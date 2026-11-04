"""Hermes Companion (Android app) as a Hermes gateway platform.

The app keeps speaking the /v1/runs contract it already uses with api_server (POST /v1/runs, SSE
/v1/runs/{id}/events replayed by seq, /steer, /stop, /approval), on this adapter's own port. Each run
is a gateway chat turn instead of an api_server run, so Hermes treats the phone like Telegram: a person
is present, dangerous commands and execute_code ask for approval (api_server is "unattended" and denies
them), clarify questions and slash commands work, and messages sent outside a run (cron deliver=companion,
send_message, background-process notices) are pushed to the phone through the hub.

chat_id is the app's Hermes session id; the first turn repoints the gateway route at that session so
history, titles and the app's session list stay the same ones api_server shows.
Auth: the api_server key (the app already holds it), bearer, constant-time compare.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import dataclasses
import hashlib
import hmac
import json
import logging
import math
import os
import re
import shutil
import threading
import time
import urllib.request
import uuid
from typing import Any, Dict, Optional

from aiohttp import web

from gateway.config import Platform
from gateway.platforms._shared import get_scoped_secret
from gateway.platforms.base import BasePlatformAdapter, SendResult, cache_image_from_bytes
from gateway.platforms.event import MessageEvent, MessageType

logger = logging.getLogger(__name__)

NAME = "companion"
DEFAULT_PORT = 8797
KEEP_FINISHED_S = 3600   # finished runs stay replayable this long
PRUNE_RUNS_S = 60        # also expire finished runs when no new turns arrive
GRACE_S = 30             # a finished run still claims the stream consumer's last sends
MUTE_S = 4               # gateway acks of our own /steer, /stop, /model commands are not shown
WATCH_S = 5              # how often a dispatched run checks that the gateway still holds its chat
FILES_DIR = os.path.expanduser("~/.hermes/companion/files")   # stable copies of temp files sent to the phone
KEEP_FILES_S = 30 * 86400
MAX_FILES_BYTES = 500 * 1024 * 1024
_FILES_LOCK = threading.Lock()   # copies and quota eviction run on worker threads
_TEMP_DIRS = ("/tmp/", "/var/tmp/", "/dev/shm/", os.path.expanduser("~/.hermes/cache/"))
_ADAPTERS: list = []     # live adapters, for the agent-thread hooks
_MARKUP = re.compile(r"[*_`#>~|\[\]()▉]+")


def _norm(text: Any) -> str:
    """Comparable form of a message: markdown, cursor and spacing stripped (the consumer may restyle)."""
    return " ".join(_MARKUP.sub(" ", str(text or "")).split())


def _preview(args: Any) -> str:
    if isinstance(args, dict):
        for key in ("command", "path", "file_path", "query", "url", "code", "name"):
            if isinstance(args.get(key), str) and args[key].strip():
                return args[key].strip().splitlines()[0][:160]
    return ""


def _prune_files(reserve: int = 0) -> None:
    """Expire copied attachments, then evict the oldest until the next copy fits."""
    cutoff = time.time() - KEEP_FILES_S
    copies = []
    with os.scandir(FILES_DIR) as dirs:
        for d in dirs:
            if d.is_dir(follow_symlinks=False):
                with os.scandir(d.path) as files:
                    size = sum(f.stat(follow_symlinks=False).st_size for f in files
                               if f.is_file(follow_symlinks=False))
                copies.append((d.stat(follow_symlinks=False).st_mtime, d.path, size))
    total = sum(size for _, _, size in copies)
    for modified, path, size in sorted(copies):
        if modified < cutoff or total + reserve > MAX_FILES_BYTES:
            shutil.rmtree(path)
            total -= size


def _stable(path: str) -> str:
    """A path the hub can still serve when the phone opens the card later. Temp files (TTS replies,
    scratch output) and names with spaces (a MEDIA: line ends at whitespace) are copied to FILES_DIR."""
    real = os.path.realpath(path)
    if not real.startswith(_TEMP_DIRS) and not re.search(r"\s", real):
        return real
    with _FILES_LOCK:
        size = os.path.getsize(real)
        if size > MAX_FILES_BYTES:
            raise OSError("file exceeds the companion attachment storage limit")
        os.makedirs(FILES_DIR, exist_ok=True)
        _prune_files(reserve=size)
        dest = os.path.join(FILES_DIR, uuid.uuid4().hex[:12], re.sub(r"\s+", "_", os.path.basename(real)))
        os.makedirs(os.path.dirname(dest))
        try:
            shutil.copyfile(real, dest)
            if os.path.getsize(dest) > MAX_FILES_BYTES:
                raise OSError("file exceeds the companion attachment storage limit")
            _prune_files()
        except OSError:
            shutil.rmtree(os.path.dirname(dest), ignore_errors=True)
            raise
        return dest


def _tail(text: Any, n: int = 4000) -> str:
    text = text if isinstance(text, str) else json.dumps(text, default=str)
    return text if len(text) <= n else "…" + text[-n:]


class Run:
    """One phone turn: events replayed by seq, with consecutive unread deltas coalesced."""

    def __init__(self, rid: str, chat_id: str, loop, quiet: bool = False):
        self.id, self.chat_id, self.session_id, self.loop, self.quiet = rid, chat_id, chat_id, loop, quiet
        self.session_key = ""
        self.events: list = []
        self.exposed_seq = -1          # events handed to any SSE writer are immutable, even after disconnect
        self._wake_pending = False
        self.lock = threading.Lock()
        self.waiters: set = set()
        self.status, self.finished_at, self.error = "running", 0.0, ""
        self.streamed, self.segment, self.new_segment, self.final = "", "", False, ""
        self.files: list = []          # MEDIA: lines for files the gateway sent; kept in the final output
        self.known: list = []          # commentary/notices already shown, so their consumer echo is dropped
        self.steered: set = set()
        self.approval_key = ""
        self.clarify = self.stopping = self.sent = False
        self.clarify_id = ""
        self.early: list = []          # steers sent before the message reached the gateway; they ride along with it
        self.mute_until = 0.0
        self.done = loop.create_future() if quiet else None

    def emit(self, name: str, **data) -> None:
        with self.lock:
            if self.status != "running":
                return
            self._append(name, data)

    def _append(self, name, data):
        field = {"assistant.delta": "delta", "reasoning.available": "text"}.get(name)
        previous = self.events[-1] if self.events else None
        if (field and previous and previous["event"] == name and previous["seq"] > self.exposed_seq
                and not previous.get("segment") and not data.get("segment")):
            previous[field] += data[field]
        else:
            data.update(event=name, seq=len(self.events), run_id=self.id, timestamp=time.time())
            self.events.append(data)
        if not self._wake_pending:
            self._wake_pending = True
            self.loop.call_soon_threadsafe(self._wake)

    def _wake(self):
        with self.lock:
            self._wake_pending = False
        for w in list(self.waiters):
            w.set()

    def delta(self, text: str) -> None:
        if self.new_segment and self.streamed:
            self.emit("assistant.delta", delta="\n\n", segment=True)  # the phone starts a new bubble
        self.new_segment = False
        self.emit("assistant.delta", delta=text)
        self.streamed += text
        self.segment += text

    def end_segment(self) -> None:
        if self.segment.strip():
            self.new_segment = True
        self.segment = ""

    def note(self, text: str) -> None:
        if _norm(text) and _norm(text) not in _norm(self.streamed):
            self.known.append(_norm(text))
            self.emit("assistant.commentary", text=text)

    def attach(self, path: str) -> None:
        """A file card in the reply: the app renders a MEDIA: line as an image, player or file card."""
        line = "MEDIA:" + path
        self.files.append(line)
        self.delta("\n\n" + line + "\n\n")
        self.end_segment()

    def steer(self, text: str) -> None:
        if text not in self.steered:
            self.steered.add(text)
            self.emit("run.steered", text=text)

    def finish(self, status: str, error: str = "", session_id: str = "") -> None:
        with self.lock:
            if self.status != "running":
                return
            self.session_id = session_id or self.session_id
            output = self.final or self.segment or self.streamed
            extra = [line for line in self.files if line not in output]
            if extra:
                output = output.rstrip() + "\n\n" + "\n".join(extra)
            self._append("run." + status, dict(output=output, error=error, session_id=self.session_id))
            self.status, self.error, self.finished_at = status, error, time.time()
        if self.done is not None and not self.done.done():
            self.loop.call_soon_threadsafe(lambda: self.done.done() or self.done.set_result(status))

    def info(self) -> dict:
        output = self.final or self.streamed
        extra = [line for line in self.files if line not in output]
        return {"run_id": self.id, "status": self.status, "session_id": self.session_id, "error": self.error,
                "output": output.rstrip() + "\n\n" + "\n".join(extra) if extra else output}


class CompanionAdapter(BasePlatformAdapter):
    def __init__(self, config, **kwargs):
        super().__init__(config, Platform(NAME))
        extra = getattr(config, "extra", None) or {}
        self._host = str(get_scoped_secret("COMPANION_GATEWAY_HOST") or extra.get("host") or "0.0.0.0")
        self._port = int(get_scoped_secret("COMPANION_GATEWAY_PORT") or extra.get("port") or DEFAULT_PORT)
        self._web = None
        self._loop = None
        self._gc_timer = None
        self.runs: Dict[str, Run] = {}
        self.chats: Dict[str, Run] = {}      # chat_id -> its current (or just finished) run
        self._models: Dict[str, str] = {}    # chat_id -> model last applied with /model
        self._account_cache = None          # one account route, at most one provider fetch in flight
        self._account_task = None
        self._account_key = None

    # ── lifecycle ────────────────────────────────────────────────────────────────────────────
    async def connect(self, *, is_reconnect: bool = False) -> bool:
        from gateway.platforms.tcp_site import start_tcp_site
        self._loop = asyncio.get_running_loop()
        app = web.Application(middlewares=[self._auth], client_max_size=64 * 1024 * 1024)
        r = app.router
        r.add_get("/health", self._health)
        r.add_get("/v1/capabilities", self._health)
        r.add_get("/v1/usage", self._usage)
        r.add_post("/v1/settings", self._settings)
        r.add_post("/v1/runs", self._start)
        r.add_get("/v1/runs/{rid}", self._status)
        r.add_get("/v1/runs/{rid}/events", self._events)
        r.add_post("/v1/runs/{rid}/stop", self._stop)
        r.add_post("/v1/runs/{rid}/steer", self._steer)
        r.add_post("/v1/runs/{rid}/approval", self._approval)
        r.add_post("/v1/runs/{rid}/clarify", self._clarify)
        self._web = web.AppRunner(app)
        await self._web.setup()
        try:
            await start_tcp_site(self._web, self._host, self._port, log_tag=NAME)
        except OSError as exc:
            await self._web.cleanup()
            self._web = None
            logger.error("[%s] could not bind %s:%d: %s", NAME, self._host, self._port, exc)
            return False
        _ADAPTERS.append(self)
        self._prune_runs()
        self._mark_connected()
        self._wire_plugin_handlers(None)
        logger.info("[%s] app run API on %s:%d", NAME, self._host, self._port)
        return True

    async def disconnect(self) -> None:
        if self._account_task is not None:
            self._account_task.cancel()
            await asyncio.gather(self._account_task, return_exceptions=True)
            self._account_task = None
        self._account_cache = None
        if self._gc_timer is not None:
            self._gc_timer.cancel()
            self._gc_timer = None
        if self in _ADAPTERS:
            _ADAPTERS.remove(self)
        if self._web:
            await self._web.cleanup()
            self._web = None
        self._mark_disconnected()

    @web.middleware
    async def _auth(self, request, handler):
        if request.path != "/health":
            key = get_scoped_secret("API_SERVER_KEY") or ""
            auth = request.headers.get("Authorization", "")
            got = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
            if not key or not hmac.compare_digest(got.encode(), key.encode()):
                return web.json_response({"error": "unauthorized"}, status=401)
        return await handler(request)

    # ── HTTP: runs ───────────────────────────────────────────────────────────────────────────
    async def _health(self, request):
        return web.json_response({"status": "ok", "platform": NAME, "runs": True, "approvals": True,
                                  "steer": True, "images": True, "clarify": True})

    async def _usage(self, request):
        sid = request.query.get("session_id", "").strip()
        if not sid:
            return web.json_response({"error": "session_id is required"}, status=400)
        source = self._source(sid)
        key = self._source_session_key(source)
        runner = self.gateway_runner
        agent = entry = None
        try:
            agent = runner._resident_agent_for(key)
        except Exception:
            pass   # private runner API may be absent on older Hermes versions
        try:
            entry = await asyncio.to_thread(runner.session_store.lookup_by_session_key, key)
        except Exception:
            pass
        result = {"as_of": int(time.time())}
        ctx = getattr(agent, "context_compressor", None)
        used = getattr(ctx, "last_prompt_tokens", None)
        window = getattr(ctx, "context_length", None)
        source_name = "live" if agent is not None else "last_turn"
        if agent is None:
            used = getattr(entry, "last_prompt_tokens", None)
            try:
                _, window, _ = await runner._resolve_context_figures(None, None, entry, source)
            except Exception:
                pass
        if isinstance(used, (int, float)) and math.isfinite(used) and used >= 0:
            context = {"used_tokens": used, "source": source_name}
            if isinstance(window, (int, float)) and math.isfinite(window) and window > 0:
                context.update(window_tokens=window, percent=max(0, min(100, used / window * 100)))
            result["context"] = context
        session = {}
        for field, attr in (("input_tokens", "session_prompt_tokens"),
                            ("output_tokens", "session_completion_tokens"),
                            ("total_tokens", "session_total_tokens"),
                            ("cached_input_tokens", "session_cache_read_tokens"),
                            ("cache_write_tokens", "session_cache_write_tokens")):
            value = getattr(agent, attr, None)
            if isinstance(value, (int, float)) and math.isfinite(value) and value >= 0:
                session[field] = value
        if session:
            result["session"] = session
        try:
            from tools.approval import is_session_yolo_enabled
            result["yolo"] = is_session_yolo_enabled(key) or getattr(entry, "yolo", False) is True
        except (ImportError, AttributeError):
            pass
        provider, base_url, api_key = (getattr(agent, k, None) for k in ("provider", "base_url", "api_key"))
        if not provider:
            try:
                provider, base_url = await runner._persisted_billing_route(source)
            except Exception:
                pass
        if not provider:
            try:
                from gateway.slash_commands_status import _configured_provider
                provider = await asyncio.to_thread(_configured_provider)
            except Exception:
                pass
        if provider:
            snapshot = await self._account_usage(provider, base_url, api_key, request.query.get("refresh") == "1")
            if snapshot is not None:
                limits = []
                for i, w in enumerate(snapshot.windows):
                    limit = {"id": re.sub(r"[^a-z0-9]+", "_", w.label.lower()).strip("_") or str(i),
                             "label": w.label}
                    if isinstance(w.used_percent, (int, float)) and math.isfinite(w.used_percent):
                        limit["used_percent"] = max(0, min(100, w.used_percent))
                    if w.reset_at is not None:
                        limit["resets_at"] = int(w.reset_at.timestamp())
                    limits.append(limit)
                if limits:
                    result["limits"] = limits
                if snapshot.plan:
                    result["plan"] = snapshot.plan
                # Hermes exposes credits in the Codex usage response; redeem only through /usage reset.
                raw = getattr(snapshot, "raw", None) or {}
                credits = (raw.get("rate_limit_reset_credits") or {}).get("available_count")
                if provider == "openai-codex" and isinstance(credits, int) and credits > 0:
                    result["resets"] = {"available": credits, "label": "Use a limit reset"}
        return web.json_response(result)

    async def _account_usage(self, provider, base_url, api_key, refresh):
        # Hash credentials for cache isolation; keep neither secrets nor an unbounded per-chat cache.
        key = hashlib.sha256(json.dumps([provider, base_url, api_key]).encode()).digest()
        if not refresh and self._account_cache is not None:
            cached_key, fetched, snapshot = self._account_cache
            if key == cached_key and time.monotonic() - fetched < 60:
                return snapshot
        if self._account_task is not None and self._account_key != key:
            if not self._account_task.done():
                return None
            self._account_task = None
        if self._account_task is None:
            async def fetch():
                try:
                    from agent.account_usage import fetch_account_usage
                    return await asyncio.to_thread(fetch_account_usage, provider, base_url=base_url, api_key=api_key)
                except Exception:
                    return None
            self._account_key = key
            self._account_task = asyncio.create_task(fetch())
        task = self._account_task
        try:
            # Shield the worker: a timed-out HTTP request must not start another provider thread.
            snapshot = await asyncio.wait_for(asyncio.shield(task), 10)
        except asyncio.TimeoutError:
            return None
        if self._account_task is task:
            self._account_cache = (key, time.monotonic(), snapshot)
            self._account_task = None
        return snapshot

    async def _settings(self, request):
        try:
            body = await request.json()
        except Exception:
            return web.json_response({"error": "bad json"}, status=400)
        if (not isinstance(body, dict) or set(body) - {"session_id", "yolo"}
                or not isinstance(body.get("session_id"), str) or not body["session_id"].strip()
                or not isinstance(body.get("yolo"), bool)):
            return web.json_response({"error": "session_id and boolean yolo are required"}, status=400)
        source = self._source(body["session_id"].strip())
        key = self._source_session_key(source)
        try:
            from tools.approval_yolo import toggle_session_yolo
        except ImportError:
            return web.json_response({"error": "per-chat YOLO is unavailable"}, status=501)
        store = getattr(self.gateway_runner, "session_store", None)
        persisted, persist = False, None
        if store is not None:
            entry = await asyncio.to_thread(store.get_or_create_session, source)
            persisted = getattr(entry, "yolo", False) is True
            persist = lambda on: store.set_session_yolo(key, on)
        enabled = await asyncio.to_thread(toggle_session_yolo, key, enabled=body["yolo"],
                                          persisted=persisted, persist=persist)
        return web.json_response({"ok": True, "yolo": enabled, "applies": "now"})

    async def _start(self, request):
        try:
            body = await request.json()
        except Exception:
            return web.json_response({"error": "bad json"}, status=400)
        sid = str(body.get("session_id") or "").strip()
        if not sid:
            return web.json_response({"error": "session_id is required"}, status=400)
        busy = self.chats.get(sid)
        if busy is not None and busy.status == "running":
            return web.json_response({"error": "a run is already active in this session", "run_id": busy.id}, status=409)
        try:
            text, images = await self._input(body.get("input"))
        except ValueError as exc:
            return web.json_response({"error": str(exc)}, status=400)
        if not text and not images:
            return web.json_response({"error": "input is empty"}, status=400)
        self._gc()
        run = Run("gw_" + uuid.uuid4().hex, sid, self._loop)   # the app routes gw_ runs to this port
        self.runs[run.id] = self.chats[sid] = run
        asyncio.create_task(self._drive(run, text, images, body))
        return web.json_response({"run_id": run.id, "status": "started", "session_id": sid})

    def _source(self, chat_id: str):
        return self.build_source(chat_id=chat_id, chat_name="Hermes Companion", chat_type="dm",
                                 user_id="owner", user_name="Owner")

    async def _drive(self, run: Run, text: str, images: list, body: dict) -> None:
        source = self._source(run.chat_id)
        try:
            # Right after a gateway restart inbound is parked (and "handled" at once) until startup restore ends.
            for i in range(360):
                if not getattr(self.gateway_runner, "_startup_restore_in_progress", False):
                    break
                if i == 0:
                    run.emit("run.queued")
                await asyncio.sleep(0.5)
            if run.status != "running":
                return   # stopped while it waited
            if getattr(self.gateway_runner, "_draining", False) is True:
                run.finish("failed", error="Hermes is restarting. Send this again in a minute.")
                return
            run.session_key = await self._bind(source, run.chat_id)
            model = str(body.get("model") or "").strip()
            if model and self._models.get(run.chat_id) != model:
                provider = str(body.get("provider") or "").strip()
                await self._command(run.chat_id, f"/model {model}" + (f" --provider {provider}" if provider else ""))
                self._models[run.chat_id] = model
            self.chats[run.chat_id] = run
            if run.status != "running":
                return
            text = "\n\n".join(t for t in [text, *run.early] if t)
            event = MessageEvent(text=text, message_type=MessageType.PHOTO if images else MessageType.TEXT,
                                 source=source, message_id=run.id,
                                 channel_prompt=str(body.get("instructions") or "").strip() or None)
            for path, mime in images:
                event.media_urls.append(path)
                event.media_types.append(mime)
            run.emit("run.started", session_id=run.session_id)
            run.sent = True
            await self.handle_message(event)
            await self._watch(run)
        except Exception as exc:
            logger.exception("[%s] run %s failed to start", NAME, run.id)
            run.finish("failed", error=str(exc)[:300])

    async def _watch(self, run: Run) -> None:
        """Safety net for a dispatched run. The gateway can drop or park a message without any processing hook
        (no handler while it shuts down, a full busy queue, startup parking), and a follow-up drained behind
        another turn gets no hooks on this adapter. When nothing has held the chat for three checks in a row,
        end the run instead of leaving the app waiting, and every later send refused as busy, forever."""
        keys = {run.session_key, self._source_session_key(self._source(run.chat_id))} - {""}
        idle = 0
        while run.status == "running":
            await asyncio.sleep(WATCH_S)
            held = run.stopping or any(self._turn_running(k) or k in getattr(self, "_active_sessions", {})
                                       or k in getattr(self, "_pending_messages", {}) for k in keys)
            idle = 0 if held else idle + 1
            if idle >= 3 and run.status == "running":
                logger.warning("[%s] run %s: the gateway no longer holds the chat; ending it", NAME, run.id)
                if run.streamed or run.final:
                    run.finish("completed", session_id=self._session_now(run))
                else:
                    run.finish("failed", error="Hermes did not take this message (the gateway may have been restarting). "
                                               "Send it again.")

    async def _bind(self, source, session_id: str) -> str:
        """Point this chat's gateway route at the app's session, so the turn lands in its history."""
        runner = self.gateway_runner
        store = getattr(runner, "session_store", None)
        if store is None:
            return self._source_session_key(source)
        entry = await asyncio.to_thread(store.get_or_create_session, source)
        if entry.session_id != session_id:
            evict = getattr(runner, "_evict_cached_agent", None)
            if callable(evict):
                evict(entry.session_key)   # a cached agent keeps writing to the session it was built for
            entry = await asyncio.to_thread(store.switch_session, entry.session_key, session_id,
                                            preserve_prompt_pin=False) or entry
        return entry.session_key

    def _session_now(self, run: Run) -> str:
        """The route's session id after the turn (compression may have continued it in a new one)."""
        try:
            entry = self.gateway_runner.session_store.lookup_by_session_key(run.session_key)
            return entry.session_id if entry else run.session_id
        except Exception:
            return run.session_id

    async def _command(self, chat_id: str, text: str) -> None:
        """Run a slash command before the turn and wait until the gateway is done with it (a message sent
        while the session is still busy would interrupt it); its reply is not shown."""
        quiet = Run("q" + uuid.uuid4().hex, chat_id, self._loop, quiet=True)
        self.runs[quiet.id] = self.chats[chat_id] = quiet
        try:
            await self.handle_message(MessageEvent(text=text, message_type=MessageType.COMMAND, source=self._source(chat_id),
                                                   message_id=quiet.id))
            await asyncio.wait_for(asyncio.shield(quiet.done), 15)
            key = self._source_session_key(self._source(chat_id))
            for _ in range(50):   # the session stays busy for a moment after the hook
                if key not in getattr(self, "_active_sessions", {}):
                    break
                await asyncio.sleep(0.1)
        except asyncio.TimeoutError:
            logger.warning("[%s] %s gave no reply in 15 s", NAME, text.split()[0])
        finally:
            self.runs.pop(quiet.id, None)

    async def _input(self, raw):
        """(text, [(image path, mime)]) from a string or an OpenAI-style content list."""
        if isinstance(raw, str):
            return raw.strip(), []
        texts, images = [], []
        for part in raw if isinstance(raw, list) else []:
            if not isinstance(part, dict):
                continue
            kind = part.get("type")
            if kind in ("text", "input_text"):
                texts.append(str(part.get("text") or ""))
            elif kind in ("image_url", "input_image"):
                ref = part.get("image_url")
                url = str((ref.get("url") if isinstance(ref, dict) else ref) or "")
                if not url.startswith("data:"):
                    continue
                head, _, b64 = url.partition(",")
                mime = head[5:].split(";")[0] or "image/jpeg"
                try:
                    data = base64.b64decode(b64, validate=False)
                except (binascii.Error, ValueError):
                    raise ValueError("bad image data")
                ext = "." + mime.split("/")[-1].replace("jpeg", "jpg")
                images.append((await asyncio.to_thread(cache_image_from_bytes, data, ext), mime))
        return "\n".join(t for t in texts if t.strip()).strip(), images

    def _run(self, request) -> Optional[Run]:
        return self.runs.get(request.match_info["rid"])

    async def _status(self, request):
        run = self._run(request)
        return web.json_response(run.info()) if run else web.json_response({"error": "unknown run"}, status=404)

    async def _events(self, request):
        run = self._run(request)
        if run is None:
            return web.json_response({"error": "unknown run"}, status=404)
        resp = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache"})
        await resp.prepare(request)
        try:
            i = max(0, int(request.headers.get("Last-Event-ID", "-1")) + 1)
        except ValueError:
            i = 0
        wake = asyncio.Event()
        run.waiters.add(wake)
        try:
            while True:
                wake.clear()
                with run.lock:
                    batch, done = run.events[i:], run.status != "running"
                    if batch:
                        # Freeze before yielding to the socket: a producer must not extend a sent seq.
                        run.exposed_seq = max(run.exposed_seq, batch[-1]["seq"])
                if batch:
                    await resp.write("".join("id: %d\ndata: %s\n\n" % (e["seq"], json.dumps(e, default=str))
                                             for e in batch).encode())
                    i += len(batch)
                    continue
                if done:
                    break
                try:
                    await asyncio.wait_for(wake.wait(), 10)
                except asyncio.TimeoutError:
                    await resp.write(b": keepalive\n\n")
        except (ConnectionResetError, asyncio.CancelledError):
            pass
        finally:
            run.waiters.discard(wake)
        return resp

    async def _stop(self, request):
        run = self._run(request)
        if run is None or run.status != "running":
            return web.json_response({"ok": True, "status": run.status if run else "unknown"})
        if not run.sent:   # still waiting for the gateway: nothing to interrupt, the message is never sent
            run.finish("cancelled")
            return web.json_response({"ok": True, "status": "cancelled"})
        run.stopping, run.mute_until = True, time.time() + MUTE_S
        run.emit("run.stopping")
        await self.handle_message(MessageEvent(text="/stop", message_type=MessageType.COMMAND,
                                               source=self._source(run.chat_id), message_id=uuid.uuid4().hex))
        self._loop.call_later(20, run.finish, "cancelled")  # the gateway reports the interrupted turn first
        return web.json_response({"ok": True, "status": "stopping"})

    async def _steer(self, request):
        run = self._run(request)
        try:
            body = await request.json()
        except Exception:
            body = {}
        text = str(body.get("text") or body.get("message") or "").strip()
        if not text:
            return web.json_response({"error": "text is required"}, status=400)
        if run is None or run.status != "running":
            return web.json_response({"ok": False, "status": run.status if run else "unknown", "pending_steer": text},
                                     status=409)
        if not run.sent:   # the gateway has not got the message yet (startup restore): send both together
            run.early.append(text)
            run.steer(text)
            return web.json_response({"ok": True, "status": "steered"})
        # A pending clarify question is answered by a plain message; otherwise /steer injects it mid-turn.
        line, run.clarify = (text if run.clarify else "/steer " + text), False
        run.mute_until = time.time() + MUTE_S
        run.steer(text)
        await self.handle_message(MessageEvent(text=line, message_type=MessageType.TEXT,
                                               source=self._source(run.chat_id), message_id=uuid.uuid4().hex))
        return web.json_response({"ok": True, "status": "steered"})

    async def _approval(self, request):
        run = self._run(request)
        try:
            body = await request.json()
        except Exception:
            body = {}
        choice = str(body.get("choice") or "").strip().lower()
        if choice not in ("once", "session", "always", "deny"):
            return web.json_response({"error": "choice must be once, session, always or deny"}, status=400)
        if run is None:
            return web.json_response({"error": "unknown run"}, status=404)
        from tools.approval import resolve_gateway_approval
        n = resolve_gateway_approval(run.approval_key or run.session_key, choice)
        run.emit("approval.resolved", choice=choice)
        return web.json_response({"ok": n > 0, "choice": choice, "resolved": n})

    async def _clarify(self, request):
        """The app answers a clarify card: a choice label, typed text, or a JSON array of labels (multi-select)."""
        run = self._run(request)
        try:
            body = await request.json()
        except Exception:
            body = {}
        qid, answer = str(body.get("request_id") or "").strip(), body.get("answer")
        if not qid or not isinstance(answer, str) or not answer.strip():
            return web.json_response({"error": "request_id and answer are required"}, status=400)
        if run is None:
            return web.json_response({"error": "unknown run"}, status=404)
        from tools.clarify_gateway import resolve_gateway_clarify
        if not resolve_gateway_clarify(qid, answer.strip()):
            run.emit("clarify.resolved", request_id=qid)
            return web.json_response({"error": "this question was already answered or has expired"}, status=409)
        run.clarify = False
        run.emit("clarify.resolved", request_id=qid)
        return web.json_response({"ok": True})

    def _gc(self) -> None:
        cutoff = time.time() - KEEP_FINISHED_S
        for rid in [k for k, r in self.runs.items() if r.status != "running" and r.finished_at < cutoff]:
            run = self.runs.pop(rid)
            if self.chats.get(run.chat_id) is run:
                del self.chats[run.chat_id]

    def _prune_runs(self) -> None:
        self._gc()
        self._gc_timer = self._loop.call_later(PRUNE_RUNS_S, self._prune_runs)

    # ── gateway → phone ──────────────────────────────────────────────────────────────────────
    def _claim(self, chat_id) -> Optional[Run]:
        """The run a send for this chat belongs to: the live one, or one that just finished."""
        run = self.chats.get(str(chat_id))
        if run is None or run.status == "running" or time.time() - run.finished_at < GRACE_S:
            return run
        return None

    async def on_processing_complete(self, event, outcome) -> None:
        run = self.runs.get((event.metadata or {}).get("companion_run") or getattr(event, "message_id", None) or "")
        if run is None:
            return
        if run.quiet:
            run.finish("completed")
            return
        # A message sent while Hermes was busy is queued or steered into the running turn and "completes" at once.
        # Keep the phone's run open, so it keeps streaming that turn, until the gateway is really done with the chat.
        # In a task: the gateway may release the session only after this hook returns.
        asyncio.create_task(self._settle(run, outcome))

    async def _settle(self, run: Run, outcome) -> None:
        while run.status == "running" and not run.stopping and self._turn_running(run.session_key):
            await asyncio.sleep(1)
        if run.status != "running":
            return
        status = {"success": "completed", "failure": "failed", "cancelled": "cancelled"}.get(
            getattr(outcome, "value", str(outcome)), "completed")
        if run.stopping:
            status = "cancelled"
        await asyncio.sleep(0.3)   # let the stream-hook queue hand over the last deltas
        run.finish(status, error="Hermes could not finish this turn" if status == "failed" else "",
                   session_id=self._session_now(run))

    def _turn_running(self, session_key: str) -> bool:
        snapshot = getattr(self.gateway_runner, "_snapshot_running_agents", None)
        try:
            return bool(session_key) and callable(snapshot) and session_key in snapshot()
        except Exception:
            return False

    async def send(self, chat_id: str, content: str, reply_to: Optional[str] = None,
                   metadata: Optional[Dict[str, Any]] = None) -> SendResult:
        mid = uuid.uuid4().hex
        run = self._claim(chat_id)
        if run is not None:
            text = _norm(content)
            if run.quiet:
                pass      # a pre-turn command's reply; its processing end (not this) releases the turn
            elif not text or text in _norm(run.streamed) or any(text in k or k in text for k in run.known):
                pass      # the consumer delivering what the phone already streamed
            elif run.status == "running" and time.time() < run.mute_until:
                pass      # the gateway acknowledging our own /steer or /stop
            elif run.status == "running":
                await asyncio.sleep(1.5)   # streamed text arrives through the hook queue; it may lag this send
                text_now = _norm(run.streamed)
                if text in text_now or any(text in k or k in text for k in run.known) or time.time() < run.mute_until:
                    pass  # already shown, or the gateway acknowledging our own /steer or /stop
                elif not run.streamed:
                    run.delta(content)   # a reply that did not stream (a command answer, streaming off)
                else:
                    run.note(content)
            else:
                await self._push(chat_id, content)
            return SendResult(success=True, message_id=mid)
        await self._push(chat_id, content)
        return SendResult(success=True, message_id=mid)

    # Files (MEDIA: tags, TTS replies, send_message attachments): the phone pulls them through the hub's
    # /media, so a send is a MEDIA: line in the live reply, the way Telegram gets an upload.
    async def _attach(self, chat_id: str, path: str, caption: Optional[str] = None) -> SendResult:
        if not path or not os.path.isfile(path):
            return SendResult(success=False, error="file not found")
        mid = uuid.uuid4().hex
        run = self._claim(chat_id)
        if run is not None and run.quiet:
            return SendResult(success=True, message_id=mid)
        if run is not None and run.status == "running":
            if any(p in run.streamed or p in run.final for p in {path, os.path.realpath(path)}):
                return SendResult(success=True, message_id=mid)   # the reply's own MEDIA: line already shows it
            try:
                ref = await asyncio.to_thread(_stable, path)
            except OSError as exc:
                return SendResult(success=False, error=str(exc))
            if caption and _norm(caption) and _norm(caption) not in _norm(run.streamed):
                run.delta(caption)
            run.attach(ref)
            return SendResult(success=True, message_id=mid)
        # Outside a live turn the phone gets a notification; the file stays on the server.
        name = os.path.basename(path)
        ok = await _notify(chat_id, (caption.strip() + "\n" if caption and caption.strip() else "") + "📎 " + name)
        return SendResult(success=ok, message_id=mid, error=None if ok else "the hub did not take the notification")

    async def send_document(self, chat_id: str, file_path: str, caption: Optional[str] = None,
                            file_name: Optional[str] = None, reply_to: Optional[str] = None,
                            metadata: Optional[Dict[str, Any]] = None, **kwargs) -> SendResult:
        return await self._attach(chat_id, file_path, caption)

    async def send_image_file(self, chat_id: str, image_path: str, caption: Optional[str] = None,
                              reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None,
                              **kwargs) -> SendResult:
        return await self._attach(chat_id, image_path, caption)

    async def send_voice(self, chat_id: str, audio_path: str, caption: Optional[str] = None,
                         reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None,
                         **kwargs) -> SendResult:
        return await self._attach(chat_id, audio_path, caption)

    async def send_video(self, chat_id: str, video_path: str, caption: Optional[str] = None,
                         reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None,
                         **kwargs) -> SendResult:
        return await self._attach(chat_id, video_path, caption)

    async def edit_message(self, chat_id: str, message_id: str, content: str, *, finalize: bool = False) -> SendResult:
        return SendResult(success=True, message_id=message_id)   # edits only grow text the phone streamed

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        return None

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        return {"name": "Hermes Companion", "type": "dm", "chat_id": chat_id}

    async def _send_exec_approval_prompt(self, prompt) -> SendResult:
        run = self._claim(prompt.chat_id)
        if run is None or run.status != "running" or run.quiet:
            return SendResult(success=False, error="no live phone run")   # gateway falls back to the text prompt
        rid = uuid.uuid4().hex
        run.approval_key = prompt.session_key
        run.emit("approval.request", request_id=rid, command=prompt.command, description=prompt.description,
                 choices=[choice for _, choice, _ in prompt.actions])
        return SendResult(success=True, message_id=rid)

    async def send_clarify(self, chat_id, question, choices, clarify_id, session_key, metadata=None) -> SendResult:
        run = self._claim(chat_id)
        if run is None or run.status != "running":
            return SendResult(success=False, error="no live phone run")
        try:
            from tools.clarify_gateway import get_pending
            multi = bool(getattr(get_pending(clarify_id), "multi_select", False))
        except Exception:
            multi = False
        run.clarify, run.clarify_id = True, str(clarify_id)   # a typed reply (steer) answers it through the gateway's text intercept
        run.emit("clarify.request", prompt_id=str(clarify_id), question=str(question or "").strip(),
                 choices=[str(c) for c in choices or []], multi_select=multi)
        return SendResult(success=True, message_id=str(clarify_id))

    async def retire_clarify_card(self, clarify_id, notice=None) -> None:
        """The gateway ends a question without an answer from the card (timeout, reset): drop the card."""
        for run in list(self.runs.values()):
            if run.clarify and run.status == "running" and run.clarify_id in ("", str(clarify_id)):
                run.clarify = False
                run.emit("clarify.resolved", request_id=str(clarify_id), notice=str(notice or ""))

    async def _push(self, chat_id: str, content: str) -> None:
        if not await _notify(chat_id, content):
            logger.warning("[%s] push to the phone failed", NAME)


