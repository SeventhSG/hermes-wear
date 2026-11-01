# Voice servers (optional, 7.34+)

Wyoming-protocol servers the app talks to on the Hermes host (ports 10300 STT, 10200 TTS). Run them on any box (a GPU helps); put a
proxy on the Hermes host so the app needs no new address.

- `parakeet_server.py` — STT: NVIDIA Parakeet TDT 0.6B v3 via `onnx-asr` (GPU fp32 or CPU int8). Sends `transcript-partial` events while you
  speak (live text), an `endpoint` event when a sentence ends and you went quiet, then the final `transcript`. Env: `PARAKEET_DEVICE=gpu|cpu`,
  `ENDPOINT_S` (default 0.7), `PARTIAL_STEP`. Pascal GPUs need `onnxruntime-gpu==1.24.x` with CUDA 12.6 libs and cuDNN 9.5; audio is padded to a
  few fixed lengths so a 4 GB card's allocator does not fragment. Plain Whisper (`wyoming-faster-whisper`) also works with the app (no partials).
- `piper_server.py` — TTS (CPU): English by Kokoro-82M (natural, ~0.5 s to first audio; needs `kokoro-onnx` and the `kokoro-v1.0.onnx` + `voices-v1.0.bin` files in `~/kokoro`), Bulgarian by Piper, chosen by script; streams audio per sentence. Voice names: any Kokoro id (`af_heart`) or a weighted mix (`af_heart:0.5+af_nicole:0.5`). Voice name `piper` or `TTS_EN=piper` gives the instant (~0.1 s) Piper English voice; Kokoro failures fall back to Piper.
- `whisper-proxy.py` — failover proxy (GPU box first, then a local small STT).

The app falls back to the phone's own voice if the TTS server is unreachable, and to one-shot transcription if streaming breaks.
