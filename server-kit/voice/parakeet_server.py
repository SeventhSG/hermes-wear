#!/usr/bin/env python3
"""Wyoming ASR server: NVIDIA Parakeet TDT 0.6B v3 via onnx-asr (GPU fp32, or CPU int8 as PARAKEET_DEVICE=cpu).

One model serves both the live `transcript-partial` events (decode of the audio so far, ~every 0.3 s)
and the final `transcript`. Variable-length encoder: cost scales with audio length, not a fixed 30 s window.
"""
import argparse, asyncio, logging, os, time
os.environ.setdefault("OMP_NUM_THREADS", "2"); os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")  # numpy mel/resample
from functools import partial
import numpy as np
import onnx_asr
import onnxruntime as ort
from wyoming.asr import Transcribe, Transcript
from wyoming.audio import AudioChunk, AudioChunkConverter, AudioStart, AudioStop
from wyoming.event import Event
from wyoming.info import AsrModel, AsrProgram, Attribution, Describe, Info
from wyoming.server import AsyncEventHandler, AsyncServer

LOG = logging.getLogger("parakeet")
RATE = 16000
BPS = RATE * 2
MODEL = "nemo-parakeet-tdt-0.6b-v3"
MAX_SEG = 20 * RATE        # onnx-asr models take ~20-30 s per call
STEP = int(float(os.environ.get("PARTIAL_STEP", "0.3")) * BPS)
MIN = BPS // 2

_model = None
_lock = None


def opts(threads):
    """Few threads, no busy-wait: the default pool spins 16 threads at ~25% each between the tiny decoder calls
    of every live partial, which heated the CPU from 60 to 86C during a recording."""
    so = ort.SessionOptions(); so.intra_op_num_threads = threads; so.inter_op_num_threads = 1
    so.add_session_config_entry("session.intra_op.allow_spinning", "0")
    return so


def load():
    dev = os.environ.get("PARAKEET_DEVICE", "gpu")
    t = time.time()
    if dev == "gpu":
        prov = [("CUDAExecutionProvider", {"arena_extend_strategy": "kSameAsRequested", "cudnn_conv_use_max_workspace": "0"}), "CPUExecutionProvider"]
        # mel preprocessing on the CPU: its big Conv/STFT buffers are what ran the 4 GB card out of memory
        cpu = {"providers": ["CPUExecutionProvider"], "sess_options": opts(2)}
        m = onnx_asr.load_model(MODEL, providers=prov, sess_options=opts(2), preprocessor_config=dict(cpu),
                                resampler_config=dict(cpu))
    else:
        m = onnx_asr.load_model(MODEL, quantization="int8", providers=["CPUExecutionProvider"], sess_options=opts(8))
    LOG.info("loaded %s on %s in %.1fs", MODEL, dev, time.time() - t)
    return m


def to_float(pcm):
    return np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0


def trim_tail(a, keep=0.25):
    """Drop trailing silence beyond `keep` seconds (the recorder waits ~1.3 s of quiet before stopping)."""
    fr = RATE // 50
    n = len(a) // fr
    if n < 10:
        return a
    rms = np.sqrt((a[: n * fr].reshape(n, fr) ** 2).mean(1))
    thr = max(0.006, float(np.percentile(rms, 20)) * 3)
    voiced = np.nonzero(rms > thr)[0]
    if len(voiced) == 0:
        return a
    end = min(len(a), (voiced[-1] + 1) * fr + int(keep * RATE))
    return a[:end]


def split(a):
    """<=20 s pieces, cut at the quietest 100 ms in the last 4 s of each piece."""
    out = []
    while len(a) > MAX_SEG:
        w = a[MAX_SEG - 4 * RATE: MAX_SEG]
        fr = RATE // 10
        n = len(w) // fr
        i = int(np.argmin(np.sqrt((w[: n * fr].reshape(n, fr) ** 2).mean(1))))
        cut = MAX_SEG - 4 * RATE + i * fr + fr // 2
        out.append(a[:cut]); a = a[cut:]
    out.append(a)
    return out


ENDPOINT_S = float(os.environ.get("ENDPOINT_S", "0.7"))   # trailing silence that ends a take (if the text looks finished)
END_PUNCT = tuple(".?!…。")


def tail_silent(a, seconds):
    """True when the last `seconds` of audio are all quiet compared with the rest of the take."""
    fr = RATE // 50
    n = len(a) // fr
    need = int(seconds * 50)
    if n < need + 10:
        return False
    rms = np.sqrt((a[: n * fr].reshape(n, fr) ** 2).mean(1))
    thr = max(0.008, float(np.percentile(rms, 20)) * 3)
    return bool((rms[-need:] < thr).all()) and bool((rms[:-need] > thr).any())


BUCKETS = [int(x * RATE) for x in (2, 3, 4, 6, 8, 10, 13, 16, 20)]


def bucket(a):
    """Zero-pad to one of a few fixed lengths so the GPU allocator keeps reusing the same buffers."""
    for b in BUCKETS:
        if len(a) <= b:
            return np.pad(a, (0, b - len(a)))
    return a


