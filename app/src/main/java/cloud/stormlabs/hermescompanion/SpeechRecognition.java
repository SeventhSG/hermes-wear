package cloud.stormlabs.hermescompanion;

import android.content.Context;
import java.io.IOException;

/** One entry point for voice sheet, assistant, and dictation. */
final class SpeechRecognition {
    private SpeechRecognition() {}

    /** Until then the voice port is known to be out of reach, so takes skip it and go straight over https. */
    private static volatile long portDownUntil;
    /** Where the last take's words came from, shown on the voice screens once it is transcribed. */
    static volatile String lastRoute = "";

    static void portDown() { portDownUntil = System.currentTimeMillis() + 60_000; }

    /** "https://your-domain" as the speech server means: always through the hub over https (works from anywhere). */
    static boolean httpsOnly(SettingsStore store) { return store.whisperAddress().matches("(?i)https?://.*"); }

    /** The hub that carries speech over https: the one in the speech server field when it is a URL, else the app's hub. */
    static HubApi hub(SettingsStore store) {
        if (!httpsOnly(store)) return new HubApi(store.hubUrl(), store.hubKey());
        String base = store.whisperAddress().trim().replaceAll("/+$", "");
        return new HubApi(base.endsWith("/hub") ? base : base + "/hub", store.hubKey());
    }

    /** Settings' "test": which route answers, voice port first, then https. */
    static String test(SettingsStore store) throws Exception {
        byte[] silence = new byte[WhisperClient.RATE];
        if (!httpsOnly(store)) {
            try { WhisperClient.parse(store.whisperAddress()).transcribe(silence, null); return "Speech server answers on the voice port (live words)"; }
            catch (IOException e) {
                try { hub(store).transcribe(silence, "en"); return "Voice port out of reach; the server answers over https (live words too)"; }
                catch (Exception viaHub) { e.addSuppressed(viaHub); throw new IOException("Neither the voice port nor https answered: " + HermesApi.friendly(viaHub), e); }
            }
        }
        hub(store).transcribe(silence, "en");
        return "Speech server answers over https (live words)";
    }

    static String transcribe(Context context, SettingsStore store, byte[] pcm) throws Exception {
        WhisperClient whisper = WhisperClient.parse(store.whisperAddress());
        SpeechToText server = (audio, language) -> {
            HubApi hub = hub(store);
            if (httpsOnly(store) || System.currentTimeMillis() < portDownUntil) { lastRoute = HTTPS; return hub.transcribe(audio, language); }
            try { lastRoute = SERVER; return whisper.transcribe(audio, language); }
            catch (java.io.IOException e) {  // voice port unreachable: the same server over the hub's https route
                try { lastRoute = HTTPS; String text = hub.transcribe(audio, language); portDown(); return text; }
                catch (Exception viaHub) { e.addSuppressed(viaHub); throw e; }
            }
        };
        SpeechToText phone = (audio, language) -> { lastRoute = PHONE; return new OnDeviceSpeech(context).transcribe(audio, language); };
        return transcribe(store.speechRecognition(), OnDeviceModel.installed(context), server, phone, pcm, store.speechLanguage());
    }

    /** A live (streaming) session when speech goes to the Whisper server, else null (on-device mode). */
    static LiveWhisper live(Context context, SettingsStore store, java.util.function.Consumer<String> onPartial, Runnable onEndpoint) {
        String mode = store.speechRecognition();
        if ("phone".equals(mode)) return null;  // Auto and Server: the server first, live words
        return new LiveWhisper(WhisperClient.parse(store.whisperAddress()), store.speechLanguage(), onPartial, store.fastEndpoint() ? onEndpoint : null,
                hub(store), httpsOnly(store) || System.currentTimeMillis() < portDownUntil);
    }

    /** The final text for a take: the live stream's answer, or the normal one-shot path if the stream broke. */
    static String finish(Context context, SettingsStore store, LiveWhisper live, byte[] pcm) throws Exception {
        if (live != null) {
            try { String text = clean(live.finish()); lastRoute = live.route.replace(" · live", ""); return text; }
            catch (Exception ignored) { /* fall back to one-shot */ }
        }
        return transcribe(context, store, pcm);
    }

    /** Package-private so selection and fallback can be tested without loading native code. */
    static String transcribe(String mode, boolean installed, SpeechToText server,
                             SpeechToText phone, byte[] pcm, String language) throws Exception {
        if ("phone".equals(mode)) {
            if (!installed) throw new IllegalStateException("Download the on-device model in Settings › Voice");
            return clean(phone.transcribe(pcm, language));
        }
        try { return clean(server.transcribe(pcm, language)); }
        catch (IOException e) {
            if (installed) return clean(phone.transcribe(pcm, language));
            throw e;
        }
    }

    /** Whisper's text without bracketed noise tags like [music], for callers outside this class. */
    static String tidy(String result) { return clean(result == null ? "" : result); }

    private static String clean(String result) {
        StringBuilder text = new StringBuilder();
        boolean space = false;
        boolean hasSpeech = false;
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            char close = c == '[' ? ']' : c == '(' ? ')' : c == '*' ? '*' : 0;
            if (close != 0) {
                int end = result.indexOf(close, i + 1);
                if (end >= 0) {
                    space = text.length() > 0;
                    i = end;
                    continue;
                }
            }
            if (c == '♪' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = text.length() > 0;
            } else {
                if (space) text.append(' ');
                text.append(c);
                hasSpeech |= Character.isLetterOrDigit(result.codePointAt(i));
                space = false;
            }
        }
        return hasSpeech ? text.toString() : "";
    }

    static final String PHONE = "On this phone · " + OnDeviceModel.NAME, SERVER = "Homelab server", HTTPS = "Homelab server over https";

    /** Where a take will go before it starts: the phone model, or the server (live words). */
    static String source(String mode, boolean installed) {
        return "phone".equals(mode) ? PHONE + " · no live words" : "Homelab server · live words";
    }

    /** The voice screens' label while a take runs: the live stream's route once it is known. */
    static String route(LiveWhisper live, String mode, boolean installed) { return live != null ? live.route : source(mode, installed); }
}