async def _notify(chat_id: str, content: str) -> bool:
    """A message outside any run (cron, send_message, notices): a phone notification via the hub."""
    home = get_scoped_secret("COMPANION_HOME_CHANNEL") or ""
    note = {"type": "info", "title": "Hermes", "body": str(content)[:4000]}
    if str(chat_id) != home:
        note["session_id"] = str(chat_id)   # tapping it opens that chat; the home channel is no chat
    url = (get_scoped_secret("COMPANION_HUB_URL") or "http://127.0.0.1:8791").rstrip("/") + "/notify"
    req = urllib.request.Request(url, data=json.dumps(note).encode(), method="POST", headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + (get_scoped_secret("API_SERVER_KEY") or "")})
    try:
        await asyncio.to_thread(lambda: urllib.request.urlopen(req, timeout=10).close())
        return True
    except Exception as exc:
        logger.warning("[%s] hub /notify failed: %s", NAME, exc)
        return False


async def _standalone_send(pconfig, chat_id, message, *, thread_id=None, media_files=None, force_document=False):
    """cron / hermes send from outside the gateway process: straight to the hub."""
    names = [os.path.basename(str(f[0] if isinstance(f, (tuple, list)) else f)) for f in media_files or []]
    if names:
        message = (str(message or "").rstrip() + "\n" + "\n".join("📎 " + n for n in names)).strip()
    return {"success": True, "message_id": uuid.uuid4().hex} if await _notify(chat_id, message) else {
        "error": "the hub did not take the notification"}


