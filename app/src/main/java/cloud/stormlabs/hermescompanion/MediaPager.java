package cloud.stormlabs.hermescompanion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.Scroller;

/** Horizontal pager for the media viewer (no AndroidX): follows the finger, snaps with a scroller, keeps three pages alive,
 *  and owns every gesture so zoomed pages can hand surplus panning over to paging. */
final class MediaPager extends FrameLayout {
    /** What a page must do for the pager's gestures; implemented by the image and video pages. */
    interface Page {
        boolean zoomed();
        void zoomBy(float factor, float fx, float fy);
        void toggleZoom(float x, float y);
        /** Pans by (dx, dy) and returns the horizontal part the page couldn't take. */
        float pan(float dx, float dy);
        void fling(float vx, float vy);
        void setActive(boolean active);
        void recycle();
    }

    interface Host {
        int count();
        View createPage(int index);
        void onTap(float x, float y);
        /** A double tap that the page's own zoom shouldn't handle (video skip zones); true when consumed. */
        boolean onDoubleTap(float x, float y);
        /** What a vertical drag starting at {@code x} does on the current page: DRAG_DISMISS, DRAG_VOLUME or DRAG_BRIGHTNESS. */
        int dragKind(float x, float width);
        /** Level drag: {@code dy} px since the last event (up is positive). */
        void onLevelDrag(int kind, float dy, float height);
        void onLevelEnd();
        /** Long press on the page; true to start a hold (called again with false on release). */
        boolean onHold(boolean on);
        void onPage(int index);
        void onDismissDrag(float progress);
        void onDismiss();
    }

    static final int DRAG_DISMISS = 0, DRAG_VOLUME = 1, DRAG_BRIGHTNESS = 2;
    private static final int NONE = 0, PAN = 1, HORIZ = 2, VERT = 3, ZOOM = 4, LEVEL = 5;

    private final Host host;
    private final SparseArray<View> pages = new SparseArray<>();
    private final Scroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private VelocityTracker velocity;
    private int current, mode, settleTo = -1, levelKind;
    private boolean holding;
    private float dragX, dragY, lastFocusX, lastFocusY;
    private ValueAnimator springBack;

