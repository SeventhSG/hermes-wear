package cloud.stormlabs.hermescompanion;

/**
 * A switchable UI concept: palette plus the layout tokens that differ between concepts.
 * Pure data so the tables can be unit-tested; Ui copies the active one into its fields.
 * Eleven themes on two styles (Console, Refined), dark and light; an accent colour can override any of them (see {@link Accent}).
 */
public final class Concept {
    public enum Chat { BUBBLES, FLAT, CONSOLE }
    /** CONSOLE: Space Grotesk, strokeless top-lit cards, signal colours. REFINED: system sans, tier fills. */
    public enum Style { CONSOLE, REFINED }

    public final String id, name, blurb;
    public final Style style;
    public final int bg, bar, surface, raised, line, text, sub, faint;
    public final int blue, blueDark, green, amber, red;
    /** Links, inline code, code-block fill and text, and the user's message fill (top/bottom) and meta line. */
    public final int link, code, codeBg, codeText, mineTop, mineBottom, mineMeta;
    public final boolean light, mono;
    /** Multipliers for corner radii, paddings and type size. */
    public final float radius, density, type;
    public final Chat chat;
    /** Four surface tiers, progressively raised: 0 ground, 1 card, 2 raised/inner, 3 floating (sheets). */
    public final int[] tiers;
    /** Signal colours (carry meaning only) and the single interaction accent. */
    public final int ok, watch, critical, accent;
    /** Alpha (0-255) of the white inner top highlight on Console cards; 0 for none. */
    public final int highlightAlpha;
    /** Corner radii in dp (not multiplied by {@link #radius}). */
    public final float cardRadius, controlRadius;
    /** Cards carry a 1dp hairline (Refined Light, where tier fills alone are too faint). */
    public final boolean cardStroke;

    private Concept(String id, String name, String blurb, Style style, int[] p, int[] tiers,
                    boolean light, Chat chat, int highlightAlpha, float cardRadius, float controlRadius, boolean cardStroke) {
        this.id = id; this.name = name; this.blurb = blurb; this.style = style;
        bg = p[0]; bar = p[1]; surface = p[2]; raised = p[3]; line = p[4]; text = p[5]; sub = p[6]; faint = p[7];
        blue = p[8]; blueDark = p[9]; green = p[10]; amber = p[11]; red = p[12];
        link = p[13]; code = p[14]; codeBg = p[15]; codeText = p[16]; mineTop = p[17]; mineBottom = p[18]; mineMeta = p[19];
        this.light = light; this.mono = false; this.radius = 1f; this.density = 1f; this.type = 1f; this.chat = chat;
        this.tiers = tiers; ok = green; watch = amber; critical = red; accent = blue;
        this.highlightAlpha = highlightAlpha; this.cardRadius = cardRadius; this.controlRadius = controlRadius; this.cardStroke = cardStroke;
    }

    public static final Concept CONSOLE_DARK = new Concept("console", "Console Dark",
            "Mission control: graphite-blue, signal colours, Space Grotesk", Style.CONSOLE,
            new int[]{0xff0b0e13, 0xff0f131a, 0xff141922, 0xff1b212c, 0xff262d38, 0xffe8ecf2, 0xffa3acba, 0xff8791a1,
                    0xff6ea8ff, 0xff2f62c0, 0xff3ddbb0, 0xfff2b544, 0xffff6b6b,
                    0xff8dbbff, 0xfff2b544, 0xff080a0e, 0xffdce3ec, 0xff2f66d0, 0xff2858b8, 0xffb4d2ff},
            new int[]{0xff0b0e13, 0xff141922, 0xff1b212c, 0xff242b38},
            false, Chat.FLAT, 15, 14, 10, false);

    public static final Concept CONSOLE_LIGHT = new Concept("console-light", "Console Light",
            "Mission control on warm paper", Style.CONSOLE,
            new int[]{0xfff4f1ea, 0xfffbf9f5, 0xfffbf9f5, 0xffece7dc, 0xffd9d2c5, 0xff1c1b18, 0xff55504a, 0xff6b665e,
                    0xff2f6fd6, 0xff2a5fb8, 0xff0e8f6e, 0xffa86f00, 0xffd23f3f,
                    0xff2f6fd6, 0xff9a4f00, 0xffece7dc, 0xff2b2925, 0xff2f6fd6, 0xff2a5fb8, 0xffd6e4fb},
            new int[]{0xfff4f1ea, 0xfffbf9f5, 0xffece7dc, 0xffffffff},
            true, Chat.FLAT, 160, 14, 10, false);

