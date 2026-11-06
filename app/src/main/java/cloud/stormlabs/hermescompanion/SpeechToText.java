package cloud.stormlabs.hermescompanion;

interface SpeechToText {
    /** 16 kHz mono signed 16-bit little-endian PCM. */
    String transcribe(byte[] pcm, String language) throws Exception;
}
