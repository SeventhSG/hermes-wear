package cloud.stormlabs.hermescompanion;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A custom wake phrase ("Hey Jarvis") for the on-device keyword spotter. The spotter matches token sequences
 * of its SentencePiece (unigram) vocabulary, so the phrase is spelled with the same Viterbi split SentencePiece
 * uses; assets/kws-vocab.txt holds the model's pieces and scores. Training ({@link WakeTraining}) then picks the
 * strictest threshold that still hears the user's own voice.
 */
final class WakePhrase {
    private WakePhrase() {}

    /** Detection thresholds tried while training, strictest first (the built-in phrase uses 0.10-0.22; clear speech scores up to ~0.5). */
    static final float[] LADDER = {.45f, .38f, .32f, .26f, .21f, .16f, .12f, .08f};
    /** Training happens close and quiet; real use is farther and noisier, so the picked step is loosened by this much. */
    static final float MARGIN = .8f;
    static final int TRAIN_TIMES = 10;

    private static Map<String, Float> vocab;

    static synchronized Map<String, Float> vocab(Context c) throws IOException {
        if (vocab == null) vocab = read(new InputStreamReader(c.getAssets().open("kws-vocab.txt"), StandardCharsets.UTF_8));
        return vocab;
    }

    static Map<String, Float> read(Reader in) throws IOException {
        Map<String, Float> out = new HashMap<>();
        try (BufferedReader r = new BufferedReader(in)) {
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab > 0) out.put(line.substring(0, tab), Float.parseFloat(line.substring(tab + 1)));
            }
        }
        return out;
    }

    /** "hey, Jarvis!" -> "HEY JARVIS": letters and apostrophes, single spaces. */
    static String normalize(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z' ]", " ").trim().replaceAll(" +", " ");
    }

    /** Space-separated spotter tokens for the phrase ("▁HE Y ▁JA R VI S"), or null when it can't be spelled. */
    static String tokens(String phrase, Map<String, Float> vocab) {
        String p = normalize(phrase);
        if (p.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String word : p.split(" ")) {
            String w = "▁" + word;
            int n = w.length();
            double[] best = new double[n + 1];
            int[] from = new int[n + 1];
            java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
            best[0] = 0;
            for (int e = 1; e <= n; e++)
                for (int s = Math.max(0, e - 16); s < e; s++) {
                    Float score = vocab.get(w.substring(s, e));
                    if (score != null && best[s] + score > best[e]) { best[e] = best[s] + score; from[e] = s; }
                }
            if (best[n] == Double.NEGATIVE_INFINITY) return null;
            List<String> pieces = new ArrayList<>();
            for (int e = n; e > 0; e = from[e]) pieces.add(0, w.substring(from[e], e));
            out.addAll(pieces);
        }
        return String.join(" ", out);
    }

    /** Spotter keyword line; a trained threshold (> 0) overrides the global one for this phrase. */
    static String keyword(String tokens, float threshold) {
        return tokens + (threshold > 0 ? String.format(Locale.ROOT, " #%.3f", threshold) : "") + " @" + WakeWordService.WAKE;
    }

    /** Trained threshold moved by the sensitivity setting: lower wakes more easily. */
    static float scaled(float threshold, String sensitivity) {
        return threshold * ("Low".equals(sensitivity) ? 1.3f : "High".equals(sensitivity) ? .75f : 1f);
    }

    /**
     * hits[i] = times the phrase was heard at LADDER[i]. The loosest step counts every attempt; keep the strictest
     * step that still heard at least 80% of them (strict = fewer false wakes), loosened by {@link #MARGIN}. 0 when nothing was heard.
     */
    static float pick(int[] hits) {
        int heard = hits[hits.length - 1];
        if (heard == 0) return 0;
        for (int i = 0; i < hits.length; i++) if (hits[i] * 5 >= heard * 4) return LADDER[i] * MARGIN;
        return LADDER[LADDER.length - 1] * MARGIN;
    }

    /** "HEY JARVIS" -> "Hey Jarvis". */
    static String label(String phrase) {
        StringBuilder b = new StringBuilder();
        for (String w : normalize(phrase).toLowerCase(Locale.ROOT).split(" "))
            if (!w.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }
}