    public static final Concept REFINED_DARK = new Concept("refined", "Refined Dark",
            "Calm graphite with a blue accent", Style.REFINED,
            new int[]{0xff101214, 0xff171a1e, 0xff1d2126, 0xff252a31, 0xff30363f, 0xfff1f3f5, 0xffa1a9b4, 0xff8590a0,
                    0xff5a9cf4, 0xff23578f, 0xff38c992, 0xffe7b24c, 0xffed6b72,
                    0xff8cc0ff, 0xffffd28a, 0xff0c0e11, 0xffdfe6ee, 0xff4a7ae8, 0xff3b61c2, 0xff9dc7f3},
            new int[]{0xff101214, 0xff1d2126, 0xff252a31, 0xff2e343c},
            false, Chat.BUBBLES, 0, 16, 12, false);

    public static final Concept REFINED_LIGHT = new Concept("refined-light", "Refined Light",
            "Calm warm paper with a blue accent", Style.REFINED,
            new int[]{0xfff7f4ee, 0xfffbf9f5, 0xffffffff, 0xffefeae1, 0xffdcd5c9, 0xff1f1d1a, 0xff57524b, 0xff736d64,
                    0xff2f6fd6, 0xff2a5fb8, 0xff1f8a5b, 0xff9a6200, 0xffc7353e,
                    0xff2f6fd6, 0xff9a4f00, 0xfff0ece4, 0xff2b2925, 0xffe9e3d8, 0xffe9e3d8, 0xff736d64},
            new int[]{0xfff7f4ee, 0xffffffff, 0xffefeae1, 0xffe6e0d4},
            true, Chat.BUBBLES, 0, 16, 12, true);

    // ── more colour themes (7.38): same two styles, other palettes ──────────────────────────────

    public static final Concept OLED = new Concept("oled", "OLED Black",
            "True black for OLED screens, cyan accent", Style.CONSOLE,
            new int[]{0xff000000, 0xff07090c, 0xff0d1014, 0xff151a20, 0xff1f262e, 0xffeef2f6, 0xffa6afbb, 0xff8a94a2,
                    0xff3dd9eb, 0xff0f8a9b, 0xff3ddbb0, 0xfff2b544, 0xffff6b6b,
                    0xff6ee7f5, 0xfff2b544, 0xff05070a, 0xffdce3ec, 0xff0f7f90, 0xff0b6676, 0xffa8eef7},
            new int[]{0xff000000, 0xff0d1014, 0xff151a20, 0xff1e242c},
            false, Chat.FLAT, 15, 14, 10, false);

    public static final Concept EMBER = new Concept("ember", "Ember",
            "Warm dark brown with an orange glow", Style.CONSOLE,
            new int[]{0xff120d0a, 0xff17110d, 0xff1d1611, 0xff271e17, 0xff382c22, 0xfff5ede6, 0xffb8a899, 0xff9b8b7c,
                    0xffff9a4d, 0xffc2621b, 0xff56d6a0, 0xfff2c14a, 0xffff6b6b,
                    0xffffb27a, 0xfff2c14a, 0xff0d0907, 0xffeadfd3, 0xffa8520f, 0xff8f450c, 0xffffd2b0},
            new int[]{0xff120d0a, 0xff1d1611, 0xff271e17, 0xff33271e},
            false, Chat.FLAT, 15, 14, 10, false);

    public static final Concept VIOLET = new Concept("violet", "Violet Night",
            "Deep indigo with a soft violet accent", Style.CONSOLE,
            new int[]{0xff0e0c14, 0xff13111b, 0xff191623, 0xff221e2f, 0xff312c43, 0xffeeeaf6, 0xffb1abc4, 0xff958fa9,
                    0xffa78bfa, 0xff6d4fd1, 0xff4fdcb0, 0xfff2b544, 0xffff6b8a,
                    0xffc4b0ff, 0xfff2b544, 0xff0a0910, 0xffe4def2, 0xff6d4fd1, 0xff5a3fb8, 0xffd9ccff},
            new int[]{0xff0e0c14, 0xff191623, 0xff221e2f, 0xff2b2640},
            false, Chat.FLAT, 15, 14, 10, false);

