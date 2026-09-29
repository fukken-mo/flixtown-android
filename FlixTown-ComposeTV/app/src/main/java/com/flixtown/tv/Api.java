package com.flixtown.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Api {
    static final ExecutorService IO = Executors.newFixedThreadPool(3);
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("flix", Context.MODE_PRIVATE); }
    static JSONObject get(String url) throws Exception { return new JSONObject(request(url, null)); }
    static JSONArray list(String url) throws Exception { return new JSONArray(request(url, null)); }
    static JSONObject post(String name, JSONObject body) throws Exception {
        return new JSONObject(request(BuildConfig.PANEL_URL + name + ".php", body.toString()));
    }
    static String request(String url, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        if (json != null) {
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        try (InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (stream == null) throw new IllegalStateException("Server returned " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            String result = bytes.toString("UTF-8");
            if (status >= 400) throw new IllegalStateException(new JSONObject(result).optString("error", "Server returned " + status));
            return result;
        } finally { connection.disconnect(); }
    }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { throw new IllegalStateException(e); } }
    static String xtream(Context c, String action, String extra) {
        SharedPreferences p = prefs(c);
        String[] account=AccountStore.read(c);if(account==null)throw new IllegalStateException("Sign in required");
        return p.getString("server", "http://streamtown.live:8080").replaceAll("/+$", "")
            + "/player_api.php?username=" + enc(account[0])
            + "&password=" + enc(account[1]) + "&action=" + enc(action) + extra;
    }
    static String accountUrl(Context c) {
        SharedPreferences p=prefs(c);String[] account=AccountStore.read(c);
        if(account==null)throw new IllegalStateException("Sign in required");
        return p.getString("server","http://streamtown.live:8080").replaceAll("/+$", "")
            + "/player_api.php?username="+enc(account[0])+"&password="+enc(account[1]);
    }
    static String stream(Context c, String type, String id, String extension) {
        SharedPreferences p = prefs(c);
        String[] account=AccountStore.read(c);if(account==null)throw new IllegalStateException("Sign in required");
        return p.getString("server", "http://streamtown.live:8080").replaceAll("/+$", "")
            + "/" + type + "/" + enc(account[0]) + "/" + enc(account[1]) + "/" + id + "." + extension;
    }
    static void cache(Context c, String key, String value) { prefs(c).edit().putString(key, value).apply(); }
}
