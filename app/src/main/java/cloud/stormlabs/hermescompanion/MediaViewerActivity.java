package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen gallery for a conversation's photos and videos: swipe between items, pinch / double-tap zoom, swipe down to dismiss,
 *  a thumbnail filmstrip, and a custom video player (skip, scrubber, volume / brightness gestures, speed, landscape fullscreen). */
public final class MediaViewerActivity extends Activity implements MediaPager.Host, VideoPage.Listener {
    static final String EXTRA_INDEX = "index", EXTRA_FROM = "from";
    private static final long HIDE_MS = 3000;

    // The chat registers every photo/video it renders; opening one hands the ordered list over (the intent can't carry bitmaps).
    private static final Map<Activity, List<MediaItem>> REGISTRY = new WeakHashMap<>();
    private static List<MediaItem> handoff;
    private static MarkdownView.Fetcher handoffFetcher;

    static void register(Activity a, MediaItem item) {
        List<MediaItem> l = REGISTRY.get(a);
        if (l == null) { l = new ArrayList<>(); REGISTRY.put(a, l); }
        l.add(item);
    }

    /** Opens the viewer on {@code tapped} with every media item currently on screen in the chat, top to bottom. */
    static void open(Activity a, MediaItem tapped, MarkdownView.Fetcher fetcher) {
        List<MediaItem> all = new ArrayList<>();
        List<int[]> pos = new ArrayList<>();
        List<MediaItem> reg = REGISTRY.get(a);
        if (reg != null) {
            for (int i = reg.size() - 1; i >= 0; i--) {   // newest registration wins; drop items whose view is gone
                MediaItem m = reg.get(i); View v = m.anchor.get();
                if (v == null || !v.isAttachedToWindow()) { reg.remove(i); continue; }
                if (all.contains(m)) continue;
                int[] xy = new int[2]; v.getLocationInWindow(xy);
                all.add(m); pos.add(xy);
            }
        }
        if (!all.contains(tapped)) { all.add(tapped); pos.add(new int[]{0, 0}); }
        Integer[] order = new Integer[all.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (p, q) -> pos.get(p)[1] != pos.get(q)[1] ? Integer.compare(pos.get(p)[1], pos.get(q)[1]) : Integer.compare(pos.get(p)[0], pos.get(q)[0]));
        List<MediaItem> sorted = new ArrayList<>();
        for (Integer i : order) sorted.add(all.get(i));
        start(a, sorted, sorted.indexOf(tapped), fetcher, tapped.anchor.get());
    }

    static void openFile(Context c, File f, String name, boolean video) {
        MediaItem m = MediaItem.of(f, name, video);
        start(c, Collections.singletonList(m), 0, null, null);
    }

    private static void start(Context c, List<MediaItem> items, int index, MarkdownView.Fetcher fetcher, View from) {
        handoff = items; handoffFetcher = fetcher;
        Intent i = new Intent(c, MediaViewerActivity.class).putExtra(EXTRA_INDEX, Math.max(0, index));
        if (from != null && from.getWidth() > 0) {
            int[] xy = new int[2]; from.getLocationOnScreen(xy);
            i.putExtra(EXTRA_FROM, new int[]{xy[0], xy[1], from.getWidth(), from.getHeight()});
        }
        c.startActivity(i);
    }

    // ── state ────────────────────────────────────────────────────────────────────────────

    private List<MediaItem> items;
    private MarkdownView.Fetcher fetcher;
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    private final Set<Integer> sharpened = new HashSet<>();
    private boolean destroyed, chromeVisible = true, seeking, centerShown, onVideo, volTouch, levelDragging, brightnessSet;
    private FrameLayout root, scrubWrap, center;
    private View bg;
    private MediaPager pager;
    private PlayerHud hud;
    private LinearLayout top, bottom, controls, levelHud, errorBox;
    private TextView title, counter, caption, timeText, bubble, speedBtn, holdChip;
    private ImageButton mute, fit, full;
    private ImageView levelIcon;
    private PlayPauseView play;
    private SkipView skipBack, skipFwd;
    private SpinnerView spinner;
    private ScrubberView scrub;
    private LevelView volPop, levelBar;
    private HorizontalScrollView strip;
    private LinearLayout stripRow;
    private int stripSel = -1, topInset, lastSec = -1, lastDur = -1, skipAcc, skipZone;
    private long streakUntil, lastVolPoll;
    private float volLevel, brightLevel;
    private final int[] xy = new int[2], rootXy = new int[2];
    private AudioManager audio;
    private AudioFocusRequest focus;
    private int preMuteVolume = -1;

