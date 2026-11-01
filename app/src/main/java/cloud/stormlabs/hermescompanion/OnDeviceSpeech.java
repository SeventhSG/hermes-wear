package cloud.stormlabs.hermescompanion;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;

import java.io.File;

final class OnDeviceSpeech implements SpeechToText {
    private final Context context;
    OnDeviceSpeech(Context context) { this.context = context.getApplicationContext(); }

    @Override public String transcribe(byte[] pcm, String language) throws Exception {
        if (!OnDeviceModel.installed(context)) throw new IllegalStateException("On-device model is not installed");
        File dir = OnDeviceModel.directory(context);
        OfflineNemoEncDecCtcModelConfig nemo = new OfflineNemoEncDecCtcModelConfig();
        nemo.setModel(new File(dir, "model.int8.onnx").getAbsolutePath()); // English only: language is ignored
        OfflineModelConfig model = new OfflineModelConfig();
        model.setNemo(nemo);
        model.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
        model.setNumThreads(2);
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(model);
        OfflineRecognizer recognizer = new OfflineRecognizer(null, config);
        try {
            OfflineStream stream = recognizer.createStream();
            try {
                float[] samples = new float[pcm.length / 2];
                for (int i = 0; i < samples.length; i++)
                    samples[i] = (short) ((pcm[2 * i] & 255) | (pcm[2 * i + 1] << 8)) / 32768f;
                stream.acceptWaveform(samples, WhisperClient.RATE);
                recognizer.decode(stream);
                return recognizer.getResult(stream).getText().trim();
            } finally { stream.release(); }
        } finally { recognizer.release(); }
    }
}
