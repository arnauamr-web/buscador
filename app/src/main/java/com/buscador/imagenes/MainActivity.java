package com.buscador.imagenes;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final int PICK_IMAGE = 1;
    private static final int REQ_NOTIF = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Android 13 o superior: pedir permiso para poder avisar al terminar
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

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
                }
                // Cualquier otra cosa no se abre nunca dentro de la app.
                return !"file".equals(scheme);
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
    protected void onResume() {
        super.onResume();
        // Al volver a la app, comprobar enseguida si ya hay resultado.
        if (web != null) {
            web.evaluateJavascript("window.__poll && window.__poll()", null);
        }
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

    private SharedPreferences prefs() {
        return getSharedPreferences(SearchService.PREFS, MODE_PRIVATE);
    }

    // Funciones que la página puede llamar.
    private class Bridge {

        @JavascriptInterface
        public String startSearch(String json) {
            if (SearchService.alive) {
                return "busy";
            }
            try {
                FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "job.json"));
                out.write(json.getBytes("UTF-8"));
                out.close();

                SharedPreferences.Editor e = prefs().edit();
                e.putString("state", "running");
                e.putString("step", "Preparando la búsqueda…");
                e.putString("error", "");
                e.putLong("jobId", System.currentTimeMillis());
                e.putBoolean("seen", false);
                e.commit();

                SearchService.cancelled = false;
                SearchService.alive = true;
                Intent i = new Intent(MainActivity.this, SearchService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
                return "ok";
            } catch (Exception ex) {
                SearchService.alive = false;
                return "error:" + ex.getMessage();
            }
        }

        @JavascriptInterface
        public String getState() {
            SharedPreferences p = prefs();
            String state = p.getString("state", "idle");
            String error = p.getString("error", "");
            // Si el sistema cerró la app a media búsqueda, no dejarla en "buscando" para siempre.
            if ("running".equals(state) && !SearchService.alive) {
                state = "error";
                error = "La búsqueda se interrumpió. Inténtalo otra vez.";
                SharedPreferences.Editor e = p.edit();
                e.putString("state", state);
                e.putString("error", error);
                e.putBoolean("seen", false);
                e.commit();
            }
            try {
                JSONObject o = new JSONObject();
                o.put("state", state);
                o.put("step", p.getString("step", ""));
                o.put("error", error);
                o.put("jobId", p.getLong("jobId", 0));
                o.put("seen", p.getBoolean("seen", true));
                return o.toString();
            } catch (Exception ex) {
                return "{\"state\":\"idle\"}";
            }
        }

        @JavascriptInterface
        public String getResult() {
            try {
                File f = new File(getFilesDir(), "result.json");
                if (!f.exists()) {
                    return "";
                }
                InputStream in = new FileInputStream(f);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
                in.close();
                return buf.toString("UTF-8");
            } catch (Exception ex) {
                return "";
            }
        }

        @JavascriptInterface
        public void markSeen() {
            prefs().edit().putBoolean("seen", true).commit();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(SearchService.ID_DONE);
        }

        @JavascriptInterface
        public void cancel() {
            SearchService.cancel();
            SharedPreferences.Editor e = prefs().edit();
            e.putString("state", "idle");
            e.putString("step", "");
            e.putString("error", "");
            e.putBoolean("seen", true);
            e.commit();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(SearchService.ID_PROGRESS);
        }
    }
}
