package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-app viewer for chat files (cacheDir/media): PDF pages, text/CSV/Markdown and audio, with Save/Share/Open with. Images and video open in {@link MediaViewerActivity}. */
public final class FileViewerActivity extends Activity {
    static final String EXTRA_PATH = "path", EXTRA_NAME = "name";
    static final Set<String> TEXT = set("txt", "md", "csv", "tsv", "json", "log", "xml", "yaml", "yml", "ini", "conf", "py", "sh", "js", "html", "htm");
    static final Set<String> IMAGE = set("png", "jpg", "jpeg", "gif", "webp", "bmp");
    static final Set<String> VIDEO = set("mp4", "webm", "mov", "mkv", "3gp");
    static final Set<String> AUDIO = set("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac");
    private static final int TEXT_LIMIT = 512 * 1024;

    private static Set<String> set(String... v) { return new java.util.HashSet<>(java.util.Arrays.asList(v)); }

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private PdfRenderer pdf; private ParcelFileDescriptor pfd;
    private VideoView video;

    /** Whether the in-app viewer handles this extension (others go to another app). */
    static boolean handles(String ext) { return ext.equals("pdf") || TEXT.contains(ext) || IMAGE.contains(ext) || VIDEO.contains(ext) || AUDIO.contains(ext); }

    static void open(Context c, File f, String name) {
        String e = MarkdownView.ext(name);
        if (IMAGE.contains(e) || VIDEO.contains(e)) { MediaViewerActivity.openFile(c, f, name, VIDEO.contains(e)); return; }
        c.startActivity(new Intent(c, FileViewerActivity.class).putExtra(EXTRA_PATH, f.getAbsolutePath()).putExtra(EXTRA_NAME, name));
    }

