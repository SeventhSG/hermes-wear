#!/usr/bin/env python3
"""Wyoming TTS server (CPU): English via Kokoro-82M (natural), Bulgarian via Piper; chosen by the script of the text.
Piper also serves English as the instant fallback (voice name "piper" / TTS_EN=piper, or if Kokoro fails).

synthesize {text, voice?: {name}} -> audio-start, audio-chunk..., audio-stop (16-bit mono PCM, streamed per sentence).
"""
import argparse, asyncio, logging, os, re, time
from functools import partial
import numpy as np
from piper import PiperVoice
from wyoming.audio import AudioChunk, AudioStart, AudioStop
from wyoming.event import Event
from wyoming.info import Attribution, Describe, Info, TtsProgram, TtsVoice
from wyoming.server import AsyncEventHandler, AsyncServer
from wyoming.tts import Synthesize

LOG = logging.getLogger("piper")
DIR = os.environ.get("PIPER_DIR") or os.path.expanduser("~/piper-voices")
VOICES = {"en": "en_US-lessac-medium", "bg": "bg_BG-dimitar-medium"}
KOKORO_DIR = os.environ.get("KOKORO_DIR") or os.path.expanduser("~/kokoro")
KOKORO_VOICE = os.environ.get("KOKORO_VOICE", "af_heart")
TTS_EN = os.environ.get("TTS_EN", "kokoro")
TTS_AZURE = os.environ.get("TTS_AZURE", "") == "1"   # make Azure the default English voice (else only "azure:<voice>" requests)
_voices = {}
_kokoro = None
_lock = asyncio.Lock() if False else None
CYR = re.compile(r"[Ѐ-ӿ]")


def voice(lang):
    if lang not in _voices:
        _voices[lang] = PiperVoice.load(os.path.join(DIR, VOICES[lang] + ".onnx"))
    return _voices[lang]


AZURE_KEY = os.environ.get("AZURE_SPEECH_KEY", "").strip()
AZURE_REGION = os.environ.get("AZURE_SPEECH_REGION", "").strip()
AZURE_VOICE = os.environ.get("AZURE_VOICE", "en-US-AvaMultilingualNeural")
AZURE_CAP = int(os.environ.get("AZURE_MONTHLY_CAP", "450000"))  # stay inside the 500K free (F0) allowance
AZURE_USAGE = os.path.expanduser("~/.azure-tts-usage.json")


def azure_ready():
    return bool(AZURE_KEY and AZURE_REGION)


def _usage():
    month = time.strftime("%Y-%m")
    try:
        import json
        d = json.load(open(AZURE_USAGE))
        if d.get("month") == month: return d
    except Exception:
        pass
    return {"month": month, "chars": 0}


def azure_within_cap(n):
    return _usage()["chars"] + n <= AZURE_CAP


def azure_count(n):
    import json
    d = _usage(); d["chars"] += n
    try: json.dump(d, open(AZURE_USAGE, "w"))
    except Exception: pass


def azure_voice(name):
    return name.split(":", 1)[1] if name and name.startswith("azure:") and len(name) > 6 else AZURE_VOICE


def azure_chunks(text, voice_name):
    """Yield (24000, int16 bytes) streamed from Azure Speech REST (neural voice); raises on any failure."""
    import urllib.request
    from xml.sax.saxutils import escape
    text = speakable(text)
    lang = "-".join(voice_name.split("-")[:2])
    ssml = f"<speak version='1.0' xml:lang='{lang}'><voice name='{voice_name}'>{escape(text)}</voice></speak>"
    req = urllib.request.Request(f"https://{AZURE_REGION}.tts.speech.microsoft.com/cognitiveservices/v1", data=ssml.encode("utf-8"),
        headers={"Ocp-Apim-Subscription-Key": AZURE_KEY, "Content-Type": "application/ssml+xml",
                 "X-Microsoft-OutputFormat": "raw-24khz-16bit-mono-pcm", "User-Agent": "hermes-tts"})
    with urllib.request.urlopen(req, timeout=15) as r:
        pending = b""
        while True:
            b = r.read(4800)
            if not b: break
            b = pending + b; keep = len(b) % 2
            pending = b[-keep:] if keep else b""
            b = b[:len(b) - keep]
            if b: yield 24000, b
    azure_count(len(text))


def kokoro():
    global _kokoro
    if _kokoro is None:
        import onnxruntime as ort
        from kokoro_onnx import Kokoro
        so = ort.SessionOptions(); so.intra_op_num_threads = 8
        sess = ort.InferenceSession(os.path.join(KOKORO_DIR, "kokoro-v1.0.onnx"), sess_options=so, providers=["CPUExecutionProvider"])
        _kokoro = Kokoro.from_session(sess, os.path.join(KOKORO_DIR, "voices-v1.0.bin"))
    return _kokoro


SENT = re.compile(r"(?<=[.!?…])\s+|\n+")
MD = re.compile(r"[*_`#>|~]+")
URL = re.compile(r"https?://\S+")


def speakable(text):
    return MD.sub("", URL.sub("link", text)).strip()


