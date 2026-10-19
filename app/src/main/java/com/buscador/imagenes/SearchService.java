package com.buscador.imagenes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Hace toda la búsqueda en segundo plano (sube la imagen, consulta SerpAPI,
 * guarda el resultado) y avisa con una notificación al terminar.
 */
public class SearchService extends Service {

    static final String PREFS = "job";
    static final String CH_PROGRESS = "progreso";
    static final String CH_DONE = "listo";
    static final int ID_PROGRESS = 1;
    static final int ID_DONE = 2;

    // true desde que se pide la búsqueda hasta que termina
    static volatile boolean alive = false;
    static volatile boolean cancelled = false;
    private static volatile HttpURLConnection current;

    static void cancel() {
        cancelled = true;
        HttpURLConnection c = current;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels();
        Notification n = progressNotification("Preparando la búsqueda…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_PROGRESS, n);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                work();
            }
        }).start();
        return START_NOT_STICKY;
    }

    // ---------- trabajo principal ----------

    private void work() {
        try {
            JSONObject p = new JSONObject(readFile(new File(getFilesDir(), "job.json")));
            String imgKey = p.getString("imgbbKey");
            String serpKey = p.getString("serpKey");
            String b64 = p.getString("image");
            String what = p.optString("what", "").trim();
            String details = p.optString("details", "").trim();
            boolean wantExact = p.optBoolean("wantExact", true);
            boolean wantSimilar = p.optBoolean("wantSimilar", false);
            boolean wantExplain = p.optBoolean("wantExplain", false);

            // 1) subir la imagen unos minutos para que el buscador pueda verla
            step("Subiendo la imagen…");
            JSONObject up = http("https://api.imgbb.com/1/upload?expiration=600&key=" + enc(imgKey),
                    "POST", "image=" + enc(b64));
            String imageUrl = "";
            JSONObject data = up.optJSONObject("data");
            if (data != null) {
                imageUrl = data.optString("url", "");
            }
            if (imageUrl.length() == 0) {
                fail(friendly(errorText(up), "ImgBB"));
                return;
            }
            if (cancelled) return;

            JSONObject result = new JSONObject();
            JSONObject meta = new JSONObject();
            meta.put("what", what);
            meta.put("details", details);
            meta.put("wantExact", wantExact);
            meta.put("wantSimilar", wantSimilar);
            meta.put("wantExplain", wantExplain);
            String q = what.length() > 0 ? what : details;

            // 2) la misma imagen
            JSONObject exact = null;
            int exactCount = 0;
            if (wantExact) {
                step("Buscando la misma imagen…");
                exact = http(serp("google_lens", serpKey,
                        "type", "exact_matches", "url", imageUrl, "hl", "es", "country", "es"), "GET", null);
                exactCount = count(exact);
                result.put("exact", exact);
                if (cancelled) return;
            }

            // 3) imágenes parecidas (también si no hay ninguna igual, o para la explicación)
            boolean needAll = wantSimilar || wantExplain || (wantExact && exactCount == 0);
            meta.put("fallback", wantExact && !wantSimilar && exactCount == 0);
            JSONObject all = null;
            int allCount = 0;
            if (needAll) {
                step("Buscando imágenes parecidas…");
                String[] extra = q.length() > 0
                        ? new String[]{"url", imageUrl, "hl", "es", "country", "es", "q", q}
                        : new String[]{"url", imageUrl, "hl", "es", "country", "es"};
                all = http(serp("google_lens", serpKey, extra), "GET", null);
                allCount = count(all);
                result.put("all", all);
                if (cancelled) return;
            }

            if (exactCount == 0 && allCount == 0) {
                String bad = firstFatal(exact, all);
                if (bad != null) {
                    fail(friendly(bad, "SerpAPI"));
                    return;
                }
            }

            // 4) explicación del contexto (opcional)
            if (wantExplain) {
                step("Preparando la explicación…");
                String token = "";
                if (all != null) {
                    JSONObject ai = all.optJSONObject("ai_overview");
                    if (ai != null) {
                        token = ai.optString("page_token", "");
                    }
                }
                boolean hasText = false;
                if (token.length() > 0) {
                    JSONObject ov = http(serp("google_ai_overview", serpKey, "page_token", token), "GET", null);
                    result.put("overview", ov);
                    JSONObject a = ov.optJSONObject("ai_overview");
                    if (a != null) {
                        JSONArray tb = a.optJSONArray("text_blocks");
                        hasText = tb != null && tb.length() > 0;
                    }
                    if (cancelled) return;
                }
                if (!hasText) {
                    String topic = q;
                    if (topic.length() == 0) {
                        topic = firstTitle(all, exact);
                    }
                    if (topic.length() > 0) {
                        JSONObject ctx = http(serp("google", serpKey,
                                "q", topic, "hl", "es", "gl", "es"), "GET", null);
                        result.put("context", ctx);
                        result.put("contextTopic", topic);
                    }
                }
            }

            if (cancelled) return;
            result.put("meta", meta);
            writeFile(new File(getFilesDir(), "result.json"), result.toString());

            SharedPreferences.Editor e = prefs().edit();
            e.putString("state", "done");
            e.putString("step", "");
            e.putString("error", "");
            e.commit();
            notifyDone("Búsqueda terminada", "Toca para ver los resultados.");
        } catch (Throwable t) {
            fail("Algo falló durante la búsqueda: " + t.getMessage());
        } finally {
            alive = false;
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        }
    }

    // ---------- estado y avisos ----------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void step(String text) {
        if (cancelled) return;
        SharedPreferences.Editor e = prefs().edit();
        e.putString("step", text);
        e.commit();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(ID_PROGRESS, progressNotification(text));
    }

    private void fail(String message) {
        if (cancelled) return;
        SharedPreferences.Editor e = prefs().edit();
        e.putString("state", "error");
        e.putString("step", "");
        e.putString("error", message);
        e.commit();
        notifyDone("No se pudo completar la búsqueda", message);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CH_PROGRESS, "Búsqueda en curso", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(
                    CH_DONE, "Búsqueda terminada", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, channel);
        }
        return new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification progressNotification(String text) {
        return builder(CH_PROGRESS)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Buscando…")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
    }

    private void notifyDone(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = builder(CH_DONE)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        nm.notify(ID_DONE, n);
    }

    // ---------- ayudas de red y datos ----------

    private static String serp(String engine, String key, String... kv) {
        StringBuilder sb = new StringBuilder("https://serpapi.com/search.json?engine=");
        sb.append(enc(engine));
        sb.append("&api_key=").append(enc(key));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append('&').append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    private static JSONObject http(String urlText, String method, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlText).openConnection();
            current = c;
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setRequestMethod(method);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream out = c.getOutputStream();
                out.write(body.getBytes("UTF-8"));
                out.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            try {
                return new JSONObject(text);
            } catch (JSONException je) {
                return errorObject("Respuesta no válida del servidor (código " + code + ")");
            }
        } catch (Exception ex) {
            return errorObject(cancelled ? "Búsqueda cancelada" : "Sin conexión o fallo de red");
        } finally {
            current = null;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static JSONObject errorObject(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", msg);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static String errorText(JSONObject o) {
        if (o == null) return "";
        Object e = o.opt("error");
        if (e instanceof String) return (String) e;
        if (e instanceof JSONObject) return ((JSONObject) e).optString("message", "");
        return "";
    }

    private static int count(JSONObject o) {
        if (o == null) return 0;
        JSONArray a = o.optJSONArray("exact_matches");
        if (a == null) a = o.optJSONArray("visual_matches");
        return a == null ? 0 : a.length();
    }

    private static boolean noResults(String err) {
        String l = err.toLowerCase();
        return l.contains("returned any results") || l.contains("no results");
    }

    private static String firstFatal(JSONObject a, JSONObject b) {
        JSONObject[] list = new JSONObject[]{a, b};
        for (JSONObject o : list) {
            String err = errorText(o);
            if (err.length() > 0 && !noResults(err)) {
                return err;
            }
        }
        return null;
    }

    private static String firstTitle(JSONObject all, JSONObject exact) {
        JSONObject[] list = new JSONObject[]{all, exact};
        for (JSONObject o : list) {
            if (o == null) continue;
            JSONArray a = o.optJSONArray("visual_matches");
            if (a == null) a = o.optJSONArray("exact_matches");
            if (a != null && a.length() > 0) {
                JSONObject first = a.optJSONObject(0);
                if (first != null) {
                    String t = first.optString("title", "");
                    if (t.length() > 0) return t;
                }
            }
        }
        return "";
    }

    private static String friendly(String raw, String who) {
        String l = raw.toLowerCase();
        if (l.contains("invalid api")) {
            return "La clave de " + who + " no es correcta. Revísala en Ajustes.";
        }
        if (l.contains("out of searches") || l.contains("run out of")) {
            return "Se han acabado tus búsquedas gratis de SerpAPI de este mes.";
        }
        if (raw.length() == 0) {
            return "No se pudo conectar con " + who + ". Revisa tu conexión y tu clave.";
        }
        return raw;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
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

    private static String readFile(File f) throws Exception {
        return readAll(new FileInputStream(f));
    }

    private static void writeFile(File f, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(text.getBytes("UTF-8"));
        out.close();
    }
}
