package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shared pieces of the Media screens: brand colours, formatting, artwork loading and poster/carousel/chip builders. */
final class Arr {
    private Arr() {}

    static final int SONARR = 0xff35c5f4, RADARR = 0xffffc230, QBIT = 0xff4c8dff, PROWLARR = 0xffff8a3d, JELLYFIN = 0xffaa5cc3;
    static final String SON = "sonarr/api/v3/", RAD = "radarr/api/v3/", QB = "qbit/api/v2/", PRO = "prowlarr/api/v1/";
    static final String FORM = "application/x-www-form-urlencoded", JSON = "application/json";

    // ---------------------------------------------------------------- speed history (shared by Home and Downloads)

    /** {epochSeconds, down B/s, up B/s}, last ~3 minutes. */
    static final ArrayDeque<double[]> SPEED = new ArrayDeque<>();

    static void sample(long down, long up) {
        double now = System.currentTimeMillis() / 1000.0;
        SPEED.addLast(new double[]{now, down, up});
        while (!SPEED.isEmpty() && now - SPEED.peekFirst()[0] > 180) SPEED.removeFirst();
    }

    static double[][] series(int col) {
        double[][] out = new double[SPEED.size()][];
        int i = 0;
        for (double[] s : SPEED) out[i++] = new double[]{s[0], s[col]};
        return out;
    }

    // ---------------------------------------------------------------- formatting

    static String size(double b) {
        String[] u = {"B", "KB", "MB", "GB", "TB"}; int i = 0;
        while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
        return String.format(Locale.US, i < 2 ? "%.0f %s" : b >= 100 ? "%.0f %s" : "%.1f %s", b, u[i]);
    }

    static String speed(long b) { return size(b) + "/s"; }

    static String eta(long s) {
        if (s <= 0 || s >= 8640000) return "∞";
        return s >= 86400 ? s / 86400 + "d " + s % 86400 / 3600 + "h" : s >= 3600 ? s / 3600 + "h " + s % 3600 / 60 + "m" : Math.max(1, s / 60) + "m";
    }

    static Date date(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        try { return Date.from(Instant.parse(iso)); } catch (Exception e) { return null; }
    }