def kokoro_chunks(text, voice_id):
    """Yield (rate, int16 bytes) one sentence at a time so playback starts after the first sentence."""
    k = kokoro()
    lang = "en-gb" if voice_id[:1] == "b" else "en-us"
    style = style_for(voice_id)
    for sent in SENT.split(speakable(text)):
        if len(sent.strip()) < 2: continue
        audio, sr = k.create(sent.strip(), voice=style, speed=1.0, lang=lang)
        yield sr, (np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes()


BLEND = re.compile(r"[abefhijpz][fm]_\w+(:[0-9.]+)?(\+[abefhijpz][fm]_\w+(:[0-9.]+)?)+")


def kokoro_voice(name):
    """A Kokoro voice id, or a blend like "af_heart:0.6+af_nicole:0.4" (weighted mix of style vectors)."""
    if name and re.fullmatch(r"[abefhijpz][fm]_\w+", name): return name
    if name and BLEND.fullmatch(name): return name
    return KOKORO_VOICE


def style_for(voice_id):
    if "+" not in voice_id: return voice_id
    parts = []
    for p in voice_id.split("+"):
        n, _, w = p.partition(":")
        parts.append((n, float(w) if w else 1.0))
    total = sum(w for _, w in parts)
    k = kokoro()
    return sum(k.get_voice_style(n) * (w / total) for n, w in parts)


def pick(text, name):
    if name:
        for k, v in VOICES.items():
            if name in (v, k):
                return k
    letters = [c for c in text if c.isalpha()]
    return "bg" if letters and len(CYR.findall(text)) / len(letters) > 0.3 else "en"


class Handler(AsyncEventHandler):
    def __init__(self, info, *a, **kw):
        super().__init__(*a, **kw)
        self.info = info

    async def handle_event(self, event):
        if Describe.is_type(event.type):
            await self.write_event(self.info.event()); return True
        if not Synthesize.is_type(event.type):
            return True
        syn = Synthesize.from_event(event)
        vname = syn.voice.name if syn.voice else None
        lang = pick(syn.text, vname)
        use_kokoro = lang == "en" and TTS_EN == "kokoro" and vname != "piper"
        use_azure = use_kokoro and azure_ready() and (TTS_AZURE or (vname or "").startswith("azure:")) and azure_within_cap(len(syn.text))
        v = None if use_kokoro else voice(lang)
        t = time.time(); first = None; n = 0; rate_out = 22050
        loop = asyncio.get_running_loop()
        q = asyncio.Queue()

        def work():  # synthesis runs in a thread; chunks are pushed as Piper produces them (per sentence)
            try:
                if use_azure:
                    sent = False
                    try:
                        for item in azure_chunks(syn.text, azure_voice(vname)):
                            sent = True
                            loop.call_soon_threadsafe(q.put_nowait, item)
                        return
                    except Exception:
                        LOG.exception("azure failed, falling back to kokoro")
                        if sent: return
                if use_kokoro:
                    try:
                        for item in kokoro_chunks(syn.text, kokoro_voice(vname)):
                            loop.call_soon_threadsafe(q.put_nowait, item)
                        return
                    except Exception:
                        LOG.exception("kokoro failed, falling back to piper")
                for ch in voice(lang).synthesize(syn.text):
                    loop.call_soon_threadsafe(q.put_nowait, (ch.sample_rate, ch.audio_int16_bytes))
            finally:
                loop.call_soon_threadsafe(q.put_nowait, None)

        fut = loop.run_in_executor(None, work)
        started = False
        while (item := await q.get()) is not None:
            rate, pcm = item; rate_out = rate
            if not started:
                await self.write_event(AudioStart(rate=rate, width=2, channels=1).event()); started = True; first = time.time() - t
            await self.write_event(AudioChunk(rate=rate, width=2, channels=1, audio=pcm).event()); n += len(pcm)
        await fut
        if not started:
            await self.write_event(AudioStart(rate=22050, width=2, channels=1).event())
        await self.write_event(AudioStop().event())
        LOG.info("%s/%s %d chars -> %.1fs audio, first %.2fs total %.2fs", lang, "azure" if use_azure else "kokoro" if use_kokoro else "piper", len(syn.text), n / 2 / rate_out, first or 0, time.time() - t)
        return False


async def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--uri", default="tcp://0.0.0.0:10200")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO)
    for k in VOICES:  # preload + warm
        list(voice(k).synthesize("Ready."))
    if TTS_EN == "kokoro":
        list(kokoro_chunks("Ready. Warming up the voice.", KOKORO_VOICE))
    attr = Attribution(name="Kokoro + Piper", url="https://github.com/OHF-Voice/piper1-gpl")
    info = Info(tts=[TtsProgram(name="piper", description="Piper", attribution=attr, installed=True, version="1",
        voices=[TtsVoice(name=VOICES[k], description=VOICES[k], attribution=attr, installed=True, version="1", languages=[k]) for k in VOICES])])
    server = AsyncServer.from_uri(args.uri)
    LOG.info("ready on %s", args.uri)
    await server.run(partial(Handler, info))


if __name__ == "__main__":
    asyncio.run(main())
