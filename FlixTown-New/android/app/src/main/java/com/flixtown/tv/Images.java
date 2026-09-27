package com.flixtown.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Images {
    private static final ExecutorService IMAGE_IO = Executors.newFixedThreadPool(4);
    private static final LruCache<String,Bitmap> MEMORY = new LruCache<String,Bitmap>(24*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap){return Math.max(1,bitmap.getByteCount()/1024);}
    };
    static void load(ImageView view, String url, int maxWidth) {
        view.setTag(url); view.setImageDrawable(null);
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        String key = maxWidth + ":" + url;
        Bitmap cached = MEMORY.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        IMAGE_IO.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(url).openConnection();
                connection.setConnectTimeout(4000); connection.setReadTimeout(7000);
                connection.setInstanceFollowRedirects(false);
                if (connection.getContentLength() > 5_000_000) return;
                byte[] data; try (java.io.InputStream in = connection.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) != -1) { out.write(buf,0,n); if (out.size() > 5_000_000) return; }
                    data = out.toByteArray();
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1,bounds.outWidth/maxWidth)));
                Bitmap bmp = BitmapFactory.decodeByteArray(data,0,data.length,opts);
                if (bmp != null) {
                    MEMORY.put(key,bmp);
                    view.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bmp); });
                }
            } catch (Exception ignored) {} finally { if (connection != null) connection.disconnect(); }
        });
    }
}
