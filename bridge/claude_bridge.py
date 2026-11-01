"""Authenticated Hermes remote for Claude Code sessions via claude-agent-sdk."""

import asyncio
import dataclasses
import hmac
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from .codex_bridge import Run, STEER_CLOSE, STEER_OPEN, _rid

MODELS = ("opus", "sonnet", "haiku", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1")
EFFORTS = ("low", "medium", "high", "xhigh", "max")
PERMISSIONS = ("default", "acceptEdits", "plan", "bypassPermissions")


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
            blocks.append({"type": "text", "text": str(part.get("text") or "")})
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


def queued_steers(path, known):
    """Messages queued into a running turn (phone steer or typed in the terminal): {uuid shown before: [text]}.

    Claude Code stores them as `queued_command` attachments, which get_session_messages leaves out.
    """
    parents, steers = {}, {}
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
        a = e.get("attachment") or {}
        if e.get("type") == "attachment" and a.get("type") == "queued_command" and isinstance(a.get("prompt"), str):
            anchor = e.get("parentUuid")
            while anchor and anchor not in known:  # walk up past other attachments to a shown entry
                anchor = parents.get(anchor)
            steers.setdefault(anchor, []).append(a["prompt"])
    return steers


def transcript_rows(messages, steers=(), queued=None):
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
        stamp = message.get("timestamp") or 0
        eid = entry.uuid or str(index)
        if entry.type == "user" and not any(b.get("type") == "tool_result" for b in blocks if isinstance(b, dict)):
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
            for col in ("model", "reasoning_effort", "permission_mode", "steers"):
                if col not in cols:
                    default = "[]" if col == "steers" else ""
                    db.execute(f"ALTER TABLE sessions ADD COLUMN {col} TEXT DEFAULT '{default}'")
            db.execute("DROP TABLE IF EXISTS messages")
            db.execute("DROP TABLE IF EXISTS runs")
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
        allowed = {"title", "pinned", "archived", "unread", "resumed", "model", "reasoning_effort", "permission_mode", "steers"}
        changes = {k: v for k, v in changes.items() if k in allowed}
        if not changes:
            return
        with self.db() as db:
            db.execute("INSERT OR IGNORE INTO sessions(id,updated) VALUES(?,?)", (session_id, time.time()))
            db.execute("UPDATE sessions SET " + ",".join(k + "=?" for k in changes) + " WHERE id=?",
                       (*changes.values(), session_id))

    def create(self, title=""):
        session_id = str(uuid.uuid4())
        self.patch(session_id, {"title": title})
        return session_id

    def delete(self, session_id):
        with self.db() as db:
            db.execute("DELETE FROM sessions WHERE id=?", (session_id,))


class ClaudeRun(Run):
    def __init__(self, run_id, session_id):
        super().__init__(run_id, session_id)
        self.client = self.loop = None
        self.cancelled = False
        self.ready = threading.Event()
        self.pending_steer = ""
        self.steers = []
        self.tool_names = {}


class Bridge:
    def __init__(self, store, token, cwd, client_factory=None, sdk_module=None, approval_timeout=600):
        if not token:
            raise ValueError("CLAUDE_BRIDGE_TOKEN is required")
        self.store, self.token, self.cwd = store, token, Path(cwd)
        if not self.cwd.is_dir():
            raise ValueError("CLAUDE_BRIDGE_CWD must name an existing directory")
        self.sdk = sdk_module or sdk()
        self.client_factory = client_factory or self.sdk.ClaudeSDKClient
        self.approval_timeout = approval_timeout
        self.runs, self.by_session = {}, {}
        self.lock = threading.RLock()

    def session(self, info=None, session_id=None):
        sid = session_id or info.session_id
        meta = self.store.get(sid)
        if info is None and not meta:
            raise KeyError("session not found")
        run = self.by_session.get(sid)
        active = run if run and run.status == "running" else None
        title = (info.custom_title or meta.get("title") or info.summary or info.first_prompt or "") if info else meta.get("title", "")
        return {"id": sid, "title": title, "source": "claude", "model": meta.get("model") or "",
                "has_model_config": bool(meta.get("model")), "preview": (info.first_prompt or "")[:160] if info else "",
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
        items = [self.session(i) for i in infos]
        items += [self.session(session_id=sid) for sid in self.store.all() if sid not in known]
        if title is not None:
            items = [s for s in items if s["title"] == title]
        items.sort(key=lambda s: (s["pinned"], s["last_active"]), reverse=True)
        return items[offset:offset + limit]

    def messages(self, sid, limit, offset):
        messages = self.sdk.get_session_messages(sid, directory=str(self.cwd))
        steers = json.loads(self.store.get(sid).get("steers") or "[]")
        projects = Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude") / "projects"
        path = projects / self.sdk.project_key_for_directory(str(self.cwd)) / (sid + ".jsonl")
        queued = queued_steers(path, {m.uuid for m in messages})
        rows = transcript_rows(messages, [t for t in steers if all(t not in q for q in queued.values())], queued)
        end = len(rows) - offset
        return rows[max(0, end - limit):max(0, end)]

    def settings(self, sid, changes=None):
        if changes is not None:
            if not changes or set(changes) - {"reasoning_effort", "permission_mode"}:
                raise ValueError("reasoning_effort or permission_mode required")
            for key, allowed in (("reasoning_effort", EFFORTS), ("permission_mode", PERMISSIONS)):
                if key in changes and changes[key] not in allowed:
                    raise ValueError("Unsupported " + key)
            self.store.patch(sid, changes)
        meta = self.store.get(sid)
        return {"reasoning_effort": meta.get("reasoning_effort") or "high",
                "reasoning_options": [{"value": x, "label": x.capitalize(), "description": ""} for x in EFFORTS],
                "permission_mode": meta.get("permission_mode") or "default",
                "permission_options": [{"value": x, "label": x, "description": ""} for x in PERMISSIONS],
                "scope": "Changes apply to your next message in this conversation."}

    def start(self, sid, value, instructions=""):
        if self.find(sid) is None:
            raise KeyError("session not found")
        content = input_blocks(value)
        with self.lock:
            prior = self.by_session.get(sid)
            if prior and prior.status == "running":
                raise RuntimeError("session already has an active turn")
            run = ClaudeRun(str(uuid.uuid4()), sid)
            run.user_text = prompt_text(content)
            self.runs[run.id] = self.by_session[sid] = run
            for rid in [r.id for r in self.runs.values() if r.finished_at and r.finished_at < time.time() - 3600]:
                self.runs.pop(rid, None)
        threading.Thread(target=lambda: asyncio.run(self._execute(run, content, instructions)), daemon=True).start()
        return run

    async def _approval(self, run, name, args, context):
        if name in run.session_allowed:
            return self.sdk.PermissionResultAllow()
        rid = str(uuid.uuid4())
        future = asyncio.get_running_loop().create_future()
        run.approvals[rid] = (future, name, args)
        command = args.get("command") or args.get("file_path") or name
        run.emit("approval.request", request_id=rid, command=str(command),
                 description=context.description or context.title or "", choices=["once", "session", "deny"])
        try:
            choice = await asyncio.wait_for(future, self.approval_timeout)
        except asyncio.TimeoutError:
            choice = "deny"
        finally:
            run.approvals.pop(rid, None)
        run.emit("approval.resolved", request_id=rid)
        if choice == "deny":
            return self.sdk.PermissionResultDeny(message="Denied by user")
        if choice == "session":
            run.session_allowed.add(name)
            from claude_agent_sdk.types import PermissionRuleValue
            rule = PermissionRuleValue(tool_name=name)
            update = self.sdk.PermissionUpdate(type="addRules", rules=[rule], behavior="allow", destination="session")
            return self.sdk.PermissionResultAllow(updated_permissions=[update])
        return self.sdk.PermissionResultAllow()

    async def _execute(self, run, content, instructions):
        s = self.sdk
        meta = self.store.get(run.thread_id)
        existing = self.sdk.get_session_info(run.thread_id, directory=str(self.cwd))
        run.loop = asyncio.get_running_loop()
        run.session_allowed = set()
        options = s.ClaudeAgentOptions(cwd=str(self.cwd), session_id=None if existing else run.thread_id,
                                       resume=run.thread_id if existing else None,
                                       include_partial_messages=True, model=meta.get("model") or None,
                                       effort=meta.get("reasoning_effort") or None,
                                       permission_mode=meta.get("permission_mode") or "default",
                                       # Claude Code's own prompt stays; the phone's context is appended to it.
                                       system_prompt={"type": "preset", "preset": "claude_code", "append": instructions} if instructions
                                       else {"type": "preset", "preset": "claude_code"},
                                       setting_sources=["user", "project", "local"],  # CLAUDE.md, skills, MCP, hooks as in the terminal
                                       can_use_tool=lambda n, a, c: self._approval(run, n, a, c))
        run.emit("run.started")
        try:
            client = run.client = self.client_factory(options)
            await client.connect()
            if run.cancelled:
                await client.interrupt()
                run.finish("cancelled")
                return
            prompt = content if isinstance(content, str) else self._content_stream(content)
            await client.query(prompt)
            run.ready.set()
            current, text, thinking_seen, result_seen = None, "", "", False
            async for message in client.receive_response():
                if isinstance(message, s.SystemMessage) and message.subtype == "init":
                    self.store.patch(run.thread_id, {"resumed": 1})
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
                            run.emit("tool.started", tool_name=name, args=args, preview=preview)
                elif isinstance(message, s.UserMessage):
                    blocks = message.content if isinstance(message.content, list) else []
                    for block in blocks:
                        if isinstance(block, s.ToolResultBlock):
                            raw = block.content or ""
                            preview = raw if isinstance(raw, str) else json.dumps(raw)
                            run.emit("tool.failed" if block.is_error else "tool.completed",
                                     tool_name=run.tool_names.get(block.tool_use_id, "tool"), args={}, preview=preview[-300:])
                elif isinstance(message, s.ResultMessage):
                    result_seen = True
                    if message.is_error:
                        raise RuntimeError(message.result or "Claude turn failed")
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
        except Exception as exc:
            run.finish("cancelled" if run.cancelled else "failed", "" if run.cancelled else str(exc))
        finally:
            run.ready.set()
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
        if not run.ready.is_set():
            run.ready.wait(10)
        if run.status == "running" and run.loop and run.client:
            for future, _, _ in list(run.approvals.values()):
                run.loop.call_soon_threadsafe(lambda f=future: not f.done() and f.set_result("deny"))
            asyncio.run_coroutine_threadsafe(run.client.interrupt(), run.loop).result(timeout=10)

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
            index = int(self.headers.get("Last-Event-ID", "-1")) + 1
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
        if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + b.token):
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
                    {"slug": "claude", "name": "Claude Code", "authenticated": True, "models": list(MODELS)}]})
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
                    b.store.patch(sid, {k: changes[k] for k in ("title", "pinned", "archived", "unread") if k in changes})
                    return self.reply(200, {"session": b.find(sid)})
                if not rest and method == "DELETE":
                    if b.sdk.get_session_info(sid, directory=str(b.cwd)):
                        b.sdk.delete_session(sid, directory=str(b.cwd))
                    b.store.delete(sid)
                    return self.reply(200, {"ok": True})
                if rest == ["fork"] and method == "POST":
                    title = self.body().get("title")
                    result = b.sdk.fork_session(sid, directory=str(b.cwd), title=title)
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
                if rest == ["command"] and method == "POST":
                    # Claude Code owns its slash commands: the phone sends the command as an ordinary prompt.
                    command = str(self.body().get("command") or "").strip()
                    if not command.startswith("/"):
                        raise ValueError("command required")
                    return self.reply(200, {"type": "send", "message": command})
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
            return self.reply(501, {"error": "unsupported bridge operation"})
        except KeyError as exc:
            return self.reply(404, {"error": str(exc)})
        except RuntimeError as exc:
            return self.reply(409, {"error": str(exc)})
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            return self.reply(400, {"error": str(exc)})

    do_GET = do_POST = do_PATCH = do_DELETE = route


def main():
    token = os.environ.get("CLAUDE_BRIDGE_TOKEN", "")
    cwd = os.environ.get("CLAUDE_BRIDGE_CWD", "")
    path = os.environ.get("CLAUDE_BRIDGE_DB", str(Path.home() / ".local/share/hermes-companion/claude-bridge.sqlite3"))
    bridge = Bridge(Store(path), token, cwd)
    server = ThreadingHTTPServer((os.environ.get("CLAUDE_BRIDGE_HOST", "127.0.0.1"),
                                  int(os.environ.get("CLAUDE_BRIDGE_PORT", "8792"))), Handler)
    server.bridge = bridge
    server.serve_forever()


if __name__ == "__main__":
    main()