    private int dp(int n) { return Ui.dp(this, n); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashReporter.install(this);
        Ui.legacyWindowInsets(this);
        File f = new File(String.valueOf(getIntent().getStringExtra(EXTRA_PATH)));
        String name = String.valueOf(getIntent().getStringExtra(EXTRA_NAME));
        // Only files the chat cached for us.
        if (!f.isFile() || !f.getAbsolutePath().startsWith(new File(getCacheDir(), "media").getAbsolutePath())) { finish(); return; }
        String ext = MarkdownView.ext(name.isEmpty() ? f.getName() : name);
        int bg = Ui.BG;
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackgroundColor(Ui.BAR);
        bar.addView(button(R.drawable.ic_back, "Back", v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(this, name, 16, Ui.TEXT, true);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setPadding(dp(4), 0, dp(4), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button(R.drawable.ic_download, "Save to Downloads", v -> save(this, f, name)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(button(R.drawable.ic_share, "Share", v -> share(this, f, name)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(button(R.drawable.ic_more, "Open with", v -> openWith(this, f, name)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        root.addView(body(f, ext), new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        Ui.fitInsets(root, root);
    }

    private ImageButton button(int icon, String label, View.OnClickListener click) {
        ImageButton b = Ui.icon(this, icon, label);
        b.setOnClickListener(click);
        return b;
    }

    private View body(File f, String ext) {
        try {
            if (ext.equals("pdf")) return pdfPages(f);
            if (AUDIO.contains(ext)) return player(f, true);
            if (TEXT.contains(ext)) return text(f, ext);
        } catch (Exception e) { return message("Couldn't open this file: " + HermesApi.friendly(e)); }
        return message("No preview for this file type. Use Open with or Save to Downloads.");
    }

    private View message(String s) {
        TextView t = Ui.text(this, s, 14, Ui.SUB, false);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(24), dp(24), dp(24), dp(24));
        return t;
    }

    /** Every page at screen width, rendered on demand off the UI thread. */
    private View pdfPages(File f) throws Exception {
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        pdf = new PdfRenderer(pfd);
        final int width = getResources().getDisplayMetrics().widthPixels - dp(16);
        final LruCache<Integer, Bitmap> pages = new LruCache<Integer, Bitmap>(48 * 1024 * 1024) {
            @Override protected int sizeOf(Integer k, Bitmap v) { return v.getByteCount(); }
        };
        ListView list = new ListView(this);
        list.setDivider(null); list.setBackgroundColor(Ui.BG); list.setPadding(dp(8), dp(4), dp(8), dp(8)); list.setClipToPadding(false);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return pdf.getPageCount(); }
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View old, ViewGroup parent) {
                ImageView iv = old instanceof ImageView ? (ImageView) old : new ImageView(FileViewerActivity.this);
                iv.setAdjustViewBounds(true); iv.setBackgroundColor(Color.WHITE); iv.setPadding(0, 0, 0, 0);
                iv.setLayoutParams(new ListView.LayoutParams(-1, -2));
                iv.setTag(i);
                iv.setContentDescription("Page " + (i + 1) + " of " + getCount());
                Bitmap cached = pages.get(i);
                if (cached != null) { iv.setImageBitmap(cached); return iv; }
                iv.setImageBitmap(null); iv.setMinimumHeight(Math.round(width * 1.41f));
                work.submit(() -> {
                    Bitmap bmp;
                    synchronized (FileViewerActivity.this) {
                        if (pdf == null) return;
                        try (PdfRenderer.Page p = pdf.openPage(i)) {
                            bmp = Bitmap.createBitmap(width, Math.max(1, Math.round(width * (float) p.getHeight() / p.getWidth())), Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        }
                    }
                    pages.put(i, bmp);
                    runOnUiThread(() -> { if (Integer.valueOf(i).equals(iv.getTag())) { iv.setMinimumHeight(0); iv.setImageBitmap(bmp); } });
                });
                return iv;
            }
        });
        list.setDividerHeight(dp(8));
        return list;
    }

    /** Video, or audio with a large icon above the controls; both use the platform player and controls. */
    private View player(File f, boolean audio) {
        FrameLayout box = new FrameLayout(this);
        video = new VideoView(this);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(box);
        video.setMediaController(controls);
        video.setVideoURI(Uri.fromFile(f));
        video.setOnPreparedListener(mp -> { video.start(); controls.show(0); });
        video.setOnErrorListener((mp, what, extra) -> { Toast.makeText(this, "Can't play this file here. Try Open with.", Toast.LENGTH_LONG).show(); return true; });
        box.addView(video, new FrameLayout.LayoutParams(audio ? 1 : -1, audio ? 1 : -2, Gravity.CENTER));
        if (audio) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_speaker);
            icon.setColorFilter(Ui.BLUE);
            icon.setPadding(dp(28), dp(28), dp(28), dp(28));
            icon.setBackground(Ui.round(this, (Ui.BLUE & 0xffffff) | 0x22000000, 40));
            icon.setOnClickListener(v -> { if (video.isPlaying()) video.pause(); else video.start(); controls.show(0); });
            icon.setContentDescription("Play or pause");
            box.addView(icon, new FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER));
        }
        box.setOnClickListener(v -> controls.show(0));
        return box;
    }

    /** Text files as monospace text (CSV/TSV as a table, Markdown rendered), capped at 512 KB. */
    private View text(File f, String ext) throws Exception {
        String s = read(f, TEXT_LIMIT);
        boolean cut = f.length() > TEXT_LIMIT;
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(24));
        if (ext.equals("md")) col.addView(MarkdownView.render(this, s, Ui.TEXT, 15, null, null));
        else if ((ext.equals("csv") || ext.equals("tsv")) && s.length() < 200_000) col.addView(MarkdownView.render(this, csvTable(s, ext.equals("tsv") ? '\t' : ','), Ui.TEXT, 14, null, null));
        else {
            HorizontalScrollView h = new HorizontalScrollView(this);
            TextView t = Ui.text(this, s, 13, Ui.TEXT, false);
            t.setTypeface(Typeface.MONOSPACE); t.setTextIsSelectable(true);
            h.addView(t);
            col.addView(h);
        }
        if (cut) col.addView(Ui.text(this, "\nShowing the first 512 KB. Save or open it elsewhere for the rest.", 12, Ui.FAINT, false));
        scroll.addView(col);
        return scroll;
    }

    /** CSV (quoted fields allowed) to a Markdown table, first 500 rows. */
    static String csvTable(String s, char sep) {
        StringBuilder md = new StringBuilder(); int rows = 0;
        for (String line : s.split("\r?\n")) {
            if (line.trim().isEmpty()) continue;
            StringBuilder row = new StringBuilder("|"); StringBuilder cell = new StringBuilder(); boolean q = false; int cells = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; } else q = !q; }
                else if (c == sep && !q) { row.append(' ').append(cell.toString().replace("|", "\\|").trim()).append(" |"); cell.setLength(0); cells++; }
                else cell.append(c);
            }
            row.append(' ').append(cell.toString().replace("|", "\\|").trim()).append(" |"); cells++;
            md.append(row).append('\n');
            if (rows == 0) { md.append('|'); for (int k = 0; k < cells; k++) md.append(" --- |"); md.append('\n'); }
            if (++rows >= 500) break;
        }
        return md.toString();
    }

    static String read(File f, int limit) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), limit)]; int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    // ── actions shared with the chat's file cards ────────────────────────────────────────────

    static Uri uri(Context c, File f) { return Uri.parse("content://" + c.getPackageName() + ".pdf/" + Uri.encode(f.getName())); }

    static String mimeOf(String name) { return MarkdownView.mime(MarkdownView.ext(name)); }

    static void openWith(Activity a, File f, String name) {
        try {
            a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(uri(a, f), mimeOf(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open " + name));
        } catch (Exception e) { Toast.makeText(a, "No app can open this file", Toast.LENGTH_SHORT).show(); }
    }

    static void share(Activity a, File f, String name) {
        try {
            a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(mimeOf(name)).putExtra(Intent.EXTRA_STREAM, uri(a, f))
                    .putExtra(Intent.EXTRA_TITLE, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share " + name));
        } catch (Exception e) { Toast.makeText(a, "Couldn't share this file", Toast.LENGTH_SHORT).show(); }
    }

    /** Copies the file into Downloads/Hermes (MediaStore, Android 10+; older versions get the share sheet to save it). */
    static void save(Activity a, File f, String name) {
        if (Build.VERSION.SDK_INT < 29) { share(a, f, name); return; }
        new Thread(() -> {
            boolean ok = false;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name.isEmpty() ? f.getName() : name);
                v.put(MediaStore.Downloads.MIME_TYPE, mimeOf(name));
                v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Hermes");
                Uri u = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u != null) try (OutputStream out = a.getContentResolver().openOutputStream(u); InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ok = true;
                }
            } catch (Exception ignored) { }
            boolean saved = ok;
            a.runOnUiThread(() -> Toast.makeText(a, saved ? "Saved to Downloads/Hermes" : "Couldn't save the file", Toast.LENGTH_SHORT).show());
        }, "save-download").start();
    }

    /** Long-press menu for a downloaded chat file. */
    static void actions(Activity a, File f, String name) {
        java.util.List<Sheets.Item> items = new java.util.ArrayList<>();
        if (handles(MarkdownView.ext(name))) items.add(Sheets.Item.of(R.drawable.ic_file, Ui.BLUE_DARK, "Open", "View it in Hermes", () -> open(a, f, name)));
        items.add(Sheets.Item.of(R.drawable.ic_download, Ui.BLUE_DARK, "Save to Downloads", "Downloads/Hermes", () -> save(a, f, name)));
        items.add(Sheets.Item.of(R.drawable.ic_share, Ui.BLUE_DARK, "Share", "Send it to another app", () -> share(a, f, name)));
        items.add(Sheets.Item.of(R.drawable.ic_more, Ui.BLUE_DARK, "Open with…", "Choose another app", () -> openWith(a, f, name)));
        Sheets.actions(a, name, String.format(Locale.ROOT, "%s · %s", MarkdownView.ext(name).toUpperCase(Locale.ROOT),
                android.text.format.Formatter.formatShortFileSize(a, f.length())), items.toArray(new Sheets.Item[0]));
    }

    @Override protected void onPause() { super.onPause(); if (video != null && video.isPlaying()) video.pause(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
        synchronized (this) {
            try { if (pdf != null) pdf.close(); if (pfd != null) pfd.close(); } catch (Exception ignored) { }
            pdf = null;
        }
        if (video != null) video.stopPlayback();
    }
}
