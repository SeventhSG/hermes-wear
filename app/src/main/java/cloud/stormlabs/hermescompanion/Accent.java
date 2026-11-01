package cloud.stormlabs.hermescompanion;

/**
 * User-picked accent colour over any theme. Pure colour maths so it is unit-testable: the pick is nudged
 * until it reads on the theme's background, and the companion shades (pressed, link, message bubble) derive from it.
 */
final class Accent {
    private Accent() {}

    static final String[] NAMES = {"Theme default", "Blue", "Cyan", "Green", "Lime", "Amber", "Orange", "Red", "Pink", "Violet"};
    /** 0 = keep the theme's own accent. */
    static final int[] COLORS = {0, 0xff4f8ff7, 0xff2fc7dc, 0xff35c47c, 0xff9ccf3a, 0xfff2b544, 0xfff5873b, 0xffec5a5a, 0xffe7599f, 0xff9a78f2};

    static double luminance(int c) {
        double[] v = {(c >> 16) & 255, (c >> 8) & 255, c & 255};
        for (int i = 0; i < 3; i++) { double s = v[i] / 255; v[i] = s <= .03928 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4); }
        return .2126 * v[0] + .7152 * v[1] + .0722 * v[2];
    }

    static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }

    static int darker(int c, float t) { return mix(c, 0xff000000, t); }
    static int lighter(int c, float t) { return mix(c, 0xffffffff, t); }

    /** The accent made readable on {@code bg}: lightened on dark themes, darkened on light ones, until the contrast is at least {@code min}. */
    static int readableOn(int accent, int bg, double min) {
        boolean darkBg = luminance(bg) < .5;
        int c = accent;
        for (int i = 0; i < 12 && contrast(c, bg) < min; i++) c = darkBg ? lighter(c, .12f) : darker(c, .12f);
        return c;
    }

    /** A fill white text can sit on (message bubbles, primary buttons). */
    static int fillForWhiteText(int accent) {
        int c = accent;
        for (int i = 0; i < 12 && contrast(0xffffffff, c) < 4.5; i++) c = darker(c, .1f);
        return c;
    }
}
