package cloud.stormlabs.hermescompanion;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Speech recognition backed by the app's own recorder and Whisper (on-device or the user's server).
 * Android only activates an assistant's VoiceInteractionService when it names a RecognitionService,
 * so without this the assistant overlay, screen context and wake word could never open.
 */
public final class HermesRecognitionService extends RecognitionService {
    private VoiceRecorder recorder;

    @Override protected void onStartListening(Intent intent, Callback cb) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { error(cb, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return; }
        if (recorder != null && recorder.running()) { error(cb, SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return; }
        try { cb.readyForSpeech(new Bundle()); } catch (RemoteException ignored) { }
        boolean[] began = {false};
        recorder = new VoiceRecorder(true);
        recorder.start(new VoiceRecorder.Listener() {
            public void onLevel(float level) {
                try {
                    if (!began[0] && level > 0.06f) { began[0] = true; cb.beginningOfSpeech(); }
                    cb.rmsChanged(-2 + level * 12);
                } catch (RemoteException ignored) { }
            }
            public void onError(String message) { error(cb, SpeechRecognizer.ERROR_AUDIO); }
            public void onDone(byte[] pcm, boolean heardSpeech) {
                try { cb.endOfSpeech(); } catch (RemoteException ignored) { }
                if (!heardSpeech) { error(cb, SpeechRecognizer.ERROR_SPEECH_TIMEOUT); return; }
                try {
                    String text = SpeechRecognition.transcribe(HermesRecognitionService.this, new SettingsStore(HermesRecognitionService.this), pcm).trim();
                    if (text.isEmpty()) { error(cb, SpeechRecognizer.ERROR_NO_MATCH); return; }
                    ArrayList<String> matches = new ArrayList<>();
                    matches.add(text);
                    Bundle results = new Bundle();
                    results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, matches);
                    cb.results(results);
                } catch (Exception e) { error(cb, SpeechRecognizer.ERROR_NETWORK); }
            }
        });
    }

    @Override protected void onStopListening(Callback cb) { if (recorder != null) recorder.stop(); }
    @Override protected void onCancel(Callback cb) { if (recorder != null) recorder.cancel(); }
    @Override public void onDestroy() {
        if (recorder != null) { recorder.cancel(); recorder = null; }
        super.onDestroy();
    }

    private static void error(Callback cb, int code) { try { cb.error(code); } catch (RemoteException ignored) { } }
}
