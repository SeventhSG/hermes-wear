package cloud.stormlabs.hermescompanion;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/** The profile-switch transition: the old profile avatar cross-fades and scales into the new one with its name (~450 ms, EASE_OUT), then the chat fades in. */
final class SwitchOverlay {
    private SwitchOverlay() {}

    /** Mid-tone hues that read on all four concepts (light Paper through black Midnight). */
    static final int[] PALETTE = {0xff4f86c6, 0xff8b6aae, 0xffc06868, 0xff4f9a83, 0xffc0873f, 0xff3aa5b8, 0xffb5628f, 0xff7d9a3c};

    /** Stable accent for a profile: same id, same colour, on every launch. */
    static int accent(String id) { return PALETTE[((id == null ? 0 : id.hashCode()) & 0x7fffffff) % PALETTE.length]; }

    /** {@code t} of the way from {@code a} to {@code b}, per channel. */
    static int mix(int a, int b, float t) {
        int r = 0xff000000;
        for (int s = 0; s <= 16; s += 8) r |= Math.round(((a >> s) & 255) * (1 - t) + ((b >> s) & 255) * t) << s;
        return r;
    }

    static GradientDrawable ring(int color, int px) {
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(0); d.setStroke(px, color); return d;
    }

    /** Uppercase first letter of a profile name ("H" when blank), the avatar glyph. */
    static String initial(String name) {
        String t = name == null ? "" : name.trim();
        return t.isEmpty() ? "H" : t.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    static final long TOTAL_MS = 450, SCRIM_MS = 150, AVATAR_MS = 300;

    static final class Overlay extends FrameLayout {
        final TextView avatar, oldAvatar, name, sub;
        Overlay(Context c, String profile, int accent, String oldProfile, int oldAccent) {
            super(c);
            setBackgroundColor(mix(Ui.BG, accent, .12f)); setClickable(true);
            LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER);
            int size = Ui.dp(c, 96);
            FrameLayout stack = new FrameLayout(c);
            oldAvatar = Ui.avatar(c, oldProfile == null ? profile : oldProfile, oldAccent, 96);
            avatar = Ui.avatar(c, profile, accent, 96);
            oldAvatar.setVisibility(oldProfile == null ? GONE : VISIBLE);
            for (TextView t : new TextView[]{oldAvatar, avatar}) { t.setTextSize(36); stack.addView(t, new FrameLayout.LayoutParams(size, size)); }
            col.addView(stack, new LinearLayout.LayoutParams(size, size));
            name = Ui.title(c, profile, Ui.TEXT); name.setTextSize(22); name.setGravity(Gravity.CENTER); name.setPadding(0, Ui.dp(c, 20), 0, 0);
            sub = Ui.caption(c, "Switching…", Ui.SUB); sub.setGravity(Gravity.CENTER); sub.setPadding(0, Ui.dp(c, 4), 0, 0);
            col.addView(name); col.addView(sub);
            addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        }

        /** Fade out and remove (a failed switch, or the chat is ready underneath). */
        void dismiss() {
            animate().alpha(0).setDuration(Ui.motionEnabled() ? 200 : 0).setInterpolator(Ui.EASE_OUT).withEndAction(() -> { if (getParent() != null) ((ViewGroup) getParent()).removeView(this); }).start();
        }
    }

    private static Overlay attach(Activity a, Overlay o) {
        ((ViewGroup) a.getWindow().getDecorView()).addView(o, new FrameLayout.LayoutParams(-1, -1));
        return o;
    }

    /** The finished overlay, instantly: MainActivity's splash and ChatActivity's first frame. */
    static Overlay still(Activity a, String profile, String id) {
        return attach(a, new Overlay(a, profile, accent(id), null, 0));
    }

    /** Old avatar becomes the new one (scrim 150 ms, avatars 300 ms, name settles by 450 ms), then {@code done}. dir &gt; 0: moves left, as if paging forward. */
    static void play(Activity a, View origin, String profile, String id, int dir, Consumer<Overlay> done) {
        SettingsStore store = new SettingsStore(a);
        Overlay o = attach(a, new Overlay(a, profile, accent(id), store.profileName(), accent(store.profileId())));
        if (!Ui.motionEnabled()) { o.post(() -> done.accept(o)); return; }
        float shift = Ui.dp(a, 24) * dir;
        o.setAlpha(0f);
        o.avatar.setAlpha(0f); o.avatar.setScaleX(.8f); o.avatar.setScaleY(.8f); o.avatar.setTranslationX(shift);
        o.name.setAlpha(0f); o.name.setTranslationY(Ui.dp(a, 6)); o.sub.setAlpha(0f);
        o.animate().alpha(1f).setDuration(SCRIM_MS).setInterpolator(Ui.EASE_OUT).start();
        o.oldAvatar.animate().alpha(0f).scaleX(.8f).scaleY(.8f).translationX(-shift).setStartDelay(100).setDuration(AVATAR_MS).setInterpolator(Ui.EASE_OUT).start();
        o.avatar.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setStartDelay(100).setDuration(AVATAR_MS).setInterpolator(Ui.EASE_OUT).start();
        o.sub.animate().alpha(1f).setStartDelay(200).setDuration(TOTAL_MS - 200).setInterpolator(Ui.EASE_OUT).start();
        o.name.animate().alpha(1f).translationY(0f).setStartDelay(150).setDuration(TOTAL_MS - 150).setInterpolator(Ui.EASE_OUT)
                .withEndAction(() -> done.accept(o)).start();
    }
}