    public static final Concept NORD = new Concept("nord", "Nord",
            "Cool slate and frost blue, easy on the eyes", Style.REFINED,
            new int[]{0xff242933, 0xff2b303b, 0xff2e3440, 0xff3b4252, 0xff4c566a, 0xffeceff4, 0xffc2cad8, 0xffa0abbd,
                    0xff88c0d0, 0xff5e81ac, 0xffa3be8c, 0xffebcb8b, 0xffbf616a,
                    0xff8fbcbb, 0xffebcb8b, 0xff1e222b, 0xffe5e9f0, 0xff4c6f9b, 0xff3f5f87, 0xffd8e6f2},
            new int[]{0xff242933, 0xff2e3440, 0xff3b4252, 0xff434c5e},
            false, Chat.BUBBLES, 0, 16, 12, false);

    public static final Concept FOREST = new Concept("forest", "Forest",
            "Dark green-grey with a fresh green accent", Style.REFINED,
            new int[]{0xff0f1511, 0xff141b16, 0xff1a221d, 0xff232d26, 0xff303c34, 0xffecf3ee, 0xffa7b6ac, 0xff8a9a8f,
                    0xff5ccf8e, 0xff2e8a58, 0xff3ddbb0, 0xffe7b24c, 0xffed6b72,
                    0xff8fe0b0, 0xffe7b24c, 0xff0b100d, 0xffdfe9e2, 0xff2e8a58, 0xff267549, 0xffbfe9cf},
            new int[]{0xff0f1511, 0xff1a221d, 0xff232d26, 0xff2c372f},
            false, Chat.BUBBLES, 0, 16, 12, false);

    public static final Concept SKY = new Concept("sky", "Sky",
            "Bright cool white with a clear blue accent", Style.REFINED,
            new int[]{0xfff3f6fa, 0xfffbfcfe, 0xffffffff, 0xffe9eef5, 0xffd3dce8, 0xff16202b, 0xff4a5868, 0xff66758a,
                    0xff1f6bd6, 0xff1a56ab, 0xff0e8f6e, 0xff9a6200, 0xffc7353e,
                    0xff1f6bd6, 0xff9a4f00, 0xffe9eef5, 0xff1c2530, 0xffdce8f8, 0xffdce8f8, 0xff4a5868},
            new int[]{0xfff3f6fa, 0xffffffff, 0xffe9eef5, 0xffdfe6ef},
            true, Chat.BUBBLES, 0, 16, 12, true);

    public static final Concept SAGE = new Concept("sage", "Sage",
            "Soft green-white paper with a leafy accent", Style.REFINED,
            new int[]{0xfff3f5ef, 0xfffafbf7, 0xffffffff, 0xffe8ece1, 0xffd2d9c8, 0xff1b1f17, 0xff4f5846, 0xff6a7460,
                    0xff2f7d4f, 0xff256640, 0xff1f8a5b, 0xff9a6200, 0xffc7353e,
                    0xff2f7d4f, 0xff9a4f00, 0xffe8ece1, 0xff252a20, 0xffdcebdc, 0xffdcebdc, 0xff4f5846},
            new int[]{0xfff3f5ef, 0xffffffff, 0xffe8ece1, 0xffdde3d3},
            true, Chat.BUBBLES, 0, 16, 12, true);

    public static final Concept DEFAULT = CONSOLE_DARK;
    public static final Concept[] ALL = {CONSOLE_DARK, CONSOLE_LIGHT, REFINED_DARK, REFINED_LIGHT, OLED, EMBER, VIOLET, NORD, FOREST, SKY, SAGE};

    /** Stored id → concept. Retired ids map to the nearest new look; unknown or missing is the default. */
    public static Concept byId(String id) {
        if (id == null) return DEFAULT;
        for (Concept c : ALL) if (c.id.equals(id)) return c;
        switch (id) {
            case "graphite": case "midnight": case "terminal": case "dynamic": return REFINED_DARK;
            case "paper": return REFINED_LIGHT;
            default: return DEFAULT;
        }
    }

    /** Surface tier 0-3 (clamped). */
    public int tier(int t) { return tiers[Math.max(0, Math.min(3, t))]; }
}
