package cloud.stormlabs.hermescompanion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.OverScroller;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One video in the gallery: a poster frame, then a MediaPlayer on a TextureView while this page is the active one.
 *  Pinch / double tap zooms (fit vs fill); the controls live in {@link MediaViewerActivity}. */
final class VideoPage extends FrameLayout implements MediaPager.Page {
    interface Listener { void onChanged(VideoPage page); }

    private static final ExecutorService POSTERS = Executors.newSingleThreadExecutor();

    final MediaItem item;
    private final Listener listener;
    private final FrameLayout stage;
    private final ImageView poster;
    private final TextureView texture;
    private final MediaMath.Zoom z = new MediaMath.Zoom();
    private final OverScroller scroller;
    private ValueAnimator anim;
    private MediaPlayer mp;
    private Surface surface;
    private boolean prepared, wantPlay, active, ended, error, recycled;
    private int vidW, vidH, durationMs, bufferedPct;
    private boolean buffering;
    private float speed = 1f;
    private boolean holding;
    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (!scroller.computeScrollOffset()) return;
            z.tx = scroller.getCurrX(); z.ty = scroller.getCurrY();
            z.clampOffsets(); apply(); postOnAnimation(this);
        }
    };

    VideoPage(Context c, MediaItem item, Listener listener) {
        super(c);
        this.item = item; this.listener = listener;
        scroller = new OverScroller(c);
        stage = new FrameLayout(c);
        poster = new ImageView(c); poster.setScaleType(ImageView.ScaleType.FIT_XY);
        texture = new TextureView(c);
        stage.addView(poster, new LayoutParams(-1, -1));
        stage.addView(texture, new LayoutParams(-1, -1));
        addView(stage, new LayoutParams(-1, -1));
        stage.setPivotX(0); stage.setPivotY(0);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { surface = new Surface(st); if (mp != null) mp.setSurface(surface); startIfWanted(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { if (mp != null) mp.setSurface(null); if (surface != null) surface.release(); surface = null; return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        loadPoster();
    }

    private void loadPoster() {
        POSTERS.submit(() -> {
            Bitmap frame = null; int w = 0, h = 0, rot = 0, dur = 0;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(item.file.getPath());
                w = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                h = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                rot = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                dur = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                Bitmap f = r.getFrameAtTime(item.resumeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (f != null) {
                    int max = 1280, s = Math.max(f.getWidth(), f.getHeight());
                    frame = s > max ? Bitmap.createScaledBitmap(f, Math.max(1, f.getWidth() * max / s), Math.max(1, f.getHeight() * max / s), true) : f;
                }
            } catch (Exception ignored) { } finally { try { r.release(); } catch (Exception ignored) { } }
            final Bitmap fb = frame; final int fw = rot == 90 || rot == 270 ? h : w, fh = rot == 90 || rot == 270 ? w : h, fd = dur;
            post(() -> {
                if (recycled) return;
                if (vidW == 0) { vidW = fw; vidH = fh; }
                if (durationMs == 0) durationMs = fd;
                if (fb != null && poster.getVisibility() == VISIBLE) poster.setImageBitmap(fb);
                refit(); changed();
            });
        });
    }

    private static int num(String s) { try { return s == null ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }

    private void startIfWanted() {
        if (!active || surface == null || mp != null || item.file == null || recycled) return;
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p; prepared = false; ended = false; error = false;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            p.setDataSource(item.file.getPath());
            p.setSurface(surface);
            p.setOnPreparedListener(m -> {
                prepared = true; durationMs = m.getDuration();
                if (speed != 1f || holding) applySpeed(m);
                if (item.resumeMs > 0) m.seekTo(item.resumeMs, MediaPlayer.SEEK_CLOSEST);
                if (wantPlay) m.start();
                changed();
            });
            p.setOnVideoSizeChangedListener((m, w, h) -> { if (w > 0 && h > 0) { vidW = w; vidH = h; refit(); } });
            p.setOnInfoListener((m, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) poster.setVisibility(GONE);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) { buffering = true; changed(); }
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) { buffering = false; changed(); }
                return false;
            });
            p.setOnBufferingUpdateListener((m, pct) -> bufferedPct = pct);
            p.setOnCompletionListener(m -> { ended = true; item.resumeMs = 0; changed(); });
            p.setOnErrorListener((m, what, extra) -> { error = true; changed(); return true; });
            p.prepareAsync();
        } catch (Exception e) { error = true; releasePlayer(); changed(); }
    }

    private void releasePlayer() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
        prepared = false; buffering = false; bufferedPct = 0;
    }

    private void changed() { if (!recycled) listener.onChanged(this); }

    // ── controls API ─────────────────────────────────────────────────────────────────────

    boolean playing() { return mp != null && prepared && mp.isPlaying(); }
    boolean failed() { return error; }
    int duration() { return prepared && mp != null ? mp.getDuration() : durationMs; }
    int position() { return prepared && mp != null ? mp.getCurrentPosition() : item.resumeMs; }

    /** True while the clip is loading or stalled and no error has happened (drives the spinner). */
    boolean loading() { return active && !error && !recycled && (!prepared || buffering); }
    float speed() { return speed; }
    boolean filled() { return z.zoomed(); }
    /** Buffered position in ms (the whole clip once it's a local file). */
    int buffered() { int d = duration(); return prepared ? Math.round(d * MediaMath.clamp(bufferedPct, 0, 100) / 100f) : 0; }

    /** Sets the speed the player shows in its chip; a long-press hold overrides it with 2x until released. */
    void setSpeed(float s) { speed = s; if (mp != null && prepared) applySpeed(mp); changed(); }

    void hold(boolean on) { holding = on; if (mp != null && prepared) applySpeed(mp); }

    private void applySpeed(MediaPlayer m) {
        float s = holding ? PlayerMath.HOLD_SPEED : speed;
        try {
            boolean was = m.isPlaying();
            m.setPlaybackParams(new PlaybackParams().setSpeed(s));
            if (!was && m.isPlaying()) m.pause();   // some releases start playing when the params change
        } catch (Exception ignored) { }
    }

    /** Throws the player away and tries again (after an error). */
    void retry() {
        releasePlayer(); error = false; wantPlay = true;
        startIfWanted(); changed();
    }

    /** Skips by {@code deltaMs}, clamped to the clip; returns the new position. */
    int seekBy(int deltaMs) {
        int to = PlayerMath.seekTarget(position(), deltaMs, duration());
        seekTo(to, false);
        return to;
    }

    void toggle() {
        if (error || mp == null || !prepared) { wantPlay = !wantPlay; return; }
        if (mp.isPlaying()) pause();
        else {
            if (ended) { mp.seekTo(0, MediaPlayer.SEEK_CLOSEST); ended = false; }
            wantPlay = true; mp.start(); changed();
        }
    }

    void pause() {
        wantPlay = false;
        if (mp != null && prepared && mp.isPlaying()) mp.pause();
        changed();
    }

    /** {@code fast} snaps to the nearest keyframe (live scrubbing); otherwise the exact frame. */
    void seekTo(int ms, boolean fast) {
        item.resumeMs = ms;
        if (mp != null && prepared) { ended = false; mp.seekTo(ms, fast ? MediaPlayer.SEEK_CLOSEST_SYNC : MediaPlayer.SEEK_CLOSEST); }
    }

    // ── pager page ───────────────────────────────────────────────────────────────────────

    @Override public void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (on) { wantPlay = true; startIfWanted(); }
        else {
            stop();
            if (mp != null) {
                if (prepared && !ended) try { item.resumeMs = mp.getCurrentPosition(); } catch (Exception ignored) { }
                releasePlayer();
            }
            wantPlay = false; holding = false;
            poster.setVisibility(VISIBLE);
            if (z.zoomed()) refit();
        }
        changed();
    }

    @Override public void recycle() { recycled = true; stop(); releasePlayer(); }

    @Override public boolean zoomed() { return z.zoomed(); }

    @Override public void zoomBy(float f, float fx, float fy) { stop(); z.zoomAt(z.zoom * f, fx, fy); apply(); }

    /** Double tap: fit <-> fill. */
    @Override public void toggleZoom(float x, float y) {
        stop();
        final float to = z.toggleTarget(z.fillZoom()), from = z.zoom;
        if (!Ui.motionEnabled()) { z.zoomAt(to, x, y); apply(); return; }
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(260); anim.setInterpolator(Ui.EASE_OUT);
        anim.addUpdateListener(a -> { z.zoomAt((float) a.getAnimatedValue(), x, y); apply(); });
        anim.start();
    }

    @Override public float pan(float dx, float dy) { float left = z.pan(dx, dy); apply(); return left; }

    @Override public void fling(float vx, float vy) {
        stop();
        if (vx == 0 && vy == 0 || !z.zoomed()) return;
        int x = Math.round(z.tx), y = Math.round(z.ty);
        scroller.fling(x, y, Math.round(z.canPanX() ? vx : 0), Math.round(z.canPanY() ? vy : 0),
                z.canPanX() ? Math.round(z.minTx()) : x, z.canPanX() ? 0 : x, z.canPanY() ? Math.round(z.minTy()) : y, z.canPanY() ? 0 : y);
        postOnAnimation(step);
    }

    private void stop() {
        scroller.forceFinished(true); removeCallbacks(step);
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); refit(); }

    private void refit() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float[] s = vidW > 0 && vidH > 0 ? MediaMath.fit(vidW, vidH, w, h) : new float[]{w, h};
        z.setSizes(w, h, s[0], s[1]);
        stage.setLayoutParams(new LayoutParams(Math.round(s[0]), Math.round(s[1])));
        apply();
    }

    private void apply() {
        stage.setScaleX(z.zoom); stage.setScaleY(z.zoom);
        stage.setTranslationX(z.tx); stage.setTranslationY(z.ty);
    }
}
