package cloud.stormlabs.hermescompanion;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Photos sent in chat, kept on the phone. The server's history drops image parts (it stores "[screenshot]"
 * or a text description), so a bubble finds its photos again by session and send time.
 * Files are "{session}@{millis}-{n}.jpg".
 */
final class SentImages {
    private SentImages() {}

    static final long WINDOW_MS = 5 * 60_000, MAX_BYTES = 150L * 1024 * 1024;
    private static File dir;

    private static synchronized File dir(Context c) {
        if (dir == null) { dir = new File(c.getApplicationContext().getFilesDir(), "sent-images"); dir.mkdirs(); }
        return dir;
    }

    private static String stem(String sessionId) { return sessionId.replaceAll("[^A-Za-z0-9_-]", "_") + "@"; }

    static void save(Context c, String sessionId, List<String> dataUrls) {
        File d = dir(c); long now = System.currentTimeMillis();
        for (int i = 0; i < dataUrls.size(); i++) {
            String u = dataUrls.get(i); int comma = u.indexOf(',');
            if (comma < 0) continue;
            try (FileOutputStream out = new FileOutputStream(new File(d, stem(sessionId) + now + "-" + i + ".jpg"))) {
                out.write(Base64.decode(u.substring(comma + 1), Base64.DEFAULT));
            } catch (Exception ignored) {}
        }
        trim(d);
    }

    /** The photos sent closest to {@code atSeconds} in this session, as data: URLs; empty when none are near. */
    static List<String> find(Context c, String sessionId, double atSeconds) {
        File[] files = dir(c).listFiles(); List<String> out = new ArrayList<>();
        if (files == null) return out;
        List<String> names = new ArrayList<>();
        for (File f : files) names.add(f.getName());
        long at = pick(names, sessionId, atSeconds);
        if (at < 0) return out;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            if (!f.getName().startsWith(stem(sessionId) + at + "-")) continue;
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()]; int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                out.add("data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Send time (millis) of the batch nearest {@code atSeconds}, or -1 when none is within the window. */
    static long pick(List<String> names, String sessionId, double atSeconds) {
        String stem = stem(sessionId); long best = -1, gap = WINDOW_MS + 1;
        for (String n : names) {
            if (!n.startsWith(stem)) continue;
            int dash = n.indexOf('-', stem.length());
            if (dash < 0) continue;
            try {
                long ms = Long.parseLong(n.substring(stem.length(), dash)), g = Math.abs(ms - (long) (atSeconds * 1000));
                if (g < gap) { gap = g; best = ms; }
            } catch (NumberFormatException ignored) {}
        }
        return best;
    }

    /** Oldest photos go first once the folder is over its cap. */
    private static void trim(File d) {
        File[] files = d.listFiles(); if (files == null) return;
        long total = 0; for (File f : files) total += f.length();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) { if (total <= MAX_BYTES) break; total -= f.length(); f.delete(); }
    }
}
