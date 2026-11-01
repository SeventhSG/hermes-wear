package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Disk cache for chat media (images, PDFs, video, audio, text) keyed by sha1(url), LRU-trimmed, plus the worker pools
 * that fetch (network) and decode (CPU). Viewers use {@link #file} / {@link #bytes} so nothing is downloaded twice.
 */
final class MediaCache {
    private MediaCache() {}

    static final String DIR = "media-cache";
    static final long MAX_BYTES = 200L * 1024 * 1024;

    /** 4 threads for downloads, 2 low-priority threads for decoding so scrolling stays smooth. */
    static final ExecutorService NET = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "media-net"); t.setDaemon(true); return t; });
    static final ExecutorService DECODE = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "media-decode"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    });

    private static volatile File dir;
    /** One lock per key: concurrent requests for the same URL wait for the first download instead of repeating it. */
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    static void init(Context c) {
        if (dir != null) return;
        File d = new File(c.getApplicationContext().getCacheDir(), DIR);
        d.mkdirs();
        dir = d;
    }

    static boolean ready() { return dir != null; }

    // ---- pure helpers (unit tested) ----

    static String key(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Largest power-of-two sample size that keeps the decoded width at or above target (thumbnails). */
    static int sampleForWidth(int w, int target) {
        int s = 1;
        if (w <= 0 || target <= 0) return 1;
        while (w / (s * 2) >= target) s *= 2;
        return s;
    }

    /** Smallest power-of-two sample size that brings both sides within max (full decode for the viewer). */
    static int sampleForMax(int w, int h, int max) {
        int s = 1;
        while (max > 0 && (w / s > max || h / s > max)) s *= 2;
        return s;
    }

    /** Indexes to delete, oldest first, so the remaining total is at most max. */
    static List<Integer> victims(long[] sizes, long[] times, long max) {
        long total = 0;
        for (long s : sizes) total += s;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) order.add(i);
        order.sort((x, y) -> Long.compare(times[x], times[y]));
        List<Integer> out = new ArrayList<>();
        for (int i : order) { if (total <= max) break; out.add(i); total -= sizes[i]; }
        return out;
    }

    /** File extension for a URL or data: URI ("" when unknown). */
    static String extFor(String url) {
        if (url.startsWith("data:")) {
            int semi = url.indexOf(';'), comma = url.indexOf(',');
            int end = semi > 0 ? semi : comma;
            String mime = end > 5 ? url.substring(5, end) : "";
            if (mime.endsWith("jpeg")) return "jpg";
            int slash = mime.indexOf('/');
            String e = slash >= 0 ? mime.substring(slash + 1).replaceAll("[^a-zA-Z0-9]", "") : "";
            return e.length() > 5 ? "" : e;
        }
        String e = MarkdownView.ext(url);
        return e.length() > 5 || !e.matches("[a-z0-9]*") ? "" : e;
    }

    // ---- disk ----

    /** The media's local file, downloading (or decoding a data: URI) on first use. Blocks; call off the UI thread. */
    static File file(String url, MarkdownView.Fetcher fetcher) throws Exception {
        File d = dir;
        if (d == null) throw new IllegalStateException("cache not initialised");
        String k = key(url), ext = extFor(url);
        File f = new File(d, ext.isEmpty() ? k : k + "." + ext);
        if (f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return f; }
        Object lock = LOCKS.computeIfAbsent(k, x -> new Object());
        synchronized (lock) {
            try {
                if (f.length() > 0) return f;
                byte[] bytes;
                if (url.startsWith("data:")) {
                    int comma = url.indexOf(',');
                    if (comma < 0) throw new IllegalArgumentException("bad data uri");
                    bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
                } else if (fetcher != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("/"))) {
                    bytes = fetcher.fetch(url);
                } else throw new IllegalStateException("no fetcher");
                File tmp = new File(d, f.getName() + ".part");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(bytes); }
                if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
            } finally { LOCKS.remove(k); }
        }
        trim();
        return f;
    }

    static byte[] bytes(String url, MarkdownView.Fetcher fetcher) throws Exception {
        File f = file(url, fetcher);
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return b;
        }
    }

    /** Sidecar for derived artifacts (poster, page thumbnail, metadata) kept next to the media. */
    static File derived(File media, String suffix) { return new File(media.getParentFile(), media.getName() + "." + suffix); }

    static void trim() {
        File d = dir;
        File[] all = d == null ? null : d.listFiles();
        if (all == null) return;
        long[] sizes = new long[all.length], times = new long[all.length];
        for (int i = 0; i < all.length; i++) { sizes[i] = all[i].length(); times[i] = all[i].lastModified(); }
        for (int i : victims(sizes, times, MAX_BYTES)) all[i].delete();
    }

    /** Decodes a cached image file at roughly target px wide; opaque JPEGs use RGB_565. */
    static Bitmap decodeFile(File f, int targetW) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sampleForWidth(o.outWidth, targetW);
        if ("image/jpeg".equals(o.outMimeType)) d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    /** Full-size-ish decode (both sides within max), used by the viewer. */
    static Bitmap decodeMax(File f, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = sampleForMax(o.outWidth, o.outHeight, max);
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    static int[] bounds(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        return new int[]{o.outWidth, o.outHeight};
    }
}
