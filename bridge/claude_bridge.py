"""Authenticated Hermes remote for Claude Code sessions via claude-agent-sdk."""

import asyncio
import dataclasses
import hmac
import json
import math
import os
import re
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from .codex_bridge import Run, STEER_CLOSE, STEER_OPEN, _rid, question_text, validate_answer

MODELS = ("opus", "sonnet", "haiku", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1")
EFFORTS = ("low", "medium", "high", "xhigh", "max")
MAX_TOOL_RESULT = 40_000
RESTART_ERROR = "The Claude bridge restarted while this reply was being written. What was saved is in the conversation; send your message again to continue."
PERMISSIONS = {
    "default": ("Ask before edits", "Ask for permission before changing files or running commands that need approval."),
    "acceptEdits": ("Edit automatically", "Allow file edits; ask before commands that need approval."),
    "plan": ("Plan mode", "Explore and plan without changing files or running commands that make changes."),
    "bypassPermissions": ("Bypass permissions", "Allow tools to run without permission prompts on the server."),
}
MODEL_DESCRIPTIONS = {
    "opus": "Most capable for complex work",
    "sonnet": "Balanced speed and capability",
    "haiku": "Fast for everyday tasks",
}
LIMIT_LABELS = {"five_hour": "5-hour limit", "seven_day": "Weekly limit",
                "seven_day_opus": "Weekly Opus limit", "seven_day_sonnet": "Weekly Sonnet limit"}


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def usage_limit(kind, utilization=None, resets_at=None, status=None, label=None):
    result = {"id": kind, "label": label or LIMIT_LABELS.get(kind, kind.replace("_", " ").capitalize())}
    if number(utilization):
        result["used_percent"] = max(0, min(100, utilization))
    if isinstance(resets_at, str):
        try:
            resets_at = datetime.fromisoformat(resets_at.replace("Z", "+00:00")).timestamp()
        except (ValueError, OverflowError):
            resets_at = None
    if number(resets_at):
        result["resets_at"] = int(resets_at)
    status = "warning" if status == "allowed_warning" else status
    if status in ("allowed", "warning", "rejected"):
        result["status"] = status
    return result


def sdk():
    import claude_agent_sdk
    return claude_agent_sdk


def input_blocks(value):
    """Convert Hermes content into Claude CLI user content blocks."""
    if isinstance(value, str):
        if not value.strip():
            raise ValueError("input required")
        return value
    if not isinstance(value, list) or not value:
        raise ValueError("input required")
    blocks = []
    for part in value:
        if not isinstance(part, dict):
            raise ValueError("invalid input block")
        kind = part.get("type")
        if kind in ("text", "input_text"):
            if str(part.get("text") or "").strip():  # the API rejects empty text blocks (image-only turns)
                blocks.append({"type": "text", "text": str(part.get("text"))})
        elif kind in ("image_url", "input_image"):
            ref = part.get("image_url")
            url = ref.get("url") if isinstance(ref, dict) else ref
            match = re.fullmatch(r"data:(image/(?:png|jpeg|gif|webp));base64,([A-Za-z0-9+/=]+)", url or "")
            if not match:
                raise ValueError("base64 image data URL required")
            blocks.append({"type": "image", "source": {"type": "base64", "media_type": match[1], "data": match[2]}})
        else:
            raise ValueError("unsupported input block")
    if not any(b["type"] == "text" and b["text"].strip() or b["type"] == "image" for b in blocks):
        raise ValueError("input required")
    return blocks


def prompt_text(value):
    return value if isinstance(value, str) else "\n".join(b["text"] if b["type"] == "text" else "[image]" for b in value)


HINTS = {"authentication_failed": "Claude Code is signed out or its login expired. Run claude /login on the server.",
         "billing_error": "Claude billing or plan problem. Check the account's plan and credit.",
         "rate_limit": "Claude usage or rate limit reached. Wait for it to reset, then try again.",
         "server_error": "Claude is overloaded or had a server error. Try again in a moment.",
         "context": "The conversation no longer fits in Claude's context window. Send /compact or start a new conversation."}
STATUS_KINDS = {401: "authentication_failed", 402: "billing_error", 429: "rate_limit",
                500: "server_error", 502: "server_error", 503: "server_error", 529: "server_error"}


def explain(detail, kind=None, status=None):
    """A run.failed message the phone can show: a plain hint for the known failures, plus Claude's own words."""
    detail = (detail or "").strip()
    low = detail.lower()
    kind = kind if kind in HINTS else STATUS_KINDS.get(status)
    if kind is None:
        kind = ("context" if "prompt is too long" in low or "context window" in low else
                "authentication_failed" if "invalid api key" in low or "/login" in low or "token has expired" in low else
                "rate_limit" if "rate limit" in low or "usage limit" in low or "hit your limit" in low else
                "server_error" if "overloaded" in low else None)
    hint = HINTS.get(kind, "")
    return hint + (" (" + detail + ")" if hint and detail and detail != hint else "") if hint else detail or "Claude turn failed"


def tool_of(name, args):
    args = args or {}
    if name == "Bash":
        return "terminal", {"command": args.get("command", ""), "cwd": args.get("cwd")}, args.get("command", "")[:120]
    if name in ("Edit", "Write", "MultiEdit"):
        files = [args.get("file_path", "")] if name != "MultiEdit" else [x.get("file_path", "") for x in args.get("edits", [])]
        return "patch", {"files": files}, ", ".join(Path(f).name for f in files)[:120]
    if name == "Read":
        return "read_file", args, str(args.get("file_path", ""))[:120]
    if name == "WebSearch":
        return "web_search", {"query": args.get("query", "")}, str(args.get("query", ""))[:120]
    if name == "WebFetch":
        return "web_extract", {"url": args.get("url", "")}, str(args.get("url", ""))[:120]
    if name in ("Task", "Agent"):
        return "delegate_task", args, str(args.get("prompt", ""))[:120]
    return name, args, name


def entry_times(path):
    """uuid -> epoch seconds from the transcript; SessionMessage carries no time, so the phone showed "now"."""
    times = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                if '"timestamp"' not in line:
                    continue
                try:
                    e = json.loads(line)
                    times[e["uuid"]] = int(datetime.fromisoformat(e["timestamp"].replace("Z", "+00:00")).timestamp())
                except (ValueError, KeyError, TypeError, AttributeError):
                    continue
    except OSError:
        pass
    return times


def queued_steers(path, known):
    """Messages queued into a running turn (phone steer or typed in the terminal): {uuid shown before: [text]}.

    Claude Code stores them as `queued_command` attachments, which get_session_messages leaves out.
    """
    parents, steers, discarded = {}, {}, set()
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except OSError:
        return {}
    for line in lines:
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("uuid"):
            parents[e["uuid"]] = e.get("parentUuid")
            if e.get("type") in ("user", "assistant") and e["uuid"] not in known:
                discarded.add(e["uuid"])
        a = e.get("attachment") or {}
        if e.get("type") == "attachment" and a.get("type") == "queued_command" and isinstance(a.get("prompt"), str):
            anchor = e.get("parentUuid")
            seen = set()
            while anchor and anchor not in known:  # walk up past other attachments to a shown entry
                if anchor in discarded or anchor in seen:
                    anchor = None
                    break
                seen.add(anchor)
                anchor = parents.get(anchor)
            if anchor:
                steers.setdefault(anchor, []).append(a["prompt"])
    return steers


def transcript_text(text):
    """Classify Claude's synthetic user text before exposing it to the phone."""
    value = text.strip()
    if value.startswith(("This session is being continued from a previous conversation",
                         "This conversation is being continued from a previous conversation")):
        return "compact", "Compacted"
    if value.startswith(("<command-name>", "<command-message>", "<local-command-caveat>")):
        command = re.search(r"<command-name>(.*?)</command-name>", value, re.S)
        if command:
            name = command.group(1).strip()
            args = re.search(r"<command-args>(.*?)</command-args>", value, re.S)
            output = re.search(r"<local-command-(stdout|stderr)>(.*?)</local-command-\1>", value, re.S)
            if name == "/compact":
                return "compact", "Compacted"
            return "command", name + (" " + args.group(1).strip() if args and args.group(1).strip() else "") + (
                "\n" + output.group(2).strip() if output and output.group(2).strip() else "")
        return "hidden", ""
    if value.startswith(("<local-command-stdout>", "<local-command-stderr>")):
        output = re.fullmatch(r"<local-command-(stdout|stderr)>(.*?)</local-command-\1>", value, re.S)
        return "output", output.group(2).strip() if output else ""
    if value.startswith(("<task-notification>", "<system-reminder>", "<session-start-hook>",
                         "<ide_opened_file>", "<ide_selection>", "<tick>", "<goal>")):
        return "hidden", ""
    return "text", text


def preview_text(text):
    kind, value = transcript_text(text or "")
    # The SDK extracts bare command names as first_prompt/summary metadata.
    return value if kind == "text" and not re.match(r"^/[\w-]+(?:\s|$)", value.lstrip()) else ""


def transcript_rows(messages, steers=(), queued=None, stamps=None):
    rows, pending, reasoning, users = [], {}, "", 0
    queued = queued or {}

    def steer_rows(anchor):
        for n, text in enumerate(queued.get(anchor, [])):
            rows.append({"id": _rid(str(anchor), "steer", str(n)), "role": "user", "timestamp": 0,
                         "content": STEER_OPEN + "\n" + text + "\n" + STEER_CLOSE})

    for index, entry in enumerate(messages):
        if index:
            steer_rows(messages[index - 1].uuid)  # a queued message shows right after the entry it followed
        message = entry.message or {}
        if not isinstance(message, dict):
            continue
        blocks = message.get("content") or []
        if isinstance(blocks, str):
            blocks = [{"type": "text", "text": blocks}]
        stamp = message.get("timestamp") or (stamps or {}).get(entry.uuid, 0)
        eid = entry.uuid or str(index)
        if entry.type == "user" and not any(b.get("type") == "tool_result" for b in blocks if isinstance(b, dict)):
            text = "\n".join(b.get("text", "") for b in blocks if isinstance(b, dict) and b.get("type") == "text")
            kind, value = transcript_text(text)
            if message.get("isCompactSummary") or getattr(entry, "isCompactSummary", False):
                kind, value = "compact", "Compacted"
            if message.get("isMeta") or getattr(entry, "isMeta", False):
                continue
            if kind != "text":
                previous = rows[-1] if rows else {}
                if kind == "output" and previous.get("role") == "system":
                    if previous.get("content") != "Compacted" and value:
                        previous["content"] += "\n" + value
                elif value and not (kind == "compact" and previous.get("role") == "system" and previous.get("content") == value):
                    rows.append({"id": _rid(eid), "role": "system", "content": value, "timestamp": stamp})
                continue
            content = []
            for b in blocks:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "text":
                    content.append({"type": "text", "text": b.get("text", "")})
                elif b.get("type") == "image":
                    source = b.get("source") or {}
                    if source.get("type") == "base64":
                        content.append({"type": "image_url", "image_url": "data:" + source.get("media_type", "image/png") + ";base64," + source.get("data", "")})
            if content:
                if len(content) == 1 and content[0]["type"] == "text":
                    value = content[0]["text"]
                    if value in steers:
                        value = STEER_OPEN + "\n" + value + "\n" + STEER_CLOSE
                else:
                    value = content
                rows.append({"id": _rid(eid), "role": "user", "content": value, "timestamp": stamp})
                users += 1
            continue
        for j, block in enumerate(blocks):
            if not isinstance(block, dict):
                continue
            kind = block.get("type")
            key = eid + ":" + str(j)
            if entry.type == "assistant" and kind == "thinking":
                reasoning += block.get("thinking", "")
            elif entry.type == "assistant" and kind == "text":
                rows.append({"id": _rid(key), "role": "assistant", "content": block.get("text", ""),
                             "reasoning": reasoning, "timestamp": stamp})
                reasoning = ""
            elif entry.type == "assistant" and kind in ("tool_use", "server_tool_use"):
                call_id = block.get("id") or key
                name, args, _ = tool_of(block.get("name", "tool"), block.get("input"))
                pending[call_id] = name
                rows.append({"id": _rid(key), "role": "assistant", "content": "", "timestamp": stamp,
                             "reasoning": reasoning, "tool_calls": [{"id": call_id, "function":
                             {"name": name, "arguments": json.dumps(args)}}]})
                reasoning = ""
            elif entry.type == "user" and kind == "tool_result":
                call_id = block.get("tool_use_id", "")
                content = block.get("content") or ""
                if not isinstance(content, str):
                    content = json.dumps(content)
                if len(content) > MAX_TOOL_RESULT:  # keep the tail: errors and summaries come last
                    content = "[truncated " + str(len(content) - MAX_TOOL_RESULT) + " chars]\n" + content[-MAX_TOOL_RESULT:]
                rows.append({"id": _rid(key), "role": "tool", "tool_call_id": call_id,
                             "tool_name": pending.get(call_id, "tool"), "content": content, "timestamp": stamp})
    if messages:
        steer_rows(messages[-1].uuid)
    return rows


class Store:
    """Phone-only metadata; Claude Code owns transcripts and session names."""

    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self.db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, title TEXT, "
                       "updated REAL, pinned INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, "
                       "unread INTEGER DEFAULT 0, resumed INTEGER DEFAULT 0, model TEXT DEFAULT '', "
                       "reasoning_effort TEXT DEFAULT '', permission_mode TEXT DEFAULT '', steers TEXT DEFAULT '[]')")
            cols = {r[1] for r in db.execute("PRAGMA table_info(sessions)")}
            for col in ("model", "reasoning_effort", "permission_mode", "steers", "usage", "rewind_at", "rewind_drop"):
                if col not in cols:
                    default = "[]" if col == "steers" else "{}" if col == "usage" else ""
                    db.execute(f"ALTER TABLE sessions ADD COLUMN {col} TEXT DEFAULT '{default}'")
            db.execute("DROP TABLE IF EXISTS messages")
            db.execute("CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, session_id TEXT, status TEXT, "
                       "error TEXT DEFAULT '', updated REAL)")
        self.path.chmod(0o600)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def get(self, session_id):
        with self.db() as db:
            row = db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        return dict(row) if row else {}

    def all(self):
        with self.db() as db:
            return {r["id"]: dict(r) for r in db.execute("SELECT * FROM sessions")}

    def patch(self, session_id, changes):
        allowed = {"title", "pinned", "archived", "unread", "resumed", "model", "reasoning_effort", "permission_mode", "steers", "usage", "rewind_at", "rewind_drop"}
        changes = {k: v for k, v in changes.items() if k in allowed}
        if not changes:
            return
        with self.db() as db:
            db.execute("INSERT OR IGNORE INTO sessions(id,updated) VALUES(?,?)", (session_id, time.time()))
            db.execute("UPDATE sessions SET " + ",".join(k + "=?" for k in changes) + " WHERE id=?",
                       (*changes.values(), session_id))

    def run_state(self, run_id, session_id, status, error=""):
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO runs(id,session_id,status,error,updated) VALUES(?,?,?,?,?)",
                       (run_id, session_id, status, error, time.time()))

    def get_run(self, run_id):
        with self.db() as db:
            row = db.execute("SELECT * FROM runs WHERE id=?", (run_id,)).fetchone()
        return dict(row) if row else {}

    def fail_orphans(self):
        """At service start no run can still be going: report the ones the restart cut off, forget old ones."""
        with self.db() as db:
            db.execute("UPDATE runs SET status='failed', error=?, updated=? WHERE status='running'",
                       (RESTART_ERROR, time.time()))
            db.execute("DELETE FROM runs WHERE updated<?", (time.time() - 7 * 86400,))

    def create(self, title=""):
        session_id = str(uuid.uuid4())
        self.patch(session_id, {"title": title})
        return session_id

    def delete(self, session_id):
        with self.db() as db:
            db.execute("DELETE FROM sessions WHERE id=?", (session_id,))
            db.execute("DELETE FROM runs WHERE session_id=?", (session_id,))


