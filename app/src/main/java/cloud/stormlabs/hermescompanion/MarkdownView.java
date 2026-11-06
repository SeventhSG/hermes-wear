package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.List;

/** Renders {@link Markdown} blocks as native views: styled text, code cards, tables and images. */
final class MarkdownView {
    private MarkdownView() {}

    /** Fetches remote image bytes (with auth when the URL is on the Hermes server). */
    interface Fetcher { byte[] fetch(String url) throws Exception; }

    /** Cancelled when its view detaches (transcript rebuilt) so queued work for dead views is skipped. */
    private static final class Job {
        volatile boolean off;
        boolean dead(Activity a) { return off || a.isDestroyed() || a.isFinishing(); }
    }

    private static Job track(Activity a, View v) {
        final Job j = new Job();
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { }
            @Override public void onViewDetachedFromWindow(View x) { j.off = true; }
        });
        return j;
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    /** Upper bound on how much of one message we lay out. Markdown.inline emits a span per punctuation
     *  byte, so a runaway message (a binary file inlined as text, an accidental multi-MB paste) can OOM
     *  the renderer and take the whole chat down. Text past the cap is replaced with a short note. */
    static final int MAX_LAYOUT_CHARS = 60_000;

    static String capped(String md) {
        if (md == null || md.length() <= MAX_LAYOUT_CHARS) return md;
        return md.substring(0, MAX_LAYOUT_CHARS)
                + "\n\n…[" + (md.length() - MAX_LAYOUT_CHARS) + " more characters hidden — message too large to display]";
    }

    static View render(Activity a, String markdown, int color, float size, View.OnLongClickListener longClick, Fetcher fetcher) {
        MediaCache.init(a);
        markdown = capped(markdown);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        List<Markdown.Block> blocks = Markdown.parse(markdown);
        boolean first = true;
        for (Markdown.Block b : blocks) {
            View v;
            int top = first ? 0 : dp(a, 6);
            switch (b.kind) {
                case HEADING: {
                    TextView t = text(a, b.text, color, size * (b.level <= 1 ? 1.3f : b.level == 2 ? 1.18f : 1.06f), longClick);
                    t.setTypeface(Ui.BOLD);
                    v = t; top = first ? 0 : dp(a, 10);
                    break;
                }
                case CODE: v = b.level == 1 && ArtifactActivity.live(b.extra) ? ArtifactActivity.card(a, b.text, longClick) : code(a, b.text, b.extra, longClick); top = first ? 0 : dp(a, 8); break;
                case QUOTE: {
                    LinearLayout q = new LinearLayout(a);
                    View bar = new View(a); bar.setBackgroundColor(Ui.BLUE);
                    q.addView(bar, new LinearLayout.LayoutParams(dp(a, 3), -1));
                    View inner = render(a, b.text, Ui.SUB, size, longClick, fetcher);
                    inner.setPadding(dp(a, 10), 0, 0, 0);
                    q.addView(inner, new LinearLayout.LayoutParams(0, -2, 1));
                    v = q;
                    break;
                }
                case BULLET: case ORDERED: {
                    LinearLayout row = new LinearLayout(a);
                    row.setPadding(dp(a, 4 + b.level * 14), 0, 0, 0);
                    TextView mark = Ui.text(a, b.kind == Markdown.Kind.BULLET ? (b.level == 0 ? "•" : "◦") : b.extra + ".", size, b.kind == Markdown.Kind.BULLET ? Ui.BLUE : Ui.SUB, b.kind == Markdown.Kind.ORDERED);
                    row.addView(mark, new LinearLayout.LayoutParams(dp(a, b.kind == Markdown.Kind.BULLET ? 16 : 24), -2));
                    row.addView(text(a, b.text, color, size, longClick), new LinearLayout.LayoutParams(0, -2, 1));
                    v = row; top = first ? 0 : dp(a, 3);
                    break;
                }
                case RULE: {
                    View line = new View(a); line.setBackgroundColor(Ui.LINE);
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(a, 1)); p.setMargins(0, dp(a, 10), 0, dp(a, 4));
                    col.addView(line, p); first = false; continue;
                }
                case IMAGE: v = image(a, b.extra, b.text, fetcher); top = first ? 0 : dp(a, 8); break;
                case FILE: v = file(a, b.extra, b.text, fetcher); top = first ? 0 : dp(a, 8); break;
                case TABLE: v = table(a, b.text, b.extra, color, longClick); top = first ? 0 : dp(a, 8); break;
                default: v = text(a, b.text, color, size, longClick);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = top;
            col.addView(v, p);
            first = false;
        }
        return col;
    }

    static TextView text(Activity a, String md, int color, float size, View.OnLongClickListener longClick) {
        md = capped(md);
        TextView t = Ui.text(a, "", size, color, false);
        Ui.leading(t, 1.42f);
        t.setText(spans(a, md));
        t.setLinkTextColor(Ui.LINK);
        if (hasLink(md)) t.setMovementMethod(LinkMovementMethod.getInstance());
        if (longClick != null) t.setOnLongClickListener(longClick);
        return t;
    }

    private static boolean hasLink(String md) { return md != null && (md.contains("](") || md.contains("http://") || md.contains("https://")); }

    static CharSequence spans(final Context a, String md) {
        Markdown.Inline in = Markdown.inline(md);
        SpannableStringBuilder sb = new SpannableStringBuilder(in.text);
        for (Markdown.Span s : in.spans) {
            if (s.start >= s.end || s.end > sb.length()) continue;
            switch (s.style) {
                case BOLD: sb.setSpan(new StyleSpan(Typeface.BOLD), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case ITALIC: sb.setSpan(new StyleSpan(Typeface.ITALIC), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case STRIKE: sb.setSpan(new StrikethroughSpan(), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case CODE:
                    sb.setSpan(new TypefaceSpan("monospace"), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Ui.CODE), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(.92f), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case LINK: {
                    final String url = s.url;
                    sb.setSpan(new ClickableSpan() {
                        @Override public void onClick(View widget) { openUrl(a, url); }
                        @Override public void updateDrawState(TextPaint ds) { ds.setColor(Ui.LINK); ds.setUnderlineText(true); }
                    }, s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
            }
        }
        return sb;
    }

    /** Single-TextView rendering (assistant overlay): block structure flattened like Markdown.plain, inline styles kept. */
    static CharSequence compact(Context a, String source) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (Markdown.Block block : Markdown.parse(DeviceActions.stripForDisplay(source))) {
            if (b.length() > 0) b.append('\n');
            switch (block.kind) {
                case CODE: { int st = b.length(); b.append(block.text);
                    b.setSpan(new TypefaceSpan("monospace"), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    b.setSpan(new ForegroundColorSpan(Ui.CODE), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break; }
                case RULE: break;
                case BULLET: b.append("\u2022 ").append(spans(a, block.text)); break;
                case ORDERED: b.append(block.extra).append(". ").append(spans(a, block.text)); break;
                case HEADING: { int st = b.length(); b.append(spans(a, block.text));
                    b.setSpan(new StyleSpan(Typeface.BOLD), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break; }
                case IMAGE: b.append("[image").append(block.text.isEmpty() ? "" : ": " + block.text).append(']'); break;
                case FILE: b.append("[file: ").append(block.text).append(']'); break;
                case TABLE: for (String[] row : Markdown.tableRows(block.text)) b.append(String.join("  ", row)).append('\n'); break;
                default: b.append(spans(a, block.text));
            }
        }
        return b;
    }

    static void openUrl(Context a, String url) {
        try {
            String u = url.matches("(?i)^[a-z][a-z0-9+.-]*:.*") ? url : "https://" + url;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            if (!(a instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Throwable t) { Toast.makeText(a, "No app can open this link", Toast.LENGTH_SHORT).show(); }
    }

    private static View code(final Activity a, final String code, String lang, View.OnLongClickListener longClick) {
        if ("proposal".equals(lang)) { View p = proposal(a, code); if (p != null) return p; }
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.stroke(a, Ui.CODE_BG, Ui.LINE, 10));
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(a, 12), dp(a, 4), dp(a, 6), dp(a, 4));
        TextView l = Ui.text(a, lang == null || lang.isEmpty() ? "code" : lang, 11, Ui.FAINT, true);
        l.setTypeface(Typeface.MONOSPACE);
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView copy = Ui.pillButton(a, "Copy", R.drawable.ic_copy, Ui.RAISED, Ui.LINE, Ui.BLUE);
        copy.setContentDescription("Copy code");
        copy.setOnClickListener(v -> {
            ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("code", code));
            Toast.makeText(a, "Code copied", Toast.LENGTH_SHORT).show();
        });
        head.addView(copy);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        TextView body = Ui.text(a, code, 12.5f, Ui.CODE_TEXT, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setLineSpacing(0, 1.15f);
        body.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 10));
        if (longClick != null) body.setOnLongClickListener(longClick);
        hs.addView(body);
        card.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** The companion skills' approval protocol: a ```proposal {"id","title","summary","action"} block answered with
     *  "Approved #id" / "Rejected #id". Anything that isn't valid proposal JSON falls back to a code block. */
    private static View proposal(final Activity a, String json) {
        final String id, title, summary, action;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json.trim());
            id = o.optString("id", "").trim(); title = o.optString("title", "").trim();
            summary = o.optString("summary", "").trim(); action = o.optString("action", "").trim();
        } catch (Exception e) { return null; }
        if (id.isEmpty() || title.isEmpty()) return null;
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 8));
        card.setBackground(Ui.stroke(a, Ui.pick(0xff1f2630, Ui.RAISED), Ui.AMBER, 12));
        TextView eyebrow = Ui.text(a, "NEEDS YOUR OK · #" + id, 11, Ui.AMBER, true);
        eyebrow.setLetterSpacing(.06f);
        card.addView(eyebrow);
        TextView t = Ui.text(a, title, 16, Ui.TEXT, true); t.setPadding(0, dp(a, 4), 0, 0); card.addView(t);
        if (!summary.isEmpty()) { TextView v = Ui.text(a, summary, 13, Ui.SUB, false); v.setPadding(0, dp(a, 2), 0, 0); card.addView(v); }
        if (!action.isEmpty()) { TextView v = Ui.text(a, "If approved: " + action, 12, Ui.FAINT, false); v.setPadding(0, dp(a, 6), 0, 0); card.addView(v); }
        final ChatActivity chat = a instanceof ChatActivity ? (ChatActivity) a : null;
        String answered = chat == null ? null : chat.proposalAnswer(id);
        if (chat != null && answered == null) chat.proposalShownUnanswered();
        LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, 6), 0, 0);
        if (answered != null) {
            row.addView(Ui.text(a, "Approved".equals(answered) ? "✓ Approved" : "✕ Rejected", 13, "Approved".equals(answered) ? Ui.GREEN : Ui.SUB, true));
        } else {
            final TextView reject = Ui.text(a, "REJECT", 13, Ui.SUB, true), approve = Ui.text(a, "APPROVE", 13, 0xffffffff, true);
            reject.setGravity(Gravity.CENTER); approve.setGravity(Gravity.CENTER);
            reject.setBackground(Ui.press(a, 0x00000000, Ui.LINE, 20)); approve.setBackground(Ui.press(a, Ui.BLUE, Ui.BLUE, 20));
            reject.setContentDescription("Reject " + title); approve.setContentDescription("Approve " + title);
            View.OnClickListener answer = v -> {
                boolean ok = v == approve;
                reject.setEnabled(false); approve.setEnabled(false); approve.setAlpha(.5f);
                if (chat != null) { chat.answerProposal(id, ok); return; }
                // Outside a chat (agent run output) there's no conversation to answer into: open the chat with the
                // answer and the proposal's own action pre-filled, for the user to review and send.
                String text = (ok ? "Approved #" : "Rejected #") + id + " (from a scheduled run): " + title + (action.isEmpty() ? "" : " — " + action);
                a.startActivity(new android.content.Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                        .putExtra(ChatActivity.EXTRA_PROMPT, text).putExtra(ChatActivity.EXTRA_AUTOSEND, false));
            };
            reject.setOnClickListener(answer); approve.setOnClickListener(answer);
            row.addView(reject, new LinearLayout.LayoutParams(dp(a, 96), dp(a, 44)));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(a, 110), dp(a, 44)); ap.setMargins(dp(a, 8), 0, 0, 0);
            row.addView(approve, ap);
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private static View table(Activity a, String raw, String sep, int color, View.OnLongClickListener longClick) {
        List<String[]> rows = Markdown.tableRows(raw);
        int[] al = Markdown.aligns(sep);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setBackground(Ui.stroke(a, 0x00000000, Ui.LINE, 8));
        grid.setClipToOutline(true);
        int cols = 0; for (String[] r : rows) cols = Math.max(cols, r.length);
        int[] widths = new int[cols];
        // Measured text widths (px), so short cells never wrap mid-word; long ones cap and wrap.
        android.text.TextPaint paint = Ui.text(a, "", 13, color, true).getPaint();
        for (String[] r : rows) for (int k = 0; k < r.length; k++) widths[k] = Math.max(widths[k], (int) Math.ceil(paint.measureText(Markdown.inline(r[k]).text)));
        for (int ri = 0; ri < rows.size(); ri++) {
            LinearLayout row = new LinearLayout(a);
            row.setBackgroundColor(ri == 0 ? (Ui.concept.light ? 0x1f000000 : 0x33ffffff) : ri % 2 == 0 ? (Ui.concept.light ? 0x0a000000 : 0x14ffffff) : 0);
            String[] r = rows.get(ri);
            for (int k = 0; k < cols; k++) {
                TextView cell = Ui.text(a, "", 13, color, ri == 0);
                cell.setText(spans(a, k < r.length ? r[k] : ""));
                if (hasLink(k < r.length ? r[k] : "")) cell.setMovementMethod(LinkMovementMethod.getInstance());
                cell.setGravity(al.length > k && al[k] == 1 ? Gravity.CENTER_HORIZONTAL : al.length > k && al[k] == 2 ? Gravity.END : Gravity.START);
                cell.setPadding(dp(a, 10), dp(a, 6), dp(a, 10), dp(a, 6));
                if (longClick != null) cell.setOnLongClickListener(longClick);
                if (k > 0) { View v = new View(a); v.setBackgroundColor(Ui.LINE); row.addView(v, new LinearLayout.LayoutParams(1, -1)); }
                row.addView(cell, new LinearLayout.LayoutParams(Math.max(dp(a, 56), Math.min(dp(a, 260), widths[k]) + dp(a, 22)), -2));
            }
            grid.addView(row);
            if (ri < rows.size() - 1) { View line = new View(a); line.setBackgroundColor(Ui.LINE); grid.addView(line, new LinearLayout.LayoutParams(-1, 1)); }
        }
        hs.addView(grid);
        return hs;
    }

    /** File card: icon, name and size (PDFs add page count and a first-page thumbnail); downloaded off the UI thread, tap opens it. */
    private static View file(final Activity a, final String url, final String name, final Fetcher fetcher) {
        final String ext = ext(name.isEmpty() ? url : name);
        final boolean pdf = ext.equals("pdf");
        final String kind = ext.isEmpty() ? "File" : ext.toUpperCase(java.util.Locale.ROOT);
        LinearLayout card = new LinearLayout(a);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(a, 10), dp(a, 10), dp(a, 12), dp(a, 10));
        card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((Ui.BLUE & 0xffffff) | 0x33000000), Ui.card(a, 2), Ui.round(a, Color.WHITE, 14)));
        Ui.press(card);
        final ImageView thumb = new ImageView(a);
        thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        thumb.setImageResource(R.drawable.ic_file);
        thumb.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.SUB));
        thumb.setBackground(Ui.round(a, Ui.tier(3), 6));
        card.addView(thumb, new LinearLayout.LayoutParams(dp(a, 56), dp(a, 72)));
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(a, 12), 0, 0, 0);
        TextView title = Ui.text(a, name, 14, Ui.TEXT, true); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final TextView sub = Ui.text(a, kind + " · loading…", 12, Ui.FAINT, false);
        col.addView(title); col.addView(sub);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        final boolean audio = FileViewerActivity.AUDIO.contains(ext), video = FileViewerActivity.VIDEO.contains(ext), text = FileViewerActivity.TEXT.contains(ext);
        if (audio || video) { thumb.setImageResource(R.drawable.ic_play); thumb.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE)); }
        final TextView preview = Ui.text(a, "", 12, Ui.SUB, false);
        preview.setTypeface(android.graphics.Typeface.MONOSPACE); preview.setMaxLines(6); preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        preview.setPadding(0, dp(a, 6), 0, 0); preview.setVisibility(View.GONE);
        col.addView(preview);
        final java.io.File[] file = new java.io.File[1];
        final MediaItem[] gallery = new MediaItem[1];
        card.setOnClickListener(v -> {
            if (video && gallery[0] != null) { MediaViewerActivity.open(a, gallery[0], fetcher); return; }
            if (file[0] == null) { if (url.startsWith("/")) Toast.makeText(a, "Couldn't download " + name, Toast.LENGTH_SHORT).show(); else openUrl(a, url); return; }
            if (FileViewerActivity.handles(ext)) FileViewerActivity.open(a, file[0], name); else FileViewerActivity.openWith(a, file[0], name);
        });
        card.setOnLongClickListener(v -> {
            if (file[0] == null) return false;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            FileViewerActivity.actions(a, file[0], name);
            return true;
        });
        // Audio plays right in the chat: tap the play button; tapping the card opens the full player.
        thumb.setImportantForAccessibility(audio ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (audio) thumb.setContentDescription("Play or pause " + name);
        if (audio) thumb.setOnClickListener(v -> { if (file[0] != null) Inline.toggle(a, file[0], thumb); });
        final Job job = track(a, card);
        MediaCache.NET.submit(() -> {
            if (job.dead(a)) return;
            java.io.File f = null;
            try { f = MediaCache.file(url, fetcher); } catch (Throwable t) { Log.d("MediaPerf", "file " + t); }
            final java.io.File ff = f;
            if (ff == null) { a.runOnUiThread(() -> { if (!job.dead(a)) fileReady(a, card, thumb, sub, preview, file, gallery, null, null, 0, null, null, url, name, kind, video); }); return; }
            MediaCache.DECODE.submit(() -> {
                if (job.dead(a)) return;
                long t0 = System.nanoTime();
                String snippet = null, dur = null; Bitmap bmp = null; int pages = 0;
                try { if (text) snippet = FileViewerActivity.read(ff, 600); } catch (Throwable ignored) { }
                if (pdf || audio || video) {
                    java.io.File meta = MediaCache.derived(ff, "meta"), th = MediaCache.derived(ff, "thumb");
                    boolean got = false;
                    if (meta.length() > 0) try {
                        org.json.JSONObject o = new org.json.JSONObject(FileViewerActivity.read(meta, 4096));
                        pages = o.optInt("pages", 0); dur = o.has("dur") ? o.getString("dur") : null;
                        if (th.length() > 0) bmp = BitmapFactory.decodeFile(th.getPath());
                        got = true; meta.setLastModified(System.currentTimeMillis());
                    } catch (Throwable ignored) { }
                    if (!got) {
                        boolean ok = true;
                        if (audio || video) try {
                            android.media.MediaMetadataRetriever mr = new android.media.MediaMetadataRetriever();
                            try {
                                mr.setDataSource(ff.getPath());
                                String ms = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                                if (ms != null) { long sec = Long.parseLong(ms) / 1000; dur = String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60); }
                                if (video) {
                                    Bitmap frame = mr.getFrameAtTime(0);
                                    if (frame != null) { bmp = Bitmap.createScaledBitmap(frame, 240, Math.max(1, 240 * frame.getHeight() / Math.max(1, frame.getWidth())), true); if (bmp != frame) frame.recycle(); }
                                }
                            } finally { mr.release(); }
                        } catch (Exception e) { ok = false; }
                        if (pdf) try (android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.open(ff, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                             android.graphics.pdf.PdfRenderer r = new android.graphics.pdf.PdfRenderer(pfd)) {
                            pages = r.getPageCount();
                            if (pages > 0) try (android.graphics.pdf.PdfRenderer.Page pg = r.openPage(0)) {
                                int w = 240, h = Math.max(1, Math.round(w * (float) pg.getHeight() / pg.getWidth()));
                                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE);
                                pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            }
                        } catch (Throwable t) { ok = false; }
                        if (ok) try {
                            if (bmp != null) try (OutputStream o = new java.io.FileOutputStream(th)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, o); }
                            org.json.JSONObject o = new org.json.JSONObject(); o.put("pages", pages); if (dur != null) o.put("dur", dur);
                            try (OutputStream os = new java.io.FileOutputStream(meta)) { os.write(o.toString().getBytes("UTF-8")); }
                        } catch (Throwable ignored) { }
                    }
                }
                Log.d("MediaPerf", "card derive " + (System.nanoTime() - t0) / 1000000 + "ms " + ff.getName());
                final String fs = snippet, fd = dur; final Bitmap fb = bmp; final int fp = pages;
                a.runOnUiThread(() -> { if (!job.dead(a)) fileReady(a, card, thumb, sub, preview, file, gallery, ff, fs, fp, fd, fb, url, name, kind, video); });
            });
        });
        return card;
    }

    private static void fileReady(Activity a, View card, ImageView thumb, TextView sub, TextView preview, java.io.File[] file, MediaItem[] gallery,
                                  java.io.File ff, String snippet, int pages, String dur, Bitmap fb, String url, String name, String kind, boolean video) {
        file[0] = ff;
        if (video && ff != null) { gallery[0] = MediaItem.video(url, name, ff, card); MediaViewerActivity.register(a, gallery[0]); }
        if (snippet != null && !snippet.trim().isEmpty()) { preview.setText(snippet.trim()); preview.setVisibility(View.VISIBLE); }
        String size = ff != null && ff.exists() ? android.text.format.Formatter.formatShortFileSize(a, ff.length()) : "";
        sub.setText(kind + (pages > 0 ? " · " + pages + (pages == 1 ? " page" : " pages") : "") + (dur != null ? " · " + dur : "") + (size.isEmpty() ? "" : " · " + size)
                + (ff != null ? " · tap to open" : url.startsWith("/") ? " · unavailable" : " · tap to open in browser"));
        if (fb != null) { thumb.setImageTintList(null); thumb.setScaleType(ImageView.ScaleType.FIT_CENTER); thumb.setImageBitmap(fb); }
    }

    static String ext(String name) {
        String n = name.replaceAll("[?#].*$", ""); int dot = n.lastIndexOf('.'), slash = n.lastIndexOf('/');
        return dot > slash && dot < n.length() - 1 ? n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    static String mime(String ext) {
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        String m = ext.isEmpty() ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "*/*" : m;
    }

    private static View image(final Activity a, final String url, final String alt, final Fetcher fetcher) {
        final ChatViews.AspectImage iv = new ChatViews.AspectImage(a);
        iv.setContentDescription(alt == null || alt.isEmpty() ? "Image" : alt);
        Ui.press(iv);
        final int target = Math.min(a.getResources().getDisplayMetrics().widthPixels, 1200);
        final String ck = key(url) + "@" + target;
        Bitmap cached = CACHE.get(ck);
        if (cached != null) { show(a, iv, cached, url, alt, fetcher); iv.loaded(cached); return iv; }
        final Job job = track(a, iv);
        MediaCache.NET.submit(() -> {
            if (job.dead(a)) return;
            long t0 = System.nanoTime();
            java.io.File f = null;
            try { f = MediaCache.file(url, fetcher); } catch (Throwable t) { Log.d("MediaPerf", "image fetch " + t); }
            final java.io.File ff = f;
            if (ff == null) { a.runOnUiThread(() -> { if (!job.dead(a)) { iv.failed(); iv.setContentDescription("Image could not be loaded"); } }); return; }
            final long fetched = (System.nanoTime() - t0) / 1000000;
            final int[] wh = MediaCache.bounds(ff);
            a.runOnUiThread(() -> { if (!job.dead(a)) iv.expect(wh[0], wh[1]); });
            MediaCache.DECODE.submit(() -> {
                if (job.dead(a)) return;
                long t1 = System.nanoTime();
                Bitmap bmp = MediaCache.decodeFile(ff, target);
                Log.d("MediaPerf", "image fetch " + fetched + "ms decode " + (System.nanoTime() - t1) / 1000000 + "ms " + wh[0] + "x" + wh[1] + " -> " + (bmp == null ? "null" : bmp.getWidth() + "x" + bmp.getHeight()));
                if (bmp != null) CACHE.put(ck, bmp);
                a.runOnUiThread(() -> {
                    if (job.dead(a)) return;
                    if (bmp != null) { show(a, iv, bmp, url, alt, fetcher); iv.loaded(bmp); } else { iv.failed(); iv.setContentDescription("Image could not be loaded"); }
                });
            });
        });
        return iv;
    }

    private static void show(final Activity a, ImageView iv, final Bitmap bmp, String url, String alt, Fetcher fetcher) {
        iv.setImageBitmap(bmp);
        final MediaItem item = MediaItem.image(url, alt, iv);
        MediaViewerActivity.register(a, item);
        iv.setOnClickListener(v -> MediaViewerActivity.open(a, item, fetcher));
        iv.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            Sheets.actions(a, "Image", null,
                    Sheets.Item.of(R.drawable.ic_download, Ui.BLUE_DARK, "Save to Pictures", "Pictures/Hermes", () -> MediaCache.DECODE.submit(() -> {
                        Bitmap full = load(url, fetcher);
                        boolean saved = full != null && saveToGallery(a, full);
                        a.runOnUiThread(() -> Toast.makeText(a, saved ? "Saved to Pictures" : "Could not save", Toast.LENGTH_SHORT).show());
                    })),
                    Sheets.Item.of(R.drawable.ic_share, Ui.BLUE_DARK, "Share", "Send it to another app", () -> MediaCache.DECODE.submit(() -> {
                        Bitmap full = load(url, fetcher);
                        if (full == null) return;
                        java.io.File dir = new java.io.File(a.getCacheDir(), "media"); dir.mkdirs();
                        java.io.File f = new java.io.File(dir, "image-" + MediaCache.key(url).substring(0, 12) + ".png");
                        try (OutputStream o = new java.io.FileOutputStream(f)) { full.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception ignored) { }
                        a.runOnUiThread(() -> FileViewerActivity.share(a, f, f.getName()));
                    })));
            return true;
        });
    }

    static String key(String url) { return url.length() > 200 ? url.length() + ":" + url.hashCode() + ":" + url.substring(url.length() - 40) : url; }

    /** Kept for the viewer: the list cache now holds screen-width thumbnails, which must not stand in for a full decode. */
    static Bitmap cached(String url) { return null; }

    /** Last path segment of a URL or server path, decoded ("" when there isn't one). */
    static String fileName(String url) {
        String n = url.replaceAll("[?#].*$", ""); int slash = n.lastIndexOf('/');
        return Uri.decode(slash >= 0 ? n.substring(slash + 1) : n);
    }

    static Bitmap load(String url, Fetcher fetcher) { return load(url, fetcher, 1600); }

    static Bitmap load(String url, Fetcher fetcher, int max) {
        try {
            if (MediaCache.ready()) return MediaCache.decodeMax(MediaCache.file(url, fetcher), max);
            byte[] bytes;
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma < 0) return null;
                bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
            } else if (fetcher != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("/"))) {
                bytes = fetcher.fetch(url);
            } else return null;
            return decode(bytes, max);
        } catch (Throwable t) { return null; }
    }

    static Bitmap decode(byte[] bytes, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = MediaCache.sampleForMax(o.outWidth, o.outHeight, max);
        BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
    }

    static boolean saveToGallery(Context c, Bitmap bmp) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "hermes-" + System.currentTimeMillis() + ".png");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Hermes");
            Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            try (OutputStream out = c.getContentResolver().openOutputStream(uri)) { return out != null && bmp.compress(Bitmap.CompressFormat.PNG, 100, out); }
        } catch (Throwable t) { return false; }
    }

    private static int dp(Context c, int v) { return Ui.dp(c, v); }

    /** One inline audio player for the whole chat: starting another file stops the last one. */
    static final class Inline {
        private static android.media.MediaPlayer player; private static ImageView button;

        static void toggle(Context c, java.io.File f, ImageView b) {
            boolean same = b == button && player != null;
            stop();
            if (same) return;
            try {
                player = new android.media.MediaPlayer();
                player.setDataSource(f.getPath());
                player.setOnCompletionListener(mp -> stop());
                player.prepare(); player.start();
                button = b; b.setImageResource(R.drawable.ic_stop);
            } catch (Exception e) { stop(); Toast.makeText(c, "Can't play this file", Toast.LENGTH_SHORT).show(); }
        }

        static void stop() {
            if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
            if (button != null) { button.setImageResource(R.drawable.ic_play); button = null; }
        }
    }
}
