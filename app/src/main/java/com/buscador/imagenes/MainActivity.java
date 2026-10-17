package com.buscador.imagenes;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class MainActivity extends Activity {

    private static final int PICK_IMAGE = 1;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        web.addJavascriptInterface(new Bridge(), "Android");

        // Los enlaces http/https se abren en el navegador del móvil.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }
        });

        // Permite elegir una imagen de la galería desde el botón de la página.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "Elige una imagen"), PICK_IMAGE);
                } catch (Exception e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_IMAGE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // Funciones que la página puede llamar. Hacen las peticiones de red por ella.
    private class Bridge {

        @JavascriptInterface
        public void uploadImage(String id, String key, String base64) {
            String url = "https://api.imgbb.com/1/upload?expiration=600&key=" + enc(key);
            request(id, url, "POST", "image=" + enc(base64));
        }

        @JavascriptInterface
        public void lensSearch(String id, String key, String imageUrl, String extra) {
            String url = "https://serpapi.com/search.json?engine=google_lens&hl=es"
                    + "&url=" + enc(imageUrl) + "&api_key=" + enc(key);
            if (extra != null && extra.trim().length() > 0) {
                url += "&q=" + enc(extra.trim());
            }
            request(id, url, "GET", null);
        }
    }

    private void request(final String id, final String urlText, final String method, final String body) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(90000);
                    conn.setRequestMethod(method);
                    if (body != null) {
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        OutputStream out = conn.getOutputStream();
                        out.write(body.getBytes("UTF-8"));
                        out.close();
                    }
                    int code = conn.getResponseCode();
                    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String text = in == null ? "" : readAll(in);
                    JSONObject o = new JSONObject();
                    o.put("status", code);
                    o.put("body", text);
                    result = o.toString();
                } catch (Exception e) {
                    try {
                        result = new JSONObject().put("error", String.valueOf(e.getMessage())).toString();
                    } catch (Exception x) {
                        result = "{\"error\":\"fallo de red\"}";
                    }
                }
                final String js = "window.__cb(" + JSONObject.quote(id) + "," + result + ")";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(js, null);
                    }
                });
            }
        }).start();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        in.close();
        return buf.toString("UTF-8");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