class ClaudeRun(Run):
    def __init__(self, run_id, session_id):
        super().__init__(run_id, session_id)
        self.client = self.loop = None
        self.cancelled = False
        self.ready = threading.Event()
        self.pending_steer = ""
        self.steers = []
        self.tool_names = {}
        self.usage_probe = threading.Lock()

    def withdraw_questions(self):
        with self.cond:
            for future, _ in self.questions.values():
                try:
                    self.loop.call_soon_threadsafe(lambda f=future: not f.done() and f.set_result(None))
                except (AttributeError, RuntimeError):
                    pass  # no loop yet, or the turn already ended and closed it
            super().withdraw_questions()


class Bridge:
    def __init__(self, store, token, cwd, client_factory=None, sdk_module=None, approval_timeout=600, stop_grace=20):
        if not token:
            raise ValueError("CLAUDE_BRIDGE_TOKEN is required")
        self.store, self.token, self.cwd = store, token, Path(cwd)
        if not self.cwd.is_dir():
            raise ValueError("CLAUDE_BRIDGE_CWD must name an existing directory")
        self.sdk = sdk_module or sdk()
        self.client_factory = client_factory or self.sdk.ClaudeSDKClient
        self.approval_timeout, self.stop_grace = approval_timeout, stop_grace
        self.runs, self.by_session = {}, {}
        self.lock = threading.RLock()
        self.undoing = set()  # sessions whose files are being rewound; the global lock is not held meanwhile
        self.limits = {}
        self.limits_as_of = None
        self.plan = None

    def session(self, info=None, session_id=None, meta=None):
        sid = session_id or info.session_id
        meta = self.store.get(sid) if meta is None else meta
        if info is None and not meta:
            raise KeyError("session not found")
        run = self.by_session.get(sid)
        active = run if run and run.status == "running" else None
        candidates = [info.custom_title, meta.get("title"), info.summary, info.first_prompt] if info else [meta.get("title")]
        title = next((preview_text(t) for t in candidates if preview_text(t)), "")
        preview = preview_text(info.first_prompt) if info else ""
        if info and ((info.first_prompt and not preview) or not title):
            # Only inspect history when SDK metadata is synthetic; ordinary list rows stay cheap.
            rows = transcript_rows(self.sdk.get_session_messages(sid, directory=str(self.cwd)))
            texts = [preview_text(r["content"]) for r in rows if r["role"] in ("user", "assistant")
                     and isinstance(r.get("content"), str)]
            texts = [t for t in texts if t]
            preview = texts[-1] if texts else ""
            title = title or (texts[0][:80] if texts else "")
        return {"id": sid, "title": title, "source": "claude", "model": meta.get("model") or "",
                "has_model_config": bool(meta.get("model")), "preview": preview[:160],
                "message_count": 0, "tool_call_count": 0,
                "last_active": (info.last_modified / 1000 if info and info.last_modified > 1e11 else info.last_modified) if info else meta.get("updated", 0),
                "pinned": bool(meta.get("pinned")), "archived": bool(meta.get("archived")),
                "unread": bool(meta.get("unread")), "active_run_id": active.id if active else "",
                "active_run_input": active.user_text if active else ""}

    def find(self, sid):
        info = self.sdk.get_session_info(sid, directory=str(self.cwd))
        return self.session(info, sid) if info or self.store.get(sid) else None

    def sessions(self, limit, offset, title=None):
        infos = self.sdk.list_sessions(directory=str(self.cwd))
        known = {i.session_id for i in infos}
        metas = self.store.all()
        items = [self.session(i, meta=metas.get(i.session_id, {})) for i in infos]
        items += [self.session(session_id=sid, meta=m) for sid, m in metas.items() if sid not in known]
        if title is not None:
            items = [s for s in items if s["title"] == title]
        items.sort(key=lambda s: (s["pinned"], s["last_active"]), reverse=True)
        return items[offset:offset + limit]

    def messages(self, sid, limit, offset):
        messages = self.history(sid)
        steers = json.loads(self.store.get(sid).get("steers") or "[]")
        projects = Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude") / "projects"
        path = projects / self.sdk.project_key_for_directory(str(self.cwd)) / (sid + ".jsonl")
        queued = queued_steers(path, {m.uuid for m in messages})
        rows = transcript_rows(messages, [t for t in steers if all(t not in q for q in queued.values())], queued,
                               entry_times(path))
        end = len(rows) - offset
        return rows[max(0, end - limit):max(0, end)]

    def history(self, sid):
        messages = self.sdk.get_session_messages(sid, directory=str(self.cwd))
        meta = self.store.get(sid)
        # Until the next query writes the new branch, the SDK still reports the old leaf.
        if meta.get("rewind_drop"):
            for i, message in enumerate(messages):
                if message.uuid == meta["rewind_drop"]:
                    return messages[:i]
        return messages

    async def _idle_control(self, sid, operation, checkpoint=None):
        existing = self.sdk.get_session_info(sid, directory=str(self.cwd))
        options = self.sdk.ClaudeAgentOptions(
            cwd=str(self.cwd), resume=sid if existing else None,
            enable_file_checkpointing=True, extra_args={"replay-user-messages": None},
            setting_sources=["user", "project", "local"])
        client = self.client_factory(options)
        try:
            await client.connect()
            if operation == "rewind":
                return await client.rewind_files(checkpoint)
            return await client.get_mcp_status()
        finally:
            await client.disconnect()

    def command(self, sid, command):
        parts = command.split()
        if not parts or not parts[0].startswith("/"):
            raise ValueError("command required")
        if parts[0] == "/undo":
            if len(parts) > 2 or len(parts) == 2 and not re.fullmatch(r"[1-9][0-9]{0,5}", parts[1]):
                raise ValueError("Use /undo [N], where N is a positive number of turns")
            count = int(parts[1]) if len(parts) == 2 else 1
            with self.lock:
                run = self.by_session.get(sid)
                if run and run.status == "running":
                    raise RuntimeError("Wait for the current run to finish before undoing turns")
                if sid in self.undoing:
                    raise RuntimeError("An undo is already running for this conversation")
                messages = self.history(sid)
                turns = [i for i, m in enumerate(messages)
                         if any(row["role"] == "user" for row in transcript_rows([m]))]
                if not turns:
                    raise ValueError("Nothing to undo: this conversation has no user turns")
                if count > len(turns):
                    raise ValueError(f"Only {len(turns)} user turn(s) can be undone")
                index = turns[-count]
                checkpoint = messages[index].uuid
                # Like Claude Code's rewind: the undone prompt goes back into the composer.
                text = next((r["content"] for r in transcript_rows([messages[index]]) if r["role"] == "user"), "")
                self.undoing.add(sid)
            try:
                # Do not change the conversation unless the SDK successfully restores its files.
                asyncio.run(asyncio.wait_for(self._idle_control(sid, "rewind", checkpoint), timeout=45))
                with self.lock:
                    if index:
                        self.store.patch(sid, {"rewind_at": messages[index - 1].uuid, "rewind_drop": checkpoint,
                                               "steers": "[]", "usage": "{}"})
                    else:
                        # There is no earlier message UUID to resume at. Reset this empty session
                        # through the SDK, keeping the phone's ID and settings for its next turn.
                        self.sdk.delete_session(sid, directory=str(self.cwd))
                        self.store.patch(sid, {"rewind_at": "", "rewind_drop": "", "resumed": 0,
                                               "steers": "[]", "usage": "{}"})
            finally:
                with self.lock:
                    self.undoing.discard(sid)
            notice = f"Undid the last {count} {'turn' if count == 1 else 'turns'}. Restored checkpointed files."
            return {"type": "undo", "notice": notice, "text": text if isinstance(text, str) else ""}
        if parts[0] == "/mcp":
            if len(parts) != 1:
                raise ValueError("Use /mcp to show MCP server status")
            with self.lock:
                run = self.by_session.get(sid)
                active = run and run.status == "running" and run.client and run.loop
                if not active:
                    status = asyncio.run(asyncio.wait_for(self._idle_control(sid, "mcp"), timeout=45))
            if active:
                status = asyncio.run_coroutine_threadsafe(run.client.get_mcp_status(), run.loop).result(timeout=15)
            status = dataclasses.asdict(status) if dataclasses.is_dataclass(status) else status
            servers = status.get("mcpServers", [])
            output = "\n".join(f"{s['name']}: {s['status']}" for s in servers) or "No MCP servers configured."
            return {"type": "output", "output": output}
        return {"type": "send", "message": command}

    def settings(self, sid, changes=None):
        applies = "next_turn"
        if changes is not None:
            if not changes or set(changes) - {"reasoning_effort", "permission_mode"}:
                raise ValueError("reasoning_effort or permission_mode required")
            for key, allowed in (("reasoning_effort", EFFORTS), ("permission_mode", PERMISSIONS)):
                if key in changes and changes[key] not in allowed:
                    raise ValueError("Unsupported " + key)
            run = self.by_session.get(sid)
            if "permission_mode" in changes and run and run.status == "running" and run.client and run.ready.is_set():
                asyncio.run_coroutine_threadsafe(run.client.set_permission_mode(changes["permission_mode"]),
                                                run.loop).result(timeout=10)
                applies = "now"
            self.store.patch(sid, changes)
        meta = self.store.get(sid)
        return {"reasoning_effort": meta.get("reasoning_effort") or "high",
                "reasoning_options": [{"value": x, "label": x.capitalize(), "description": ""} for x in EFFORTS],
                "permission_mode": meta.get("permission_mode") or "default",
                "permission_options": [{"value": x, "label": label, "description": description}
                                       for x, (label, description) in PERMISSIONS.items()],
                "applies": applies,
                "scope": "Permission changes apply now; effort changes apply to your next message." if applies == "now"
                else "Changes apply to your next message in this conversation."}

    def usage(self, sid, refresh=False):
        run = self.by_session.get(sid)
        if refresh and run and run.status == "running" and run.client and run.ready.is_set():
            asyncio.run_coroutine_threadsafe(self._refresh_usage(run), run.loop).result(timeout=12)
            run.emit("usage.updated", **self.usage(sid))
        with self.lock:
            result = json.loads(self.store.get(sid).get("usage") or "{}")
            if self.limits_as_of is not None:
                order = list(LIMIT_LABELS) + [k for k in self.limits if k not in LIMIT_LABELS]
                result["limits"] = [self.limits[k] for k in order if k in self.limits]
                result["as_of"] = max(result.get("as_of", 0), self.limits_as_of)
            if self.plan is not None:
                result["plan"] = self.plan
        return result

    def _result_usage(self, run, message):
        with self.lock:
            usage = json.loads(self.store.get(run.thread_id).get("usage") or "{}")
            session = usage.get("session", {})
            native = message.usage or {}
            # Anthropic counts cache reads and writes outside input_tokens; the phone shows the whole prompt as input.
            parts = [native.get(k) for k in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")]
            if any(number(v) and v >= 0 for v in parts):
                session["input_tokens"] = session.get("input_tokens", 0) + sum(v for v in parts if number(v) and v >= 0)
            for key, wire in (("output_tokens", "output_tokens"), ("cache_read_input_tokens", "cached_input_tokens")):
                value = native.get(key)
                if number(value) and value >= 0:
                    session[wire] = session.get(wire, 0) + value
            if number(message.total_cost_usd) and message.total_cost_usd >= 0:
                # Resume cost semantics need a real-turn check before summing process totals.
                session.update(cost_usd=message.total_cost_usd, cost_label="API-equivalent")
            if session:
                usage["session"] = session
                usage["as_of"] = int(time.time())
                self.store.patch(run.thread_id, {"usage": json.dumps(usage)})

    async def _refresh_usage(self, run):
        # Reuse the connected client only; idle refresh must not start a CLI process.
        if not run.usage_probe.acquire(blocking=False):
            return
        try:
            try:
                native = await asyncio.wait_for(run.client.get_context_usage(), timeout=5)
                context = {wire: native[key] for key, wire in (
                    ("totalTokens", "used_tokens"), ("rawMaxTokens", "window_tokens"),
                    ("percentage", "percent"), ("autoCompactThreshold", "auto_compact_at"))
                    if number(native.get(key)) and native[key] >= 0}
                if context:
                    if "percent" in context:
                        context["percent"] = max(0, min(100, context["percent"]))
                    context["source"] = "last_turn"
                    with self.lock:
                        usage = json.loads(self.store.get(run.thread_id).get("usage") or "{}")
                        usage.update(context=context, as_of=int(time.time()))
                        self.store.patch(run.thread_id, {"usage": json.dumps(usage)})
            except Exception:
                pass  # Optional SDK sources vary by CLI version and account.
            try:
                native = await asyncio.wait_for(run.client._query._send_control_request(
                    {"subtype": "get_usage", "skip_behaviors": True}), timeout=5)
                limits = native.get("rate_limits")
                if isinstance(limits, dict):
                    mapped = {}
                    for kind in LIMIT_LABELS:
                        row = limits.get(kind)
                        if isinstance(row, dict):
                            mapped[kind] = usage_limit(kind, row.get("utilization"), row.get("resets_at"))
                    # Installed CLI schema: model_scoped is nested under rate_limits.
                    for row in limits.get("model_scoped") or []:
                        if isinstance(row, dict) and isinstance(row.get("display_name"), str):
                            name = row["display_name"]
                            kind = "model:" + name
                            mapped[kind] = usage_limit(kind, row.get("utilization"), row.get("resets_at"),
                                                       label="Weekly " + name + " limit")
                    with self.lock:
                        for kind, row in mapped.items():
                            self.limits[kind] = {**self.limits.get(kind, {}), **row}
                        self.limits_as_of = int(time.time())
                if isinstance(native.get("subscription_type"), str):
                    with self.lock:
                        self.plan = native["subscription_type"]
            except Exception:
                pass  # Private get_usage is experimental and can be unsupported.
        finally:
            run.usage_probe.release()

    def _rate_limit(self, run, info):
        if not info.rate_limit_type:
            return
        limit = usage_limit(info.rate_limit_type,
                            info.utilization * 100 if number(info.utilization) else None,
                            info.resets_at, info.status)
        with self.lock:
            self.limits[info.rate_limit_type] = limit
            self.limits_as_of = int(time.time())
        run.emit("usage.updated", **self.usage(run.thread_id))

    def start(self, sid, value, instructions=""):
        if self.find(sid) is None:
            raise KeyError("session not found")
        content = input_blocks(value)
        with self.lock:
            prior = self.by_session.get(sid)
            if prior and prior.status == "running":
                raise RuntimeError("session already has an active turn")
            if sid in self.undoing:
                raise RuntimeError("Undo in progress: send again in a moment")
            run = ClaudeRun(str(uuid.uuid4()), sid)
            run.user_text = prompt_text(content)
            self.runs[run.id] = self.by_session[sid] = run
            for old in [r for r in self.runs.values() if r.finished_at and r.finished_at < time.time() - 3600]:
                self.runs.pop(old.id, None)
                if self.by_session.get(old.thread_id) is old:  # else the run's whole event log stays referenced
                    del self.by_session[old.thread_id]
            self.store.run_state(run.id, sid, "running")
        threading.Thread(target=lambda: asyncio.run(self._execute(run, content, instructions)), daemon=True).start()
        return run

    async def _approval(self, run, name, args, context):
        if name == "AskUserQuestion":
            return await self._ask_questions(run, args)
        if name in run.session_allowed and name != "ExitPlanMode":
            return self.sdk.PermissionResultAllow()
        rid = str(uuid.uuid4())
        future = asyncio.get_running_loop().create_future()
        run.approvals[rid] = (future, name, args)
        command = next((args[k] for k in ("command", "file_path", "url", "query", "pattern") if args.get(k)), name)
        run.emit("approval.request", request_id=rid, command=str(command),
                 description=(args.get("plan") or "") if name == "ExitPlanMode" else context.description or context.title or "",
                 choices=["once", "session", "deny"])
        try:
            choice = await asyncio.wait_for(future, self.approval_timeout)
        except asyncio.TimeoutError:
            choice = "timeout"
        finally:
            run.approvals.pop(rid, None)
        run.emit("approval.resolved", request_id=rid)
        if choice == "timeout":
            return self.sdk.PermissionResultDeny(message="Nobody approved this within " + str(int(self.approval_timeout)) + " seconds")
        if choice == "deny":
            return self.sdk.PermissionResultDeny(message="Denied by user")
        if name == "ExitPlanMode":
            await run.client.set_permission_mode("acceptEdits")
            self.store.patch(run.thread_id, {"permission_mode": "acceptEdits"})
            return self.sdk.PermissionResultAllow()
        if choice == "session":
            run.session_allowed.add(name)
            from claude_agent_sdk.types import PermissionRuleValue
            rule = PermissionRuleValue(tool_name=name)
            update = self.sdk.PermissionUpdate(type="addRules", rules=[rule], behavior="allow", destination="session")
            return self.sdk.PermissionResultAllow(updated_permissions=[update])
        return self.sdk.PermissionResultAllow()

    async def _ask_questions(self, run, args):
        questions = args.get("questions")
        try:
            if not isinstance(questions, list) or not questions:
                raise ValueError("questions required")
            for question in questions:
                if not isinstance(question, dict):
                    raise ValueError("invalid question")
                question_text(question)
            if len({q["question"] for q in questions}) != len(questions):
                raise ValueError("unique question text required")
        except ValueError as exc:
            return self.sdk.PermissionResultDeny(message=str(exc))
        answers = {}
        for question in questions:
            rid = str(uuid.uuid4())
            future = asyncio.get_running_loop().create_future()
            with run.cond:
                if run.cancelled or run.status != "running":
                    return self.sdk.PermissionResultDeny(message="Question withdrawn")
                run.questions[rid] = (future, bool(question.get("multiSelect")))
                text, choices = question_text(question)
                run.emit("clarify.request", prompt_id=rid, question=text, choices=choices,
                         multi_select=bool(question.get("multiSelect")))
            try:
                answer = await asyncio.wait_for(future, self.approval_timeout)
            except asyncio.TimeoutError:
                return self.sdk.PermissionResultDeny(message="Nobody answered this within " + str(int(self.approval_timeout)) + " seconds")
            finally:
                with run.cond:
                    if run.questions.pop(rid, None) is not None:
                        run.emit("clarify.resolved", request_id=rid)
            if answer is None or run.cancelled or run.status != "running":
                return self.sdk.PermissionResultDeny(message="Question withdrawn")
            answers[question["question"]] = answer
        # Installed CLI AskUserQuestion schema: answers maps question text to strings;
        # its multi-select coercion joins labels with ", ". SDK query.py sends updated_input as updatedInput.
        return self.sdk.PermissionResultAllow(updated_input={**args, "answers": answers})

    def clarify(self, run, request_id, answer):
        async def resolve():
            with run.cond:
                if run.status != "running" or run.cancelled:
                    raise RuntimeError("run already finished or stopping")
                validate_answer(request_id, answer)
                entry = run.questions.get(request_id)
                if entry is None or entry[0].done():
                    raise KeyError("no pending question")
                future, multi = entry
                value = answer.strip()
                if multi and value.startswith("["):
                    labels = json.loads(value)
                    if not isinstance(labels, list) or not labels or not all(isinstance(v, str) and v.strip() for v in labels):
                        raise ValueError("multi-select answer must be a nonempty array of strings")
                    value = ", ".join(labels)
                future.set_result(value)
        if run.status != "running" or run.cancelled:
            raise RuntimeError("run already finished or stopping")
        validate_answer(request_id, answer)
        if not run.loop:
            raise KeyError("no pending question")
        asyncio.run_coroutine_threadsafe(resolve(), run.loop).result(timeout=10)

    async def _execute(self, run, content, instructions):
        s = self.sdk
        run.loop, run.task = asyncio.get_running_loop(), asyncio.current_task()
        run.session_allowed = set()
        run.emit("run.started")
        try:  # everything that can raise stays inside, or the run would never leave "running"
            meta = self.store.get(run.thread_id)
            existing = self.sdk.get_session_info(run.thread_id, directory=str(self.cwd))
            resume_at = meta.get("rewind_at") or None
            if resume_at and all(m.uuid != meta.get("rewind_drop") for m in
                                 self.sdk.get_session_messages(run.thread_id, directory=str(self.cwd))):
                # A crash after writing the new branch must not discard that branch again.
                resume_at = None
            options = s.ClaudeAgentOptions(cwd=str(self.cwd), session_id=None if existing else run.thread_id,
                                           resume=run.thread_id if existing else None,
                                           resume_session_at=resume_at,
                                           enable_file_checkpointing=True,
                                           extra_args={"replay-user-messages": None},
                                           include_partial_messages=True, model=meta.get("model") or None,
                                           effort=meta.get("reasoning_effort") or None,
                                           permission_mode=meta.get("permission_mode") or "default",
                                           # Claude Code's own prompt stays; the phone's context is appended to it.
                                           system_prompt={"type": "preset", "preset": "claude_code", "append": instructions} if instructions
                                           else {"type": "preset", "preset": "claude_code"},
                                           setting_sources=["user", "project", "local"],  # CLAUDE.md, skills, MCP, hooks as in the terminal
                                           can_use_tool=lambda n, a, c: self._approval(run, n, a, c))
            client = run.client = self.client_factory(options)
            await client.connect()
            if run.cancelled:
                await client.interrupt()
                run.finish("cancelled")
                return
            prompt = content if isinstance(content, str) else self._content_stream(content)
            await client.query(prompt)
            run.ready.set()
            current, text, thinking_seen, result_seen, api_error = None, "", "", False, None
            async for message in client.receive_response():
                if isinstance(message, s.SystemMessage) and message.subtype == "init":
                    self.store.patch(run.thread_id, {"resumed": 1})
                elif isinstance(message, getattr(s, "RateLimitEvent", ())):
                    self._rate_limit(run, message.rate_limit_info)
                elif isinstance(message, s.StreamEvent):
                    event = message.event
                    delta = event.get("delta") or {}
                    if event.get("type") == "message_start":
                        current = (event.get("message") or {}).get("id") or message.uuid
                        thinking_seen = ""
                    elif event.get("type") == "content_block_delta" and delta.get("type") == "text_delta":
                        chunk = delta.get("text", "")
                        run.message(current or message.uuid, chunk)
                    elif event.get("type") == "content_block_delta" and delta.get("type") == "thinking_delta":
                        chunk = delta.get("thinking", "")
                        thinking_seen += chunk
                        run.emit("reasoning.available", text=chunk)
                elif isinstance(message, s.AssistantMessage):
                    api_error = message.error or api_error
                    message_id = message.message_id or message.uuid or str(uuid.uuid4())
                    full = "\n".join(b.text for b in message.content if isinstance(b, s.TextBlock))
                    if full:
                        run.message(current or message_id, full, completed=True)
                        text = full
                    for block in message.content:
                        if isinstance(block, s.ThinkingBlock) and block.thinking:
                            remaining = block.thinking[len(thinking_seen):] if block.thinking.startswith(thinking_seen) else block.thinking
                            if remaining:
                                run.emit("reasoning.available", text=remaining)
                        elif isinstance(block, s.ToolUseBlock):
                            name, args, preview = tool_of(block.name, block.input)
                            run.tool_names[block.id] = name
                            run.emit("tool.started", tool_name=name, item_id=block.id, args=args, preview=preview)
                elif isinstance(message, s.UserMessage):
                    blocks = message.content if isinstance(message.content, list) else []
                    for block in blocks:
                        if isinstance(block, s.ToolResultBlock):
                            raw = block.content or ""
                            preview = raw if isinstance(raw, str) else json.dumps(raw)
                            run.emit("tool.failed" if block.is_error else "tool.completed",
                                     tool_name=run.tool_names.get(block.tool_use_id, "tool"), item_id=block.tool_use_id,
                                     args={}, preview=preview[-300:])
                elif isinstance(message, s.ResultMessage):
                    result_seen = True
                    self._result_usage(run, message)
                    if not run.cancelled:
                        await self._refresh_usage(run)
                    run.emit("usage.updated", **self.usage(run.thread_id))
                    if message.is_error:
                        raise RuntimeError(explain(message.result or "; ".join(message.errors or []), api_error,
                                                   message.api_error_status))
                    if message.result and not text:
                        run.message("result", message.result, completed=True)
            if not result_seen:
                raise RuntimeError("Claude turn ended without result")
            if not existing and meta.get("title") and meta["title"].lower() not in ("new conversation", "new chat", "mobile"):
                try:
                    self.sdk.rename_session(run.thread_id, meta["title"], directory=str(self.cwd))
                except (OSError, ValueError):
                    pass  # The phone title remains in metadata if the SDK transcript is not ready yet.
            run.finish("cancelled" if run.cancelled else "completed")
        except asyncio.CancelledError:  # stop() gave up waiting for the interrupt to end the turn
            run.finish("cancelled")
        except Exception as exc:
            run.finish("cancelled" if run.cancelled else "failed",
                       "" if run.cancelled else explain(str(exc) or type(exc).__name__))
        finally:
            # Once a query has written a new branch, normal resumes follow its new leaf.
            try:
                meta = self.store.get(run.thread_id)
                if meta.get("rewind_drop"):
                    messages = self.sdk.get_session_messages(run.thread_id, directory=str(self.cwd))
                    if messages and all(m.uuid != meta["rewind_drop"] for m in messages):
                        self.store.patch(run.thread_id, {"rewind_at": "", "rewind_drop": ""})
            except Exception:
                pass  # Preserve the rewind target if the transcript cannot yet be read.
            run.ready.set()
            try:
                self.store.run_state(run.id, run.thread_id, run.status, run.error)
            except Exception:
                pass
            if run.client:
                try:
                    await run.client.disconnect()
                except Exception:
                    pass
            run.client = None

    @staticmethod
    async def _content_stream(content):
        yield {"type": "user", "message": {"role": "user", "content": content}, "parent_tool_use_id": None,
               "session_id": "default"}

    def steer(self, run, text):
        if run.status == "running" and not run.ready.is_set():
            run.ready.wait(10)
        if run.status != "running" or not run.loop or not run.client:
            run.pending_steer = text
            return "pending_steer"
        try:
            asyncio.run_coroutine_threadsafe(run.client.query(text), run.loop).result(timeout=10)
        except Exception:
            if run.status != "running":
                run.pending_steer = text
                return "pending_steer"
            raise
        run.steers.append(text)
        steers = json.loads(self.store.get(run.thread_id).get("steers") or "[]")
        self.store.patch(run.thread_id, {"steers": json.dumps(steers + [text])})
        run.emit("run.steered", text=text)
        return "steered"

    def stop(self, run):
        if run.status != "running":
            raise KeyError("run not active")
        run.cancelled = True
        run.withdraw_questions()
        if not run.ready.is_set():
            run.ready.wait(10)
        try:
            if run.status == "running" and run.loop and run.client:
                for future, _, _ in list(run.approvals.values()):
                    run.loop.call_soon_threadsafe(lambda f=future: not f.done() and f.set_result("deny"))
                # A tool that ignores the interrupt must not keep the session locked forever.
                run.loop.call_soon_threadsafe(run.loop.call_later, self.stop_grace,
                                              lambda: run.status == "running" and run.task.cancel())
                asyncio.run_coroutine_threadsafe(run.client.interrupt(), run.loop).result(timeout=10)
        except Exception:
            if run.status == "running":
                raise  # the turn ended while stopping (loop closed, client gone): that is a successful stop

    def approve(self, run, choice, request_id=""):
        if choice not in ("once", "session", "deny"):
            raise ValueError("invalid approval choice")
        key = request_id or next(iter(run.approvals), "")
        entry = run.approvals.get(key)
        if not entry:
            raise KeyError("no pending approval")
        future = entry[0]
        run.loop.call_soon_threadsafe(lambda: not future.done() and future.set_result(choice))

    def skills(self):
        roots = [Path.home() / ".claude/skills", self.cwd / ".claude/skills",
                 Path.home() / ".claude/plugins/cache"]
        found = {}
        for root in roots:
            for path in root.glob("*/SKILL.md") if root.name != "cache" else root.glob("*/*/skills/*/SKILL.md"):
                try:
                    head = path.read_text(encoding="utf-8")[:4000]
                except OSError:
                    continue
                match = re.search(r"(?m)^description:\s*([^\n]*)", head)
                name = re.search(r"(?m)^name:\s*(.+)$", head)
                description = match.group(1).strip().strip("'\"") if match else ""
                if description in (">", "|", ">-", "|-"):
                    tail = head[match.end():]
                    lines = []
                    for line in tail.splitlines()[1:]:
                        if line and not line.startswith(("  ", "\t")):
                            break
                        lines.append(line.strip())
                    description = " ".join(lines).strip()
                found[(name.group(1).strip() if name else path.parent.name)] = {
                    "name": name.group(1).strip() if name else path.parent.name,
                    "description": description}
        return list(found.values())

    def commands(self):
        """Slash commands for the phone's palette: Claude Code built-ins, user/project commands, then skills."""
        found = dict(BUILTIN_COMMANDS)
        for root in (Path.home() / ".claude/commands", self.cwd / ".claude/commands"):
            for path in sorted(root.rglob("*.md")):
                try:
                    head = path.read_text(encoding="utf-8")[:2000]
                except OSError:
                    continue
                match = re.search(r"(?m)^description:\s*([^\n]*)", head)
                found[":".join(path.relative_to(root).with_suffix("").parts)] = match.group(1).strip().strip("'\"") if match else "Custom command"
        for skill in self.skills():
            found.setdefault(skill["name"], skill["description"][:120])
        return [{"name": n, "description": d} for n, d in found.items()]


# Claude Code runs these itself when they open a prompt, headless included (checked with `claude -p /context`).
BUILTIN_COMMANDS = [("compact", "Summarize the conversation to free up context (usage: /compact [focus])"),
                    ("undo", "Undo user turns and restore checkpointed files (usage: /undo [N])"),
                    ("mcp", "Show MCP server names and connection status"),
                    ("context", "Show how much of the context window is used"),
                    ("cost", "Show plan usage and limits"),
                    ("review", "Review a pull request or the pending changes"),
                    ("security-review", "Security review of the pending changes"),
                    ("init", "Create a CLAUDE.md for this project")]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 120

    def log_message(self, format, *args):
        pass

    @property
    def bridge(self):
        return self.server.bridge

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > 20_000_000:
            raise ValueError("request body too large")
        value = json.loads(self.rfile.read(length) or b"{}")
        if not isinstance(value, dict):
            raise ValueError("JSON object required")
        return value

    def events(self, run):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            index = max(0, int(self.headers.get("Last-Event-ID", "-1")) + 1)
        except ValueError:
            index = 0
        try:
            while True:
                with run.cond:
                    if index >= len(run.events) and run.status == "running":
                        run.cond.wait(10)
                    batch, done = run.events[index:], run.status != "running"
                data = "".join(f'id: {e["seq"]}\ndata: {json.dumps(e)}\n\n' for e in batch) or ": keepalive\n\n"
                self.wfile.write(data.encode())
                self.wfile.flush()
                index += len(batch)
                if done and index >= len(run.events):
                    return
        except OSError:
            return

    def route(self):
        b = self.bridge
        if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + b.token).encode()):
            return self.reply(401, {"error": "unauthorized"})
        url = urlsplit(self.path)
        parts, args, method = url.path.strip("/").split("/"), parse_qs(url.query), self.command
        arg = lambda key, default: args.get(key, [default])[0]
        try:
            if parts == ["v1", "capabilities"] and method == "GET":
                return self.reply(200, {"platform": "Claude Code", "approvals": True, "steer": True,
                                        "reasoning": True, "images": True, "models": True, "forks": True,
                                        "deletes": True, "skills": True, "jobs": False, "toolsets": False, "hub": False})
            if parts in (["v1", "models"], ["api", "model", "options"]) and method == "GET":
                return self.reply(200, {"provider": "claude", "model": "sonnet", "providers": [
                    {"slug": "claude", "name": "Claude Code", "authenticated": True, "models": [
                        {"id": model, "description": next((description for family, description in MODEL_DESCRIPTIONS.items()
                         if model == family or model.startswith("claude-" + family + "-")), "")} for model in MODELS]}]})
            if parts == ["v1", "skills"] and method == "GET":
                return self.reply(200, {"data": b.skills()})
            if parts == ["v1", "commands"] and method == "GET":
                return self.reply(200, {"data": b.commands()})
            if parts[:2] == ["api", "sessions"]:
                if len(parts) == 2 and method == "GET":
                    return self.reply(200, {"data": b.sessions(max(1, min(200, int(arg("limit", 100)))),
                                                               max(0, int(arg("offset", 0))), arg("title", None))})
                if len(parts) == 2 and method == "POST":
                    title = str(self.body().get("title") or "")
                    sid = b.store.create(title)
                    return self.reply(201, {"session": b.find(sid)})
                if len(parts) == 2:
                    return self.reply(501, {"error": "unsupported bridge operation"})
                sid, rest = parts[2], parts[3:]
                item = b.find(sid)
                if item is None:
                    return self.reply(404, {"error": "session not found"})
                if not rest and method == "GET":
                    return self.reply(200, {"session": item})
                if not rest and method == "PATCH":
                    changes = self.body()
                    if "title" in changes:
                        if not isinstance(changes["title"], str) or not changes["title"].strip():
                            raise ValueError("title required")
                        if b.sdk.get_session_info(sid, directory=str(b.cwd)):
                            b.sdk.rename_session(sid, changes["title"].strip(), directory=str(b.cwd))
                    if any(k in changes and not isinstance(changes[k], bool) for k in ("pinned", "archived", "unread")):
                        raise ValueError("pinned, archived and unread must be true or false")
                    b.store.patch(sid, {k: changes[k] for k in ("title", "pinned", "archived", "unread") if k in changes})
                    return self.reply(200, {"session": b.find(sid)})
                if not rest and method == "DELETE":
                    if item["active_run_id"]:
                        raise RuntimeError("Stop the running reply before deleting this conversation")
                    if b.sdk.get_session_info(sid, directory=str(b.cwd)):
                        b.sdk.delete_session(sid, directory=str(b.cwd))
                    b.store.delete(sid)
                    return self.reply(200, {"ok": True})
                if rest == ["fork"] and method == "POST":
                    title = self.body().get("title")
                    if title is not None and not isinstance(title, str):
                        raise ValueError("title must be text")
                    try:
                        result = b.sdk.fork_session(sid, directory=str(b.cwd), title=title)
                    except FileNotFoundError:
                        raise ValueError("Nothing to fork yet: this conversation has no messages")
                    return self.reply(200, {"session": b.find(result.session_id)})
                if rest == ["messages"] and method == "GET":
                    return self.reply(200, {"data": b.messages(sid, max(1, min(500, int(arg("limit", 120)))),
                                                                max(0, int(arg("offset", 0))))})
                if rest == ["model"] and method == "POST":
                    model = str(self.body().get("model") or "").strip()
                    if model not in MODELS:
                        raise ValueError("Unsupported model")
                    b.store.patch(sid, {"model": model})
                    return self.reply(200, {"ok": True})
                if rest == ["settings"] and method in ("GET", "POST"):
                    return self.reply(200, b.settings(sid, self.body() if method == "POST" else None))
                if rest == ["usage"] and method == "GET":
                    return self.reply(200, b.usage(sid, refresh=arg("refresh", "0") == "1"))
                if rest == ["command"] and method == "POST":
                    command = str(self.body().get("command") or "").strip()
                    return self.reply(200, b.command(sid, command))
                if rest == ["chat", "stream"] and method == "POST":
                    body = self.body()
                    run = b.start(sid, body.get("input"), body.get("system_message") or "")
                    return self.events(run)
            if parts == ["v1", "runs"] and method == "POST":
                body = self.body()
                run = b.start(body.get("session_id"), body.get("input"), body.get("instructions") or "")
                return self.reply(202, {"run_id": run.id, "status": "running"})
            if len(parts) >= 3 and parts[:2] == ["v1", "runs"]:
                run = b.runs.get(parts[2])
                if run is None:
                    saved = b.store.get_run(parts[2])  # a run from before a restart: say how it ended, not "gone"
                    if saved and len(parts) == 3 and method == "GET":
                        return self.reply(200, {"run_id": saved["id"], "status": saved["status"], "output": "",
                                                "error": saved["error"], "session_id": saved["session_id"],
                                                "pending_steer": ""})
                    return self.reply(404, {"error": "run not found"})
                rest = parts[3:]
                if not rest and method == "GET":
                    return self.reply(200, {"run_id": run.id, "status": run.status, "output": run.output,
                                            "error": run.error, "session_id": run.thread_id,
                                            "pending_steer": run.pending_steer})
                if rest == ["events"] and method == "GET":
                    return self.events(run)
                if rest == ["mcp", "status"] and method == "GET":
                    if not run.client or not run.loop:
                        return self.reply(404, {"error": "run not active"})
                    status = asyncio.run_coroutine_threadsafe(run.client.get_mcp_status(), run.loop).result(timeout=10)
                    return self.reply(200, dataclasses.asdict(status) if dataclasses.is_dataclass(status) else status)
                if rest == ["stop"] and method == "POST":
                    b.stop(run)
                    return self.reply(200, {"status": "stopping"})
                if rest == ["steer"] and method == "POST":
                    body = self.body()
                    value = body.get("text") or body.get("message")
                    if not isinstance(value, str) or not value.strip():
                        raise ValueError("text required")
                    state = b.steer(run, value)
                    return self.reply(200, {"status": state, "pending_steer": run.pending_steer})
                if rest == ["approval"] and method == "POST":
                    body = self.body()
                    choice = body.get("choice", "deny")
                    b.approve(run, choice, str(body.get("request_id") or ""))
                    return self.reply(200, {"choice": choice})
                if rest == ["clarify"] and method == "POST":
                    body = self.body()
                    b.clarify(run, body.get("request_id"), body.get("answer"))
                    return self.reply(200, {"ok": True})
            return self.reply(501, {"error": "unsupported bridge operation"})
        except KeyError as exc:
            return self.reply(404, {"error": str(exc)})
        except RuntimeError as exc:
            return self.reply(409, {"error": str(exc)})
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            return self.reply(400, {"error": str(exc)})
        except Exception as exc:  # an SDK/disk failure must still answer, not drop the connection
            return self.reply(500, {"error": explain(str(exc) or type(exc).__name__)})

    do_GET = do_POST = do_PATCH = do_DELETE = route


def main():
    token = os.environ.get("CLAUDE_BRIDGE_TOKEN", "")
    cwd = os.environ.get("CLAUDE_BRIDGE_CWD", "")
    path = os.environ.get("CLAUDE_BRIDGE_DB", str(Path.home() / ".local/share/hermes-companion/claude-bridge.sqlite3"))
    store = Store(path)
    store.fail_orphans()
    bridge = Bridge(store, token, cwd)
    server = ThreadingHTTPServer((os.environ.get("CLAUDE_BRIDGE_HOST", "127.0.0.1"),
                                  int(os.environ.get("CLAUDE_BRIDGE_PORT", "8792"))), Handler)
    server.bridge = bridge
    server.serve_forever()


if __name__ == "__main__":
    main()