    static String ymd(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms)); }
    static String clock(Date d) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d); }
    static String day(Date d) { return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d); }

    /** "Today", "Tomorrow", "Yesterday", weekday within a week, else "d MMM". */
    static String when(Date d) {
        if (d == null) return "";
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance(); b.setTime(d);
        a.set(Calendar.HOUR_OF_DAY, 0); a.set(Calendar.MINUTE, 0); a.set(Calendar.SECOND, 0); a.set(Calendar.MILLISECOND, 0);
        long days = Math.round((startOfDay(b) - a.getTimeInMillis()) / 86400000.0);
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        if (days == -1) return "Yesterday";
        if (days > 1 && days < 7) return new SimpleDateFormat("EEEE", Locale.getDefault()).format(d);
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(d);
    }

    private static long startOfDay(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    static String ago(Date d) {
        if (d == null) return "";
        long s = (System.currentTimeMillis() - d.getTime()) / 1000;
        if (s < 3600) return Math.max(1, s / 60) + "m ago";
        if (s < 86400) return s / 3600 + "h ago";
        if (s < 86400 * 30) return s / 86400 + "d ago";
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(d);
    }

    static String ep(JSONObject e) { return String.format(Locale.US, "S%02dE%02d", e.optInt("seasonNumber"), e.optInt("episodeNumber")); }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // ---------------------------------------------------------------- artwork

    /** Library items come from the service's own resized MediaCover (via the hub); others from the remote URL, TMDB resized. */
    static String art(String svc, JSONObject o, String type) {
        int id = o.optInt("id");
        if (id > 0 && o.has("images")) {
            if (svc.equals("sonarr")) return "hub:sonarr/MediaCover/" + id + "/" + type + (type.equals("poster") ? "-250" : "") + ".jpg";
        }
        JSONArray imgs = o.optJSONArray("images");
        if (imgs != null) for (int i = 0; i < imgs.length(); i++) {
            JSONObject im = imgs.optJSONObject(i);
            if (im != null && type.equals(im.optString("coverType"))) {
                String u = im.optString("remoteUrl", "");
                if (!u.isEmpty()) return tmdb(u, type.equals("poster") ? "w342" : "w1280");
            }
        }
        return type.equals("poster") ? tmdb(o.optString("remotePoster", ""), "w342") : "";
    }

    private static String tmdb(String u, String size) { return u.contains("image.tmdb.org/t/p/original/") ? u.replace("/original/", "/" + size + "/") : u; }

    static String jellyfinImage(String itemId, String type, int maxHeight) {
        return "hub:jellyfin/Items/" + itemId + "/Images/" + type + "?maxHeight=" + maxHeight;
    }

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    /** Loads {@code src} ("hub:&lt;arr path&gt;" or an http(s) URL) into v, decoded to about widthPx wide. */
    static void image(Activity a, HubApi hub, ImageView v, String src, int widthPx) {
        if (src == null || src.isEmpty()) return;
        String key = src + "@" + widthPx;
        v.setTag(key);
        Bitmap hit = MEM.get(key);
        if (hit != null) { v.setImageBitmap(hit); return; }
        if (!MediaCache.ready()) return;
        String url = src.startsWith("hub:") ? hub.arrUrl(src.substring(4)) : src;
        MediaCache.NET.execute(() -> {
            try {
                Bitmap b = MediaCache.decodeFile(MediaCache.file(url, u -> src.startsWith("hub:") ? hub.arrBytes(src.substring(4)) : src.startsWith("media:") ? hub.mediaBytes(src.substring(6)) : download(u)), widthPx);
                if (b == null) return;
                MEM.put(key, b);
                a.runOnUiThread(() -> {
                    if (a.isDestroyed() || !key.equals(v.getTag())) return;
                    v.setImageBitmap(b);
                    if (Ui.motionEnabled()) { v.setAlpha(0f); v.animate().alpha(1f).setDuration(180).start(); }
                });
            } catch (Exception ignored) { }
        });
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(20000);
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > 8_000_000) throw new java.io.IOException("too large"); }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    // ---------------------------------------------------------------- views

    static int dp(Activity a, int n) { return Ui.dp(a, n); }
    static int alpha(int color, int a) { return (color & 0xffffff) | (a << 24); }

    /** Readable text on a brand-colour fill. */
    static int on(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        return (r * 299 + g * 587 + b * 114) / 1000 > 150 ? 0xff111111 : Color.WHITE;
    }

    static TextView tag(Activity a, String text, int color) {
        TextView t = Ui.text(a, text, 11, color, true);
        t.setPadding(dp(a, 7), dp(a, 2), dp(a, 7), dp(a, 2));
        t.setBackground(Ui.round(a, alpha(color, 0x2a), 99));
        t.setSingleLine(true);
        return t;
    }

    static TextView solidTag(Activity a, String text, int color) {
        TextView t = Ui.text(a, text, 10, on(color), true);
        t.setPadding(dp(a, 6), dp(a, 2), dp(a, 6), dp(a, 2));
        t.setBackground(Ui.round(a, color, 6));
        t.setSingleLine(true);
        return t;
    }

    static TextView chip(Activity a, String label, boolean on, int accent, View.OnClickListener tap) {
        TextView c = Ui.text(a, label, 13, on ? on(accent) : Ui.SUB, on);
        c.setPadding(dp(a, 14), dp(a, 7), dp(a, 14), dp(a, 7));
        c.setBackground(on ? Ui.round(a, accent, 99) : Ui.round(a, Ui.tier(2), 99));
        c.setOnClickListener(tap);
        c.setSingleLine(true);
        return c;
    }

    static LinearLayout chipRow(Activity a, LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(a, 10);
        parent.addView(hs, lp);
        return row;
    }

    static void addChip(Activity a, LinearLayout row, View chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(dp(a, 6));
        row.addView(chip, lp);
    }

    static View bar(Activity a, float p, int color, int heightDp) {
        LinearLayout track = new LinearLayout(a);
        track.setBackground(Ui.round(a, Ui.RAISED, 99));
        View fill = new View(a);
        fill.setBackground(Ui.round(a, color, 99));
        float f = Math.max(0f, Math.min(1f, p));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, Math.max(0.0001f, f)));
        track.addView(new View(a), new LinearLayout.LayoutParams(0, -1, Math.max(0.0001f, 1 - f)));
        track.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(a, heightDp)));
        return track;
    }

    /** Section title with an optional "See all" action in the accent colour. */
    static LinearLayout header(Activity a, String title, String action, int accent, View.OnClickListener tap) {
        LinearLayout h = new LinearLayout(a);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(a, 4), dp(a, 18), dp(a, 4), dp(a, 8));
        TextView t = Ui.title(a, title, Ui.TEXT);
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView act = Ui.text(a, action, 13, Ui.BLUE, true); // one link colour; the service colours stay on their own cards
            act.setPadding(dp(a, 8), dp(a, 4), 0, dp(a, 4));
            act.setOnClickListener(tap);
            h.addView(act);
        }
        return h;
    }

    /** A horizontal scroller; add poster cards to the returned row. */
    static LinearLayout carousel(Activity a, LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(a);
        row.setPadding(0, 0, dp(a, 8), 0);
        hs.addView(row);
        parent.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /**
     * 2:3 poster with a rounded clip, optional corner badge, optional progress strip along the bottom edge,
     * and one or two lines of caption below.
     */
    static LinearLayout poster(Activity a, HubApi hub, int widthDp, String img, String title, String sub,
                               String badge, int badgeColor, float progress, int accent, View.OnClickListener tap) {
        int w = dp(a, widthDp), h = w * 3 / 2;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(a);
        frame.setBackground(Ui.round(a, Ui.RAISED, 10));
        frame.setClipToOutline(true);
        frame.setElevation(dp(a, 2));
        TextView fallback = Ui.text(a, title == null ? "" : title, 13, Ui.SUB, true);
        fallback.setGravity(Gravity.CENTER); fallback.setPadding(dp(a, 8), 0, dp(a, 8), 0); fallback.setMaxLines(4);
        frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        image(a, hub, iv, img, Math.max(w, dp(a, 120)));
        if (badge != null && !badge.isEmpty()) {
            TextView b = solidTag(a, badge, badgeColor);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            bl.setMargins(dp(a, 6), dp(a, 6), dp(a, 6), 0);
            frame.addView(b, bl);
        }
        if (progress >= 0) {
            View strip = bar(a, progress, accent, 4);
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, dp(a, 4), Gravity.BOTTOM);
            sl.setMargins(dp(a, 6), 0, dp(a, 6), dp(a, 6));
            frame.addView(strip, sl);
        }
        col.addView(frame, new LinearLayout.LayoutParams(w, h));
        if (title != null) {
            TextView t = Ui.text(a, title, 13, Ui.TEXT, true);
            t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(a, 2), dp(a, 6), dp(a, 2), 0);
            col.addView(t, new LinearLayout.LayoutParams(w, -2));
        }
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(a, sub, 11, Ui.SUB, false);
            s.setSingleLine(true); s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(dp(a, 2), dp(a, 1), dp(a, 2), 0);
            col.addView(s, new LinearLayout.LayoutParams(w, -2));
        }
        if (tap != null) { col.setOnClickListener(tap); Ui.press(col); }
        col.setContentDescription(title + (sub == null ? "" : ", " + sub));
        return col;
    }

    static void addToCarousel(Activity a, LinearLayout row, View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(dp(a, 12));
        row.addView(card, lp);
    }

    /** Lays cards out {@code cols} to a row, each row filling the width. */
    static void grid(Activity a, LinearLayout parent, List<View> cards, int cols) {
        LinearLayout row = null;
        for (int i = 0; i < cards.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(a);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.bottomMargin = dp(a, 14);
                parent.addView(row, rl);
            }
            FrameLayout cell = new FrameLayout(a);
            cell.addView(cards.get(i), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (row != null) for (int k = cards.size() % cols; k != 0 && k < cols; k++) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
    }

    /** Poster width that fits {@code cols} per row inside the page padding. */
    static int gridWidthDp(Activity a, int cols) {
        float wdp = a.getResources().getDisplayMetrics().widthPixels / a.getResources().getDisplayMetrics().density;
        return (int) ((wdp - 24 - (cols - 1) * 10) / cols);
    }

}