    MediaPager(Context c, Host host) {
        super(c);
        this.host = host;
        scroller = new Scroller(c, Ui.EASE_OUT);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { MediaPager.this.host.onTap(e.getX(), e.getY()); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (MediaPager.this.host.onDoubleTap(e.getX(), e.getY())) return true;
                Page p = page(current); if (p != null) p.toggleZoom(e.getX(), e.getY()); return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (mode == NONE && !holding && settleTo < 0 && MediaPager.this.host.onHold(true)) { holding = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); }
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) { return scrolled(e1, e2, dx, dy); }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { lastFocusX = d.getFocusX(); lastFocusY = d.getFocusY(); return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                Page p = page(current);
                if (p == null) return false;
                p.zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                p.pan(d.getFocusX() - lastFocusX, d.getFocusY() - lastFocusY);   // two-finger drag pans too
                lastFocusX = d.getFocusX(); lastFocusY = d.getFocusY();
                return true;
            }
        });
        scaler.setQuickScaleEnabled(false);
        setClickable(true);
        setOnClickListener(v -> host.onTap(getWidth() / 2f, getHeight() / 2f));   // TalkBack's double tap
        setClipChildren(true);
    }

    int current() { return current; }

    Page page(int index) { View v = pages.get(index); return v instanceof Page ? (Page) v : null; }

    View pageView(int index) { return pages.get(index); }

    void setCurrent(int index) {
        scroller.forceFinished(true); settleTo = -1;
        Page old = page(current);
        if (old != null && index != current) old.setActive(false);
        current = MediaMath.clampIndex(index, host.count());
        dragX = 0; dragY = 0;
        rebuild();
        Page now = page(current);
        if (now != null) now.setActive(true);
        host.onPage(current);
    }

    /** Keeps only current-1..current+1 alive; neighbours are created (and so preloaded) ahead of the swipe. */
    private void rebuild() {
        for (int i = pages.size() - 1; i >= 0; i--) {
            int idx = pages.keyAt(i);
            if (Math.abs(idx - current) > 1) { discard(pages.valueAt(i)); pages.removeAt(i); }
        }
        for (int idx = current - 1; idx <= current + 1; idx++) {
            if (idx < 0 || idx >= host.count() || pages.get(idx) != null) continue;
            View v = host.createPage(idx);
            pages.put(idx, v);
            addView(v, new LayoutParams(-1, -1));
        }
        position();
    }

    private void discard(View v) { if (v instanceof Page) { ((Page) v).setActive(false); ((Page) v).recycle(); } removeView(v); }

    void release() { for (int i = 0; i < pages.size(); i++) if (pages.valueAt(i) instanceof Page) { ((Page) pages.valueAt(i)).setActive(false); ((Page) pages.valueAt(i)).recycle(); } }

    private float shownDrag() { return MediaMath.edgeResistance(dragX, current > 0, current < host.count() - 1); }

    private void position() {
        float w = getWidth(), d = shownDrag();
        boolean fx = Ui.motionEnabled();
        for (int i = 0; i < pages.size(); i++) {
            View v = pages.valueAt(i);
            float off = (pages.keyAt(i) - current) * w + d;
            v.setTranslationX(off);
            if (dragY == 0) { float s = fx ? PlayerMath.pageScale(off, w) : 1f; v.setScaleX(s); v.setScaleY(s); }   // neighbours shrink as they slide away
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); position(); }

    /** Animates to {@code target} (current ± 1 or current itself). */
    void settleTo(int target) {
        int t = MediaMath.clampIndex(target, host.count());
        float from = shownDrag(), to = -(t - current) * (float) getWidth();
        if (t != current) { Page old = page(current); if (old != null) old.setActive(false); }
        settleTo = t; dragX = from;
        if (!Ui.motionEnabled() || getWidth() == 0) { dragX = to; finishSettle(); return; }
        scroller.startScroll(Math.round(from), 0, Math.round(to - from), 0, 280);
        postInvalidateOnAnimation();
    }

    @Override public void computeScroll() {
        if (settleTo < 0) return;
        if (scroller.computeScrollOffset()) { dragX = scroller.getCurrX(); position(); postInvalidateOnAnimation(); }
        else { dragX = scroller.getFinalX(); finishSettle(); }
    }

    private void finishSettle() {
        int t = settleTo; settleTo = -1;
        boolean changed = t != current;
        current = t; dragX = 0;
        rebuild();
        Page now = page(current);
        if (now != null) now.setActive(true);
        if (changed) host.onPage(current);
    }

    private boolean scrolled(MotionEvent e1, MotionEvent e2, float dx, float dy) {
        if (e1 == null || e2.getPointerCount() > 1 || mode == ZOOM || settleTo >= 0) return false;
        Page p = page(current);
        if (mode == NONE) {
            if (p != null && p.zoomed()) mode = PAN;
            else mode = Math.abs(e2.getX() - e1.getX()) > Math.abs(e2.getY() - e1.getY()) ? HORIZ : VERT;
            if (holding) return true;   // a long-press hold owns the finger
            if (mode == VERT && (levelKind = host.dragKind(e1.getX(), getWidth())) != DRAG_DISMISS) mode = LEVEL;
            if (mode == HORIZ || mode == PAN) getParent().requestDisallowInterceptTouchEvent(true);
        }
        float mx = -dx, my = -dy;
        if (mode == PAN && p != null) {
            if (dragX != 0) {   // already pulling the pager from an edge: undo that before panning the page again
                float nd = dragX + mx;
                if (dragX * nd < 0) { mx = nd; dragX = 0; } else { dragX = nd; mx = 0; }
            }
            float left = p.pan(mx, my);
            if (left != 0) dragX += left;
            position();
        } else if (mode == HORIZ) { dragX += mx; position(); }
        else if (mode == VERT) { dragY += my; applyDismiss(); }
        else if (mode == LEVEL) host.onLevelDrag(levelKind, -my, getHeight());
        return true;
    }

    private void applyDismiss() {
        View v = pages.get(current);
        if (v == null) return;
        float prog = MediaMath.dismissProgress(dragY, getHeight());
        v.setTranslationY(dragY);
        v.setScaleX(1 - .15f * prog); v.setScaleY(1 - .15f * prog);
        host.onDismissDrag(prog);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (settleTo >= 0) { scroller.forceFinished(true); finishSettle(); }
            if (springBack != null) springBack.cancel();
            Page p = page(current); if (p != null) p.fling(0, 0);   // a touch stops a fling
            mode = NONE; holding = false;
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
        } else if (a == MotionEvent.ACTION_POINTER_DOWN) {
            if (mode == HORIZ || mode == VERT) { dragX = 0; dragY = 0; position(); applyDismiss(); }
            if (holding) { holding = false; host.onHold(false); }
            if (mode == LEVEL) host.onLevelEnd();
            mode = ZOOM;
        }
        if (velocity != null) velocity.addMovement(e);
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (velocity != null) velocity.computeCurrentVelocity(1000);
            float vx = velocity == null ? 0 : velocity.getXVelocity(), vy = velocity == null ? 0 : velocity.getYVelocity();
            boolean cancel = a == MotionEvent.ACTION_CANCEL;
            if (holding) { holding = false; host.onHold(false); }
            if (mode == LEVEL) host.onLevelEnd();
            if (settleTo < 0 && dragX != 0) settleTo(MediaMath.snapTarget(current, shownDrag(), cancel ? 0 : vx, getWidth(), host.count()));
            else if (mode == VERT && dragY != 0) {
                if (!cancel && MediaMath.shouldDismiss(dragY, vy, getHeight())) dismiss(); else springBack();
            } else if (mode == PAN && !cancel) { Page p = page(current); if (p != null) p.fling(vx, vy); }
            if (velocity != null) { velocity.recycle(); velocity = null; }
            mode = NONE;
        }
        return true;
    }

    private void springBack() {
        final View v = pages.get(current);
        if (v == null) return;
        if (!Ui.motionEnabled()) { dragY = 0; applyDismiss(); return; }
        springBack = ValueAnimator.ofFloat(dragY, 0);
        springBack.setDuration(220); springBack.setInterpolator(Ui.EASE_OUT);
        springBack.addUpdateListener(an -> { dragY = (float) an.getAnimatedValue(); applyDismiss(); });
        springBack.start();
    }

    private void dismiss() {
        final View v = pages.get(current);
        if (v == null || !Ui.motionEnabled()) { host.onDismiss(); return; }
        float to = Math.signum(dragY) * getHeight() * .6f;
        ValueAnimator an = ValueAnimator.ofFloat(dragY, to);
        an.setDuration(180); an.setInterpolator(Ui.EASE_OUT);
        an.addUpdateListener(u -> { dragY = (float) u.getAnimatedValue(); applyDismiss(); host.onDismissDrag(Math.max(MediaMath.dismissProgress(dragY, getHeight()), .7f)); });
        an.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator x) { host.onDismiss(); } });
        an.start();
    }

    // TalkBack: swipe through items with the scroll actions.
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (current < host.count() - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (current > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && current < host.count() - 1) { settleTo(current + 1); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && current > 0) { settleTo(current - 1); return true; }
        return super.performAccessibilityAction(action, args);
    }
}