_cpu = None
_gpu_broken = False   # set when decoding yields <unk> garbage (weights corrupted in RAM after a suspend/resume); restart after the reply


def run(a):
    global _cpu, _gpu_broken
    try:
        text = str(m.recognize(bucket(a), sample_rate=RATE)).strip()
        if "<unk>" not in text:
            return text
        _gpu_broken = True
        raise RuntimeError("GPU decoded <unk> garbage")
    except Exception as e:  # GPU out of memory etc.: answer from a CPU int8 copy instead of failing the request
        LOG.warning("GPU decode failed (%s); using CPU", str(e)[:120])
        if _cpu is None:
            _cpu = onnx_asr.load_model(MODEL, quantization="int8", providers=["CPUExecutionProvider"], sess_options=opts(8))
        return str(_cpu.recognize(a, sample_rate=RATE)).strip()


def recognize(a):
    if len(a) < RATE // 5:
        return ""
    return " ".join(run(seg) for seg in split(a)).strip()


class Handler(AsyncEventHandler):
    def __init__(self, info, *args, **kw):
        super().__init__(*args, **kw)
        self.info = info
        self.conv = AudioChunkConverter(rate=RATE, width=2, channels=1)
        self.reset()

    def reset(self):
        self.buf = bytearray(); self.last = 0; self.busy = False; self.open = False; self.text = ""; self.ended = False; self.sent = 0

    async def disconnect(self):
        if self.open:  # the client hung up mid-take (cancelled, no speech heard, or a broken link)
            LOG.info("take dropped without audio-stop: %.2fs audio, %d partials", len(self.buf) / BPS, self.sent)

    async def handle_event(self, event):
        if Describe.is_type(event.type):
            await self.write_event(self.info.event()); return True
        if Transcribe.is_type(event.type):
            return True
        if AudioStart.is_type(event.type):
            self.reset(); self.open = True; LOG.info("take start"); return True
        if AudioChunk.is_type(event.type):
            self.buf += self.conv.convert(AudioChunk.from_event(event)).audio
            if self.open and not self.busy and len(self.buf) >= MIN and len(self.buf) - self.last >= STEP:
                self.busy = True; self.last = len(self.buf)
                asyncio.create_task(self.partial(bytes(self.buf)))
            return True
        if AudioStop.is_type(event.type):
            self.open = False
            t = time.time()
            a = trim_tail(to_float(bytes(self.buf)))
            async with _lock:
                text = await asyncio.to_thread(recognize, a)
            LOG.info("final %.2fs audio -> %.2fs (%d partials): %s", len(a) / RATE, time.time() - t, self.sent, text)
            await self.write_event(Transcript(text=text).event())
            if _gpu_broken:  # systemd (Restart=always) reloads the model in ~5 s
                LOG.error("GPU state broken: exiting for a clean restart")
                os._exit(1)
            return False
        return True

    async def partial(self, pcm):
        try:
            async with _lock:
                if not self.open:
                    return
                a = to_float(pcm)[-MAX_SEG:]
                text = await asyncio.to_thread(run, a)
            if self.open and text and text != self.text:
                self.text = text; self.sent += 1
                await self.write_event(Event(type="transcript-partial", data={"text": text}))
            # Early end-of-turn: the words read as a finished sentence and the audio has gone quiet.
            if self.open and not self.ended and text and text.endswith(END_PUNCT) and tail_silent(to_float(pcm), ENDPOINT_S):
                self.ended = True
                await self.write_event(Event(type="endpoint", data={"text": text}))
        except Exception as e:
            LOG.warning("partial failed: %s", e)
        finally:
            self.busy = False


async def main():
    global _lock, m, _gpu_broken
    ap = argparse.ArgumentParser()
    ap.add_argument("--uri", default="tcp://0.0.0.0:10300")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO)
    m = load()
    recognize(np.zeros(RATE * 2, dtype=np.float32) + 0.001)  # warm up kernels
    if _gpu_broken:  # still garbage after a fresh load (corrupt cached fp32 weights): serve the int8 copy on the CPU
        LOG.error("GPU decodes garbage at startup; serving on CPU int8")
        os.environ["PARAKEET_DEVICE"] = "cpu"; m = load(); _gpu_broken = False
    _lock = asyncio.Lock()
    info = Info(asr=[AsrProgram(
        name="parakeet", description="NVIDIA Parakeet TDT 0.6B v3 (onnx-asr)", version="1",
        attribution=Attribution(name="NVIDIA", url="https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3"), installed=True,
        models=[AsrModel(name=MODEL, description="Parakeet TDT 0.6B v3", version="3", installed=True,
                         attribution=Attribution(name="NVIDIA", url="https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3"),
                         languages=["en", "bg", "de", "fr", "es", "it", "ro", "ru", "uk", "el", "pl", "nl"])])])
    server = AsyncServer.from_uri(args.uri)
    LOG.info("ready on %s", args.uri)
    await server.run(partial(Handler, info))


if __name__ == "__main__":
    asyncio.run(main())
