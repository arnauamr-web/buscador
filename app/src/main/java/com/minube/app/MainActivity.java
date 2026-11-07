package com.minube.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

public class MainActivity extends Activity {

    private static final int PICK_FILES = 1;
    // La página se sirve desde los archivos de la app con una dirección https propia.
    // Así funcionan el portapapeles, el cifrado y el almacenamiento como en un navegador normal.
    private static final String HOST = "nube.local";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    private final Object pdfLock = new Object();
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfFd;
    private int pdfId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#0B1020"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B1020"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B1020"));
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setSupportZoom(false);

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!HOST.equals(u.getHost())) {
                    return null;
                }
                String path = u.getPath();
                if (path == null || path.equals("/")) {
                    path = "/index.html";
                }
                if (path.startsWith("/_pdf/")) {
                    return pdfPage(path);
                }
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mimeOf(path), "UTF-8", in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) {
                    return false;
                }
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                }
                return true;
            }
        });

        // Abre la galería o el selector de archivos del móvil desde los botones de la página.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(intent, PICK_FILES);
                } catch (Exception e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("https://" + HOST + "/index.html");
    }

    private String mimeOf(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILES) {
            if (fileCallback != null) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // Botón atrás: primero cierra lo que esté abierto dentro de la página.
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back && window.__back()) ? '1' : '0'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"\"1\"".equals(value)) {
                    MainActivity.super.onBackPressed();
                }
            }
        });
    }

    // Al salir de la app se bloquean las carpetas secretas.
    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.evaluateJavascript("window.__lock && window.__lock()", null);
        }
    }

    @Override
    protected void onDestroy() {
        closePdf();
        super.onDestroy();
    }

    // ---------- PDF con el visor de Android ----------

    private void closePdf() {
        synchronized (pdfLock) {
            try {
                if (pdfRenderer != null) {
                    pdfRenderer.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (pdfFd != null) {
                    pdfFd.close();
                }
            } catch (Exception ignored) {
            }
            pdfRenderer = null;
            pdfFd = null;
        }
    }

    private WebResourceResponse pdfPage(String path) {
        // Formato: /_pdf/<id>/<pagina>.jpg
        try {
            String[] parts = path.substring("/_pdf/".length()).split("/");
            int id = Integer.parseInt(parts[0]);
            int page = Integer.parseInt(parts[1].replace(".jpg", ""));
            byte[] bytes;
            synchronized (pdfLock) {
                if (pdfRenderer == null || id != pdfId || page < 0 || page >= pdfRenderer.getPageCount()) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
                }
                PdfRenderer.Page p = pdfRenderer.openPage(page);
                int w = 1200;
                int h = Math.max(1, Math.round((float) p.getHeight() / (float) p.getWidth() * w));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
                bmp.recycle();
                bytes = out.toByteArray();
            }
            return new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 500, "Error",
                    new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private void pdfDone(final int id, final int pages, final String error) {
        final String js = "window.__pdfDone && window.__pdfDone(" + id + "," + pages + "," + JSONObject.quote(error == null ? "" : error) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    // Funciones que la página puede llamar.
    private class Bridge {

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Mi Nube", text));
        }

        @JavascriptInterface
        public void share(String text) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            Intent chooser = Intent.createChooser(i, "Compartir enlace");
            startActivity(chooser);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
        }

        // Descarga el PDF a la memoria temporal de la app y lo prepara para verlo página a página.
        @JavascriptInterface
        public void pdfOpen(final String url) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        closePdf();
                        File f = new File(getCacheDir(), "visor.pdf");
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        if (c.getResponseCode() != 200) {
                            pdfDone(0, 0, "No se pudo descargar el PDF.");
                            return;
                        }
                        InputStream in = c.getInputStream();
                        FileOutputStream out = new FileOutputStream(f);
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                        out.close();
                        in.close();
                        int pages;
                        int id;
                        synchronized (pdfLock) {
                            pdfFd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                            pdfRenderer = new PdfRenderer(pdfFd);
                            pdfId++;
                            id = pdfId;
                            pages = pdfRenderer.getPageCount();
                        }
                        pdfDone(id, pages, null);
                    } catch (Exception e) {
                        closePdf();
                        pdfDone(0, 0, "No se pudo abrir el PDF.");
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void pdfClose() {
            closePdf();
        }
    }
}
