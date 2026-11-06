package cloud.stormlabs.hermescompanion;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

/** Small custom views for the chat: Hermes presence mark, composer sweep, aspect-correct image. */
final class ChatViews {
    private ChatViews() {}

    /** Hermes orbit mark; while replying it turns once every 4 s (linear), phase-locked to the clock so rebuilds do not restart it. */
    static View mark(Context c, int size, boolean replying) {
        View v = Ui.hermesLogo(c, size);
        v.setContentDescription(ChatLogic.presence(replying));
        if (replying && Ui.motionEnabled()) {
            // A custom logo (a photo) pulses instead of spinning.
            ObjectAnimator a = v instanceof ImageView ? ObjectAnimator.ofFloat(v, View.ALPHA, 1f, .45f, 1f) : ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f);
            a.setDuration(4000); a.setInterpolator(new LinearInterpolator()); a.setRepeatCount(ValueAnimator.INFINITE);
            a.setCurrentPlayTime(android.os.SystemClock.uptimeMillis() % 4000);
            v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View x) { if (!a.isStarted()) a.start(); }
                public void onViewDetachedFromWindow(View x) { a.cancel(); }
            });
        }
        return v;
    }

    static View mark(Context c, int size, boolean replying, String agent) {
        if ("Hermes".equals(agent)) return mark(c, size, replying);
        android.widget.TextView v = Ui.text(c, "Codex".equals(agent) ? ">_" : "✳", size / 2, Ui.BLUE, true);
        v.setGravity(android.view.Gravity.CENTER);
        v.setContentDescription(agent + (replying ? " is replying" : ""));
        return v;
    }

    static View systemNote(Context c, String content) {
        android.widget.TextView v = Ui.caption(c, content, Ui.SUB);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
        v.setSingleLine(true); v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setOnClickListener(view -> { boolean expand = v.getMaxLines() == 1; v.setSingleLine(!expand); v.setEllipsize(expand ? null : android.text.TextUtils.TruncateAt.END); });
        return v;
    }

    /** 2dp accent sweep along an edge: runs only while active (1.4 s, repeating, edge to edge). */
    static final class Sweep extends View {
        private ObjectAnimator anim;
        private boolean active;
        Sweep(Context c) {
            super(c);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.BLUE & 0xffffff, Ui.BLUE, Ui.BLUE & 0xffffff});
            setBackground(g);
            setVisibility(GONE);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setActive(boolean on) {
            if (on == active) return;
            active = on;
            if (!on) { if (anim != null) anim.cancel(); anim = null; setVisibility(GONE); return; }
            setVisibility(VISIBLE);
            if (!Ui.motionEnabled()) { setTranslationX(0); return; }
            restart();
        }
        private void restart() {
            if (anim != null) anim.cancel();
            // Travel the whole bar: from fully off the left edge to fully off the right (it used to stop at 40%).
            View parent = getParent() instanceof View ? (View) getParent() : null;
            float pw = parent != null && parent.getWidth() > 0 ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels;
            float w = getLayoutParams() != null && getLayoutParams().width > 0 ? getLayoutParams().width : pw * .4f;
            anim = ObjectAnimator.ofFloat(this, TRANSLATION_X, -w, pw);
            anim.setDuration(1400); anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()); anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.start();
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (active && Ui.motionEnabled()) restart(); }
        @Override protected void onDetachedFromWindow() { if (anim != null) anim.cancel(); super.onDetachedFromWindow(); }
    }

    /** Image that keeps its aspect ratio (max height 220dp, never upscaled), card-rounded, shimmering until the bitmap arrives. */
    static final class AspectImage extends ImageView {
        private float ratio = .75f; private int naturalW = Integer.MAX_VALUE;
        private ObjectAnimator shimmer;
        AspectImage(Context c) {
            super(c);
            setScaleType(ScaleType.FIT_XY);
            setClipToOutline(true);
            final float r = Ui.dp(c, 1) * Ui.concept.cardRadius;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            loading();
        }
        private void loading() {
            setBackground(Ui.round(getContext(), Ui.tier(2), 14));
            if (!Ui.motionEnabled()) return;
            shimmer = ObjectAnimator.ofFloat(this, ALPHA, 1f, .45f);
            shimmer.setDuration(800); shimmer.setRepeatCount(ValueAnimator.INFINITE); shimmer.setRepeatMode(ValueAnimator.REVERSE);
            shimmer.start();
        }
        private void stopShimmer() { if (shimmer != null) { shimmer.cancel(); shimmer = null; } setAlpha(1f); }
        void loaded(Bitmap b) {
            stopShimmer(); setBackground(null);
            ratio = ChatLogic.aspect(b.getWidth(), b.getHeight()); naturalW = b.getWidth();
            setImageBitmap(b); requestLayout();
            if (Ui.motionEnabled()) { setAlpha(0f); animate().alpha(1f).setDuration(200).start(); }
        }
        /** Sizes the placeholder to the image's aspect ratio once its bounds are known, so the list doesn't jump. */
        void expect(int w, int h) { if (getDrawable() == null && w > 0 && h > 0) { ratio = ChatLogic.aspect(w, h); requestLayout(); } }
        void failed() { stopShimmer(); setBackground(Ui.round(getContext(), Ui.tier(1), 14)); }
        @Override protected void onMeasure(int ws, int hs) {
            int avail = MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? Ui.dp(getContext(), 220) : MeasureSpec.getSize(ws);
            int[] wh = ChatLogic.fit(naturalW, avail, Ui.dp(getContext(), 220), ratio);
            setMeasuredDimension(MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY && getDrawable() == null ? avail : wh[0],
                    getDrawable() == null ? Math.min(Math.round(avail * ratio), Ui.dp(getContext(), 220)) : wh[1]);
        }
        @Override protected void onDetachedFromWindow() { if (shimmer != null) shimmer.cancel(); super.onDetachedFromWindow(); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (shimmer != null && !shimmer.isStarted()) shimmer.start(); }
    }
}
