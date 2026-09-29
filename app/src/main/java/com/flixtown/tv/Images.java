package com.flixtown.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Images {
    private static final ExecutorService IMAGE_IO = Executors.newFixedThreadPool(4);
    private static final long DISK_LIMIT = 80L * 1024 * 1024;
    private static final LruCache<String,Bitmap> MEMORY = new LruCache<String,Bitmap>(24*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap){return Math.max(1,bitmap.getByteCount()/1024);}
    };
    private static volatile File diskDir;

    /** Enables the small poster disk cache so artwork is not downloaded again on every launch. */
    static void init(Context context) {
        if (diskDir != null) return;
        File dir = new File(context.getApplicationContext().getCacheDir(), "posters");
        if (!dir.exists() && !dir.mkdirs()) return;
        diskDir = dir;
        IMAGE_IO.execute(Images::trimDisk);
    }

    static void load(ImageView view, String url, int maxWidth) {
        view.setTag(url); view.setImageDrawable(null);
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        String key = maxWidth + ":" + url;
        Bitmap cached = MEMORY.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        IMAGE_IO.execute(() -> {
            // Fast scrolling queues many requests; skip the ones whose card was already rebound.
            if (!url.equals(view.getTag())) return;
            try {
                byte[] data = readDisk(url);
                boolean fromDisk = data != null;
                if (data == null) data = download(url);
                if (data == null) return;
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                if (bounds.outWidth <= 0) return;
                if (!fromDisk) writeDisk(url, data);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1,bounds.outWidth/maxWidth)));
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeByteArray(data,0,data.length,opts);
                if (bmp != null) {
                    MEMORY.put(key,bmp);
                    view.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bmp); });
                }
            } catch (Exception ignored) {}
        });
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(4000); connection.setReadTimeout(7000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200 || connection.getContentLength() > 5_000_000) return null;
            try (java.io.InputStream in = connection.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) { out.write(buf,0,n); if (out.size() > 5_000_000) return null; }
                return out.toByteArray();
            }
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static File diskFile(String url) {
        File dir = diskDir;
        return dir == null ? null : new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
    }
    private static byte[] readDisk(String url) {
        File file = diskFile(url);
        if (file == null || !file.isFile()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf,0,n);
            file.setLastModified(System.currentTimeMillis());
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }
    private static void writeDisk(String url, byte[] data) {
        File file = diskFile(url);
        if (file == null) return;
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); }
        catch (Exception e) { temp.delete(); return; }
        if (!temp.renameTo(file)) temp.delete();
    }
    private static void trimDisk() {
        File dir = diskDir;
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_LIMIT) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_LIMIT * 3 / 4) break;
            long size = f.length();
            if (f.delete()) total -= size;
        }
    }
}