    private final Runnable hide = () -> { if (autoHides()) setChrome(false); };
    private final Runnable hideVol = () -> { if (!volTouch) fade(volPop, false); };
    private final Runnable hideLevel = () -> fade(levelHud, false);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            syncControls();
            VideoPage v = currentVideo();
            if (v != null && v.playing() && Ui.motionEnabled()) root.postOnAnimation(this);   // smooth scrubber while playing
            else root.postDelayed(this, 250);
        }
    };

    private int dp(int n) { return Ui.dp(this, n); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashReporter.install(this);
        Ui.apply(this);
        items = handoff; fetcher = handoffFetcher;
        if (items == null || items.isEmpty()) { finish(); return; }
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 28) getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        root = new FrameLayout(this);
        bg = new View(this); bg.setBackgroundColor(Color.BLACK);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        pager = new MediaPager(this, this);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        hud = new PlayerHud(this);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        buildTop();
        buildCenter();
        buildBottom();
        buildOverlays();
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> { fitBars(ins); return ins; });
        root.requestApplyInsets();
        applyBars();
        relayout();

        int index = MediaMath.clampIndex(getIntent().getIntExtra(EXTRA_INDEX, 0), items.size());
        pager.setCurrent(index);
        animateOpen(getIntent().getIntArrayExtra(EXTRA_FROM));
        root.postDelayed(tick, 250);
    }

    // ── chrome ───────────────────────────────────────────────────────────────────────────

    private ImageButton button(int icon, String label, View.OnClickListener click) {
        ImageButton b = Ui.icon(this, icon, label);
        b.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setOnClickListener(v -> { click.onClick(v); kick(); });
        return b;
    }

    private static GradientDrawable scrim(boolean fromTop) {
        return new GradientDrawable(fromTop ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x66000000, 0});
    }

    private void buildTop() {
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(scrim(true));
        top.setPadding(dp(4), dp(4), dp(4), dp(28));
        top.addView(button(R.drawable.ic_back, "Back", v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(4), 0, dp(8), 0);
        title = Ui.title(this, "", Color.WHITE);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        counter = Ui.num(this, "", 12, 0xCCFFFFFF);
        names.addView(title); names.addView(counter);
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button(R.drawable.ic_download, "Save to Downloads", v -> act(0)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(button(R.drawable.ic_share, "Share", v -> act(1)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(button(R.drawable.ic_more, "Open with", v -> act(2)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    /** Big play/pause with a skip button either side, plus the loading ring over it. */
    private void buildCenter() {
        center = new FrameLayout(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        skipBack = new SkipView(this, false, "Back 10 seconds");
        skipFwd = new SkipView(this, true, "Forward 10 seconds");
        skipBack.setOnClickListener(v -> { skip(PlayerMath.LEFT, -1, -1); });
        skipFwd.setOnClickListener(v -> { skip(PlayerMath.RIGHT, -1, -1); });
        play = new PlayPauseView(this);
        play.setOnClickListener(v -> { VideoPage p = currentVideo(); if (p != null) p.toggle(); kick(); });
        row.addView(skipBack, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(64), dp(64));
        pl.setMargins(dp(24), 0, dp(24), 0);
        row.addView(play, pl);
        row.addView(skipFwd, new LinearLayout.LayoutParams(dp(56), dp(56)));
        center.addView(row, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        spinner = new SpinnerView(this);
        spinner.setVisibility(View.GONE);
        root.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        center.setVisibility(View.GONE);
    }

    private void buildBottom() {
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(scrim(false));
        bottom.setPadding(dp(8), dp(32), dp(8), dp(8));

        buildStrip();
        caption = Ui.text(this, "", 14, Color.WHITE, false);
        caption.setMaxLines(4); caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setPadding(dp(12), 0, dp(12), dp(8));
        bottom.addView(caption);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        // the time bubble lives in a reserved band above the scrubber so it can follow the thumb
        scrubWrap = new FrameLayout(this);
        bubble = Ui.num(this, "0:00", 14, Color.WHITE);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(Ui.round(this, 0xCC000000, 14));
        bubble.setVisibility(View.INVISIBLE);
        bubble.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrub = new ScrubberView(this);
        scrub.setListener(new ScrubberView.Listener() {
            @Override public void onScrubStart() {
                seeking = true; root.removeCallbacks(hide);
                bubble.animate().cancel(); bubble.setVisibility(View.VISIBLE);
                if (Ui.motionEnabled()) { bubble.setAlpha(0); bubble.animate().alpha(1).setDuration(120).setInterpolator(Ui.EASE_OUT).start(); } else bubble.setAlpha(1);
            }
            @Override public void onScrub(int ms, float thumbX) {
                VideoPage v = currentVideo(); if (v == null) return;
                bubble.setText(MediaMath.time(ms));
                bubble.setTranslationX(PlayerMath.bubbleLeft(thumbX, dp(72), scrubWrap.getWidth()));
                timeText.setText(PlayerMath.timeLabel(ms, v.duration()));
                v.seekTo(ms, true);
            }
            @Override public void onScrubEnd(int ms) {
                seeking = false;
                VideoPage v = currentVideo(); if (v != null) v.seekTo(ms, false);
                bubble.animate().cancel();
                if (Ui.motionEnabled()) bubble.animate().alpha(0).setDuration(160).setInterpolator(Ui.EASE_OUT).withEndAction(() -> bubble.setVisibility(View.INVISIBLE)).start();
                else bubble.setVisibility(View.INVISIBLE);
                kick();
            }
            @Override public void onNudge(int dir) { VideoPage v = currentVideo(); if (v != null) { v.seekBy(dir * PlayerMath.SKIP_MS); syncControls(); } kick(); }
        });
        scrubWrap.addView(bubble, new FrameLayout.LayoutParams(dp(72), dp(28), Gravity.TOP | Gravity.START));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM);
        scrubWrap.addView(scrub, sl);
        controls.addView(scrubWrap, new LinearLayout.LayoutParams(-1, dp(68)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        timeText = Ui.num(this, "0:00 / 0:00", 12, 0xE6FFFFFF);
        timeText.setPadding(dp(12), 0, 0, 0);
        row.addView(timeText, new LinearLayout.LayoutParams(0, -2, 1));
        mute = button(R.drawable.ic_speaker, "Mute", v -> { toggleMute(); showVolPop(); });
        speedBtn = Ui.num(this, PlayerMath.speedLabel(1f), 12, Color.WHITE);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setMinWidth(dp(56)); speedBtn.setMinHeight(dp(48));
        speedBtn.setPadding(dp(8), 0, dp(8), 0);
        speedBtn.setBackground(new android.graphics.drawable.InsetDrawable(Ui.round(this, 0x2EFFFFFF, 14), dp(4), dp(8), dp(4), dp(8)));
        speedBtn.setContentDescription("Playback speed 1x");
        speedBtn.setClickable(true); speedBtn.setFocusable(true);
        Ui.press(speedBtn);
        speedBtn.setOnClickListener(v -> {
            VideoPage p = currentVideo();
            if (p != null) { p.setSpeed(PlayerMath.nextSpeed(p.speed())); syncControls(); }
            kick();
        });
        fit = button(R.drawable.ic_aspect, "Fill screen", v -> { VideoPage p = currentVideo(); if (p != null) p.toggleZoom(p.getWidth() / 2f, p.getHeight() / 2f); });
        full = button(R.drawable.ic_fullscreen, "Enter fullscreen", v -> toggleOrientation());
        row.addView(mute, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(speedBtn, new LinearLayout.LayoutParams(-2, dp(48)));
        row.addView(fit, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(full, new LinearLayout.LayoutParams(dp(48), dp(48)));
        controls.addView(row, new LinearLayout.LayoutParams(-1, -2));
        bottom.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        updateOrientationLabel();
    }

    /** Pop-out volume slider, the gesture level HUD, the 2x chip and the error card; all float above the pager. */
    private void buildOverlays() {
        volPop = new LevelView(this, true);
        volPop.setContentDescription("Volume");
        volPop.setVisibility(View.GONE);
        volPop.setListener(new LevelView.Listener() {
            @Override public void onLevel(float level) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, PlayerMath.levelToStep(level, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
                preMuteVolume = -1; syncControls();
            }
            @Override public void onTouch(boolean down) {
                volTouch = down; root.removeCallbacks(hideVol); root.removeCallbacks(hide);
                if (!down) { root.postDelayed(hideVol, 2000); kick(); }
            }
        });
        root.addView(volPop, new FrameLayout.LayoutParams(dp(48), dp(150), Gravity.TOP | Gravity.START));

        levelHud = new LinearLayout(this);
        levelHud.setOrientation(LinearLayout.VERTICAL);
        levelHud.setGravity(Gravity.CENTER_HORIZONTAL);
        levelIcon = new ImageView(this);
        levelIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        levelBar = new LevelView(this, false);
        levelHud.addView(levelIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(48), dp(140));
        bl.topMargin = dp(8);
        levelHud.addView(levelBar, bl);
        levelHud.setVisibility(View.GONE);
        levelHud.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(levelHud, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END));

        holdChip = Ui.num(this, PlayerMath.speedLabel(PlayerMath.HOLD_SPEED), 16, Color.WHITE);
        holdChip.setGravity(Gravity.CENTER);
        holdChip.setBackground(Ui.round(this, 0xB3000000, 16));
        holdChip.setPadding(dp(16), dp(6), dp(16), dp(6));
        holdChip.setVisibility(View.GONE);
        root.addView(holdChip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView msg = Ui.title(this, "Can't play this video", Color.WHITE);
        TextView sub = Ui.caption(this, "Try again, or open it in another app.", 0xB3FFFFFF);
        sub.setPadding(0, dp(4), 0, dp(16));
        LinearLayout acts = new LinearLayout(this);
        acts.setGravity(Gravity.CENTER);
        TextView retry = Ui.primaryButton(this, "Retry");
        retry.setMinHeight(dp(48));
        retry.setOnClickListener(v -> { VideoPage p = currentVideo(); if (p != null) p.retry(); });
        TextView open = Ui.ghostButton(this, "Open with", Color.WHITE);
        open.setMinHeight(dp(48));
        open.setOnClickListener(v -> act(2));
        acts.addView(retry); acts.addView(open);
        errorBox.addView(msg); errorBox.addView(sub); errorBox.addView(acts);
        errorBox.setVisibility(View.GONE);
        root.addView(errorBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }

    // ── filmstrip ────────────────────────────────────────────────────────────────────────

    private void buildStrip() {
        strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.setContentDescription("Gallery thumbnails");
        stripRow = new LinearLayout(this);
        stripRow.setPadding(dp(8), 0, dp(8), dp(8));
        strip.addView(stripRow, new FrameLayout.LayoutParams(-2, -2));
        if (items.size() < 2) { strip.setVisibility(View.GONE); bottom.addView(strip, new LinearLayout.LayoutParams(-1, 0)); return; }
        for (int i = 0; i < items.size(); i++) {
            final int idx = i; final MediaItem it = items.get(i);
            FrameLayout cell = new FrameLayout(this);
            cell.setContentDescription((it.video ? "Video " : "Photo ") + (i + 1) + " of " + items.size());
            cell.setClickable(true); cell.setFocusable(true);
            cell.setOnClickListener(v -> { if (idx != pager.current()) pager.setCurrent(idx); kick(); });
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF1C1F24);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(6)); }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            cell.addView(iv, lp);
            if (it.video) {
                ImageView p = new ImageView(this); p.setImageResource(R.drawable.ic_play);
                p.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                cell.addView(p, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(52), dp(60));
            cl.rightMargin = dp(2);
            stripRow.addView(cell, cl);
            cell.setAlpha(.6f); cell.setScaleX(.92f); cell.setScaleY(.92f);
            thumbs.submit(() -> {
                Bitmap t = thumb(it);
                runOnUiThread(() -> { if (!destroyed && t != null) iv.setImageBitmap(t); });
            });
        }
        bottom.addView(strip, new LinearLayout.LayoutParams(-1, -2));
    }

    private Bitmap thumb(MediaItem it) {
        if (!it.video) return bitmap(it, 160);
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(it.file.getPath());
            Bitmap f = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (f == null) return null;
            int s = Math.max(f.getWidth(), f.getHeight());
            return s > 160 ? Bitmap.createScaledBitmap(f, Math.max(1, f.getWidth() * 160 / s), Math.max(1, f.getHeight() * 160 / s), true) : f;
        } catch (Exception e) { return null; }
        finally { try { r.release(); } catch (Exception ignored) { } }
    }

    /** Outlines the current thumbnail and slides the strip so it sits in the middle. */
    private void selectThumb(int index) {
        if (stripRow == null || stripRow.getChildCount() <= index || index < 0) return;
        for (int i : new int[]{stripSel, index}) {
            if (i < 0 || i >= stripRow.getChildCount()) continue;
            View cell = stripRow.getChildAt(i); boolean on = i == index;
            cell.setBackground(on ? Ui.stroke(this, Color.TRANSPARENT, Color.WHITE, 9) : null);
            if (Ui.motionEnabled()) cell.animate().alpha(on ? 1f : .6f).scaleX(on ? 1f : .92f).scaleY(on ? 1f : .92f).setDuration(180).setInterpolator(Ui.EASE_OUT).start();
            else { cell.setAlpha(on ? 1f : .6f); cell.setScaleX(on ? 1f : .92f); cell.setScaleY(on ? 1f : .92f); }
        }
        stripSel = index;
        final View cell = stripRow.getChildAt(index);
        strip.post(() -> {
            int x = cell.getLeft() + cell.getWidth() / 2 - strip.getWidth() / 2;
            if (Ui.motionEnabled()) strip.smoothScrollTo(Math.max(0, x), 0); else strip.scrollTo(Math.max(0, x), 0);
        });
    }

    private void fitBars(WindowInsets ins) {
        int t, b, l, r;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            t = i.top; b = i.bottom; l = i.left; r = i.right;
        } else {
            t = ins.getSystemWindowInsetTop(); b = ins.getSystemWindowInsetBottom(); l = ins.getSystemWindowInsetLeft(); r = ins.getSystemWindowInsetRight();
        }
        topInset = t;
        top.setPadding(dp(4) + l, dp(4) + t, dp(4) + r, dp(28));
        bottom.setPadding(dp(8) + l, dp(32), dp(8) + r, dp(8) + b);
        FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) holdChip.getLayoutParams();
        hp.topMargin = t + dp(24);
        holdChip.setLayoutParams(hp);
    }

    private boolean landscape() { return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; }

    /** Landscape is true fullscreen: system bars stay hidden, the caption and filmstrip give way, the transport spreads out. */
    private void relayout() {
        boolean land = landscape();
        MediaItem it = items.get(pager.current());
        caption.setVisibility(land || it.video || it.caption.isEmpty() ? View.GONE : View.VISIBLE);
        strip.setVisibility(land || it.video || items.size() < 2 ? View.GONE : View.VISIBLE);
        LinearLayout row = (LinearLayout) play.getParent();
        for (View v : new View[]{play}) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = lp.rightMargin = dp(land ? 48 : 24);
            v.setLayoutParams(lp);
        }
        row.requestLayout();
    }

    /** Bars hide with the chrome, and always in landscape; layout stays edge to edge so nothing jumps. */
    @SuppressWarnings("deprecation")
    private void applyBars() {
        int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!chromeVisible || landscape()) f |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyBars(); updateOrientationLabel(); relayout();
        root.requestApplyInsets();
    }

    /** Back leaves fullscreen first, then the viewer. */
    @Override public void onBackPressed() {
        if (landscape()) { toggleOrientation(); return; }
        super.onBackPressed();
    }

    private void toggleOrientation() {
        setRequestedOrientation(landscape() ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void updateOrientationLabel() { Ui.swapIcon(full, landscape() ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen, landscape() ? "Exit fullscreen" : "Enter fullscreen"); }

    private boolean autoHides() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        VideoPage v = currentVideo();
        return v != null && v.playing() && !seeking && !volTouch && !(am != null && am.isTouchExplorationEnabled());
    }

    /** Any interaction with the controls restarts the 3 s countdown. */
    private void kick() {
        root.removeCallbacks(hide);
        if (chromeVisible && autoHides()) root.postDelayed(hide, HIDE_MS);
    }

    /** One fade for everything that belongs to the chrome: 200 ms ease-out, then gone so it can't catch touches. */
    private void fade(View v, boolean show) {
        v.animate().cancel();
        v.setTag(show);
        if (show) {
            v.setVisibility(View.VISIBLE);
            if (Ui.motionEnabled()) v.animate().alpha(1).setDuration(200).setInterpolator(Ui.EASE_OUT).start(); else v.setAlpha(1);
        } else if (Ui.motionEnabled()) {
            v.animate().alpha(0).setDuration(200).setInterpolator(Ui.EASE_OUT).withEndAction(() -> { if (Boolean.FALSE.equals(v.getTag())) v.setVisibility(View.GONE); }).start();
        } else v.setVisibility(View.GONE);
    }

    private void setChrome(boolean show) {
        if (show == chromeVisible) return;
        chromeVisible = show;
        fade(top, show); fade(bottom, show);
        if (!show) { root.removeCallbacks(hideVol); fade(volPop, false); }
        updateCenter();
        applyBars();
        kick();
    }

    /** The transport in the middle shows with the chrome, for videos that haven't failed. */
    private void updateCenter() {
        VideoPage v = currentVideo();
        boolean want = chromeVisible && onVideo && v != null && !v.failed();
        if (want == centerShown) return;
        centerShown = want;
        fade(center, want);
    }

    // ── MediaPager.Host ──────────────────────────────────────────────────────────────────

    @Override public int count() { return items.size(); }

    @Override public View createPage(int index) {
        final MediaItem it = items.get(index);
        if (it.video) return new VideoPage(this, it, this);
        final ZoomImageView v = new ZoomImageView(this);
        loader.submit(() -> {
            Bitmap bmp = bitmap(it, 1600);
            runOnUiThread(() -> {
                if (destroyed) return;
                if (bmp != null) v.setBitmap(bmp);
                else if (items.get(pager.current()) == it) Toast.makeText(this, "Couldn't load this image", Toast.LENGTH_SHORT).show();
            });
        });
        return v;
    }

    private Bitmap bitmap(MediaItem it, int max) {
        try {
            if (it.file != null) {
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(it.file.getPath(), o);
                int s = 1; while (o.outWidth / s > max || o.outHeight / s > max) s *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = s;
                return BitmapFactory.decodeFile(it.file.getPath(), d);
            }
            Bitmap c = max <= 1600 ? MarkdownView.cached(it.url) : null;
            return c != null ? c : MarkdownView.load(it.url, fetcher, max);
        } catch (Throwable t) { return null; }
    }

    @Override public void onPage(int index) {
        MediaItem it = items.get(index);
        onVideo = it.video;
        title.setText(it.name);
        counter.setText(MediaMath.counter(index, items.size()));
        counter.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
        caption.setText(it.caption);
        controls.setVisibility(it.video ? View.VISIBLE : View.GONE);
        relayout();
        selectThumb(index);
        lastSec = -1;
        String what = (it.video ? "Video " : "Photo ") + (index + 1) + " of " + items.size() + ", " + it.name;
        pager.setContentDescription(what);
        pager.announceForAccessibility(what);
        View pv = pager.pageView(index);
        if (pv != null) pv.setContentDescription(what);
        if (!it.video && sharpened.add(index)) {   // sharper decode for zooming, off-thread
            loader.submit(() -> {
                Bitmap big = bitmap(it, 3072);
                runOnUiThread(() -> {
                    View v = pager.pageView(index);
                    if (!destroyed && big != null && v instanceof ZoomImageView) {
                        Bitmap now = ((ZoomImageView) v).bitmap();
                        if (now == null || big.getWidth() > now.getWidth()) ((ZoomImageView) v).setBitmap(big);
                    }
                });
            });
        }
        if (!chromeVisible) setChrome(true);
        else updateCenter();
        syncControls();
        kick();
    }

    @Override public void onTap(float x, float y) {
        VideoPage v = currentVideo();
        int z = PlayerMath.zone(x, pager.getWidth());
        // after a double-tap skip, further taps in the same third keep skipping (YouTube style)
        if (v != null && !v.zoomed() && z != PlayerMath.CENTER && z == skipZone && SystemClock.uptimeMillis() < streakUntil) { skip(z, x, y); return; }
        setChrome(!chromeVisible);
    }

    @Override public boolean onDoubleTap(float x, float y) {
        VideoPage v = currentVideo();
        int z = PlayerMath.zone(x, pager.getWidth());
        if (v == null || v.zoomed() || z == PlayerMath.CENTER) return false;
        skip(z, x, y);
        return true;
    }

    /** Skips 10 s towards {@code zone}; negative x/y (the on-screen buttons) puts the bubble mid-third. */
    private void skip(int zone, float x, float y) {
        VideoPage v = currentVideo();
        if (v == null) return;
        long now = SystemClock.uptimeMillis();
        int delta = zone * PlayerMath.SKIP_MS;
        skipAcc = zone == skipZone && now < streakUntil ? skipAcc + delta : delta;
        skipZone = zone; streakUntil = now + 700;
        v.seekBy(delta);
        float w = pager.getWidth(), h = pager.getHeight();
        hud.skip(zone, PlayerMath.skipLabel(skipAcc), x >= 0 ? x : zone < 0 ? w / 6f : w * 5f / 6f, y >= 0 ? y : h / 2f);
        syncControls();
        kick();
    }

    @Override public int dragKind(float x, float width) {
        VideoPage v = currentVideo();
        if (v == null || v.zoomed()) return MediaPager.DRAG_DISMISS;
        int z = PlayerMath.zone(x, width);
        return z == PlayerMath.RIGHT ? MediaPager.DRAG_VOLUME : z == PlayerMath.LEFT ? MediaPager.DRAG_BRIGHTNESS : MediaPager.DRAG_DISMISS;
    }

    @Override public void onLevelDrag(int kind, float dy, float height) {
        root.removeCallbacks(hideLevel); root.removeCallbacks(hide);
        boolean vol = kind == MediaPager.DRAG_VOLUME;
        if (!levelDragging) { levelDragging = true; volLevel = currentVolume(); brightLevel = currentBrightness(); }
        if (vol) {
            volLevel = PlayerMath.levelAfterDrag(volLevel, dy, height);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, PlayerMath.levelToStep(volLevel, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
            preMuteVolume = -1;
        } else {
            brightLevel = PlayerMath.levelAfterDrag(brightLevel, dy, height);
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = Math.max(.01f, brightLevel);
            getWindow().setAttributes(lp);
            brightnessSet = true;
        }
        float shown = vol ? volLevel : brightLevel;
        levelIcon.setImageResource(vol ? (shown <= 0 ? R.drawable.ic_speaker_off : R.drawable.ic_speaker) : R.drawable.ic_brightness);
        levelBar.setLevel(shown);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) levelHud.getLayoutParams();
        int g = Gravity.CENTER_VERTICAL | (vol ? Gravity.END : Gravity.START);
        if (lp.gravity != g) { lp.gravity = g; lp.leftMargin = lp.rightMargin = dp(28); levelHud.setLayoutParams(lp); }
        if (levelHud.getVisibility() != View.VISIBLE || !Boolean.TRUE.equals(levelHud.getTag())) fade(levelHud, true);
        levelHud.setContentDescription((vol ? "Volume " : "Brightness ") + Math.round(shown * 100) + " percent");
    }

    @Override public void onLevelEnd() {
        levelDragging = false;
        root.postDelayed(hideLevel, 600);
        kick();
    }

    @Override public boolean onHold(boolean on) {
        VideoPage v = currentVideo();
        if (on && (v == null || v.zoomed() || !v.playing())) return false;
        if (v != null) v.hold(on);
        if (on) { root.removeCallbacks(hide); fade(holdChip, true); }
        else { fade(holdChip, false); kick(); }
        return true;
    }

    private float currentVolume() { return PlayerMath.stepToLevel(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)); }

    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try { return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f; }
        catch (Exception e) { return .5f; }
    }

    @Override public void onDismissDrag(float p) {
        bg.setAlpha(1 - p);
        float a = 1 - MediaMath.clamp(p * 3, 0, 1);
        if (chromeVisible) { top.setAlpha(a); bottom.setAlpha(a); }
        if (centerShown) center.setAlpha(a);
    }

    @Override public void onDismiss() { finish(); overridePendingTransition(0, 0); }

    // ── video ────────────────────────────────────────────────────────────────────────────

    private VideoPage currentVideo() {
        if (pager == null) return null;
        View v = pager.pageView(pager.current());
        return v instanceof VideoPage ? (VideoPage) v : null;
    }

    @Override public void onChanged(VideoPage p) {
        if (currentVideo() != p) return;
        boolean playing = p.playing();
        root.setKeepScreenOn(playing);
        focus(playing);
        updateCenter();
        syncControls();
        kick();
    }

    private void syncControls() {
        long now = SystemClock.uptimeMillis();
        if (now - lastVolPoll >= 250) {   // the stream volume is a binder call; poll it at 4 Hz, not per frame
            lastVolPoll = now;
            int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (!volTouch) volPop.setLevel(PlayerMath.stepToLevel(level, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
            Ui.swapIcon(mute, level == 0 ? R.drawable.ic_speaker_off : R.drawable.ic_speaker, level == 0 ? "Unmute" : "Mute");
        }
        VideoPage v = currentVideo();
        if (v == null) { spinner.setVisibility(View.GONE); errorBox.setVisibility(View.GONE); return; }
        boolean loading = v.loading(), failed = v.failed();
        play.setPlaying(v.playing());
        play.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        spinner.setVisibility(loading && !failed ? View.VISIBLE : View.GONE);
        errorBox.setVisibility(failed ? View.VISIBLE : View.GONE);
        scrub.setEnabled(!failed);
        int dur = Math.max(0, v.duration()), pos = v.position();
        scrub.set(pos, dur, v.buffered());
        if (!seeking && (pos / 1000 != lastSec || dur != lastDur)) {
            lastSec = pos / 1000; lastDur = dur;
            timeText.setText(PlayerMath.timeLabel(pos, dur));
        }
        String sp = PlayerMath.speedLabel(v.speed());
        if (!sp.contentEquals(speedBtn.getText())) { speedBtn.setText(sp); speedBtn.setContentDescription("Playback speed " + sp.replace("×", "x")); }
        boolean filled = v.filled();
        fit.setImageTintList(ColorStateList.valueOf(filled ? Ui.BLUE : Color.WHITE));
        fit.setContentDescription(filled ? "Fit to screen" : "Fill screen");
    }

    private void showVolPop() {
        root.removeCallbacks(hideVol);
        mute.getLocationInWindow(xy); root.getLocationInWindow(rootXy);
        volPop.setTranslationX(xy[0] - rootXy[0] + mute.getWidth() / 2f - dp(24));
        volPop.setTranslationY(xy[1] - rootXy[1] - dp(150) - dp(4));
        volPop.setLevel(currentVolume());
        fade(volPop, true);
        root.postDelayed(hideVol, 2500);
    }

    private void toggleMute() {
        int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int to = level > 0 ? 0 : (preMuteVolume > 0 ? preMuteVolume : Math.max(1, max / 2));
        preMuteVolume = level > 0 ? level : -1;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, to, 0);
        lastVolPoll = 0;
        syncControls();
    }

    private void focus(boolean want) {
        if (want && focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(c -> {
                        if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { VideoPage v = currentVideo(); if (v != null) v.pause(); }
                    }).build();
            audio.requestAudioFocus(focus);
        } else if (!want && focus != null) {
            audio.abandonAudioFocusRequest(focus); focus = null;
        }
    }

    // ── save / share / open with ─────────────────────────────────────────────────────────

    private void act(int which) {
        final MediaItem it = items.get(pager.current());
        if (it.file != null) { run(which, it); return; }
        loader.submit(() -> {
            boolean ok = materialize(it);
            runOnUiThread(() -> { if (destroyed) return; if (ok) run(which, it); else Toast.makeText(this, "Couldn't get the original file", Toast.LENGTH_SHORT).show(); });
        });
    }

    private void run(int which, MediaItem it) {
        if (which == 0) FileViewerActivity.save(this, it.file, it.name);
        else if (which == 1) FileViewerActivity.share(this, it.file, it.name);
        else FileViewerActivity.openWith(this, it.file, it.name);
    }

    /** Writes the original bytes of a chat image into cacheDir/media so the share/save helpers can use a file. */
    private boolean materialize(MediaItem it) {
        try {
            byte[] bytes; String ext;
            if (it.url.startsWith("data:")) {
                int comma = it.url.indexOf(',');
                bytes = Base64.decode(it.url.substring(comma + 1), Base64.DEFAULT);
                String mime = it.url.substring(5, Math.max(5, it.url.indexOf(';') > 0 ? it.url.indexOf(';') : comma));
                ext = mime.endsWith("jpeg") ? "jpg" : mime.contains("/") ? mime.substring(mime.indexOf('/') + 1) : "png";
            } else if (fetcher != null) {
                bytes = fetcher.fetch(it.url);
                ext = MarkdownView.ext(it.url);
                if (ext.isEmpty()) ext = "png";
            } else return false;
            File dir = new File(getCacheDir(), "media"); dir.mkdirs();
            File f = new File(dir, "img-" + Integer.toHexString(it.url.hashCode()) + "." + ext);
            try (OutputStream o = new FileOutputStream(f)) { o.write(bytes); }
            it.file = f;
            return true;
        } catch (Throwable t) { return false; }
    }

    // ── open animation ───────────────────────────────────────────────────────────────────

    /** Grows from the tapped thumbnail's bounds (scale + fade); skipped with animations off or no known origin. */
    private void animateOpen(final int[] from) {
        if (from == null || !Ui.motionEnabled()) return;
        bg.setAlpha(0); top.setAlpha(0); bottom.setAlpha(0); center.setAlpha(0);
        pager.setAlpha(0);
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] loc = new int[2]; pager.getLocationOnScreen(loc);
                float w = Math.max(1, pager.getWidth()), s = Math.min(1f, from[2] / w);
                pager.setScaleX(s); pager.setScaleY(s);
                pager.setTranslationX(from[0] + from[2] / 2f - (loc[0] + w / 2f));
                pager.setTranslationY(from[1] + from[3] / 2f - (loc[1] + pager.getHeight() / 2f));
                pager.setAlpha(.2f);
                pager.animate().scaleX(1).scaleY(1).translationX(0).translationY(0).alpha(1).setDuration(280).setInterpolator(Ui.EASE_OUT).start();
                for (View v : new View[]{bg, top, bottom, center}) v.animate().alpha(1).setDuration(280).setInterpolator(Ui.EASE_OUT).start();
                return true;
            }
        });
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────

    @Override protected void onPause() {
        super.onPause();
        VideoPage v = currentVideo(); if (v != null) v.pause();
        focus(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (root != null) root.removeCallbacks(tick);
        if (root != null) { root.removeCallbacks(hide); root.removeCallbacks(hideVol); root.removeCallbacks(hideLevel); }
        if (brightnessSet) { android.view.WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; getWindow().setAttributes(lp); }
        thumbs.shutdownNow();
        if (pager != null) pager.release();
        loader.shutdownNow();
        if (audio != null) focus(false);
        if (isFinishing()) { handoff = null; handoffFetcher = null; }
    }

}