# ── agent hooks: streamed text, tool cards, reasoning, the exact final reply ─────────────────────
# Text comes from on_stream_delta (token deltas, off the token path on a worker thread, in the turn's
# contextvars), not from the stream consumer's throttled message edits.
def _hook_run(session_id: str = "") -> Optional[Run]:
    try:
        from tools.approval_context import get_current_session_key
        key = get_current_session_key("")
    except Exception:
        key = ""
    for adapter in list(_ADAPTERS):
        for run in list(adapter.chats.values()):
            if run.status == "running" and not run.quiet and (
                    (key and key == run.session_key) or (session_id and session_id == run.session_id)):
                return run
    return None


def _pre_tool(tool_name="", args=None, session_id="", tool_call_id="", **_):
    run = _hook_run(session_id)
    if run is not None:
        run.emit("tool.started", tool_name=tool_name, args=args if isinstance(args, dict) else {},
                 preview=_preview(args), tool_call_id=tool_call_id,
                 **({"item_id": tool_call_id} if tool_call_id else {}))


def _post_tool(tool_name="", result=None, session_id="", tool_call_id="", duration_ms=0, status="ok", **_):
    run = _hook_run(session_id)
    if run is not None:
        run.emit("tool.failed" if status == "error" else "tool.completed", tool_name=tool_name,
                 tool_call_id=tool_call_id, duration=round((duration_ms or 0) / 1000, 2), preview=_tail(result),
                 **({"item_id": tool_call_id} if tool_call_id else {}))


def _stream_delta(delta="", kind="", session_id="", **_):
    run = _hook_run(session_id) if delta else None
    if run is None:
        return
    if kind == "reasoning":
        run.emit("reasoning.available", text=delta)
    elif kind == "text":
        if delta.startswith("\n\n"):   # the agent marks text after a tool iteration
            run.end_segment()
            delta = delta[2:]
        if delta:
            run.delta(delta)


def _interim(text="", already_streamed=False, session_id="", **_):
    run = _hook_run(session_id)
    if run is not None and text and not already_streamed:
        run.note(text)


def _post_llm(session_id="", assistant_response="", **_):
    run = _hook_run(session_id)
    if run is not None and isinstance(assistant_response, str):
        run.final = assistant_response   # device-action blocks are parsed from the exact final text


def register(ctx):
    kwargs = dict(
        name=NAME, label="Hermes Companion", adapter_factory=CompanionAdapter, check_fn=lambda: True,
        validate_config=lambda config: True, is_connected=lambda config: True,
        cron_deliver_env_var="COMPANION_HOME_CHANNEL", max_message_length=0, emoji="📱",
        standalone_sender_fn=_standalone_send,
        parse_target_ref_fn=lambda raw: (raw.strip(), None) if raw and raw.strip() and " " not in raw.strip() else None,
        platform_hint="You are talking to the owner through the Hermes Companion Android app. It renders markdown.",
    )
    from gateway.platform_registry import PlatformEntry
    known = {f.name for f in dataclasses.fields(PlatformEntry)}
    # The run API authenticates the owner with the api_server key, so there is no sender to allowlist.
    for key, value in (("trusted_inbound", True), ("display_tier", "high")):
        if key in known:
            kwargs[key] = value
    ctx.register_platform(**kwargs)
    for hook, fn in (("pre_tool_call", _pre_tool), ("post_tool_call", _post_tool),
                     ("on_stream_delta", _stream_delta), ("on_interim_message", _interim),
                     ("post_llm_call", _post_llm)):
        ctx.register_hook(hook, fn)
