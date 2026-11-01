"""Remote control for Codex: Hermes Companion drives sessions on the local Codex app-server daemon.

Codex CLI sessions (the TUI included) live in the shared app-server daemon, so the phone sees and
continues the same threads the terminal does, live: list, read, send, steer, stop, approve. It speaks
the Hermes wire contract the app already uses (/api/sessions, durable /v1/runs with SSE replay).
"""

import hashlib
import hmac
import json
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

STEER_OPEN, STEER_CLOSE = "[OUT-OF-BAND USER MESSAGE]", "[/OUT-OF-BAND USER MESSAGE]"
CONTEXT_OPEN, CONTEXT_CLOSE = "<phone_context>", "</phone_context>"
# Codex's own slash commands that the daemon can run natively; the rest of the phone's palette is app-side.
COMMANDS = [("compact", "Summarize the conversation to free up context"),
            ("review", "Review uncommitted changes (usage: /review [instructions])"),
            ("diff", "Show the working tree's git changes"),
            ("mcp", "List MCP servers and their state"),
            ("skills", "List available skills"),
            ("status", "Model, reasoning, permissions and rate limits"),
            ("goal", "Show or set the thread goal (usage: /goal <objective>)"),
            ("init", "Create an AGENTS.md for this project"),
            ("undo", "Drop the last turns from the history (usage: /undo [N]); files stay as they are")]
PLACEHOLDER_TITLES = {"", "new conversation", "new chat", "mobile"}
DECISIONS = {"once": "accept", "session": "acceptForSession", "always": "acceptForSession", "deny": "decline"}
APPROVAL_METHODS = {"item/commandExecution/requestApproval", "item/fileChange/requestApproval",
                    "item/permissions/requestApproval"}
PERMISSIONS = {
    "read-only": ("Read only", "Read files; ask before commands need more access.",
                  {"type": "readOnly", "networkAccess": False}, "on-request"),
    "workspace-write": ("Workspace access", "Edit the project; ask before network or access outside it.",
                        {"type": "workspaceWrite", "networkAccess": False}, "on-request"),
    "full-access": ("Full access", "Commands, network and files across the host without approval.",
                    {"type": "dangerFullAccess"}, "never"),
}


class RpcError(Exception):
    pass


class Rpc:
    """One JSON-RPC connection to the daemon (WebSocket over its unix socket); reconnects on drop."""

    def __init__(self, sock, on_message, on_ready=lambda: None, connect=None):
        self.sock, self.on_message, self.on_ready = sock, on_message, on_ready
        self.connect = connect or self._unix_connect
        self.lock, self.send_lock = threading.Lock(), threading.Lock()
        self.pending, self.next_id, self.ws = {}, 0, None
        self.ready = threading.Event()
        threading.Thread(target=self._loop, daemon=True).start()

    def _unix_connect(self):
        from websockets.sync.client import unix_connect
        # No keepalive pings: the daemon stalls for a minute when its own upstream calls time out, and a ping
        # timeout then drops every subscription. A dead unix socket fails recv() by itself.
        return unix_connect(os.path.realpath(self.sock), uri="ws://localhost/", max_size=None, open_timeout=10,
                            ping_interval=None)

    def _loop(self):
        while True:
            try:
                ws = self.connect()
                ws.send(json.dumps({"id": "init", "method": "initialize", "params": {
                    "clientInfo": {"name": "hermes-companion", "title": "Hermes Companion", "version": "1"},
                    "capabilities": {"experimentalApi": True}}}))
                while json.loads(ws.recv()).get("id") != "init":
                    pass
                ws.send(json.dumps({"method": "initialized"}))
                self.ws = ws
                self.ready.set()
                threading.Thread(target=self.on_ready, daemon=True).start()
                while True:
                    self._dispatch(json.loads(ws.recv()))
            except Exception:
                pass
            self.ready.clear()
            with self.lock:
                failed, self.pending = self.pending, {}
            for slot in failed.values():
                slot[1] = {"error": {"message": "Codex daemon connection lost"}}
                slot[0].set()
            time.sleep(2)

    def _dispatch(self, msg):
        if "method" not in msg:
            with self.lock:
                slot = self.pending.pop(msg.get("id"), None)
            if slot:
                slot[1] = msg
                slot[0].set()
            return
        try:
            self.on_message(msg)
        except Exception:
            pass  # One bad notification must not drop the connection.

    def send(self, msg):
        with self.send_lock:
            self.ws.send(json.dumps(msg))

    def call(self, method, params, timeout=20):  # under the app's 30 s read timeout: an error, not a hang
        if not self.ready.wait(15):
            raise RpcError("Codex daemon is not running (codex app-server daemon start)")
        slot = [threading.Event(), None]
        with self.lock:
            self.next_id += 1
            rid = self.next_id
            self.pending[rid] = slot
        self.send({"id": rid, "method": method, "params": params})
        if not slot[0].wait(timeout):
            with self.lock:
                self.pending.pop(rid, None)
            raise RpcError(method + " timed out")
        if "error" in slot[1]:
            raise RpcError(slot[1]["error"].get("message", "Codex error"))
        return slot[1].get("result") or {}

    def respond(self, rid, result):
        self.send({"id": rid, "result": result})


class Run:
    """One Codex turn as a Hermes run: an append-only event log the phone replays by seq."""

    def __init__(self, run_id, thread_id):
        self.id, self.thread_id = run_id, thread_id
        self.user_text, self.last_text, self.output, self.error = "", "", "", ""
        self.status, self.finished_at = "running", 0.0
        self.events, self.items, self.approvals = [], {}, {}
        self.message_text, self.last_message_id, self.steered = {}, None, set()
        self.cond = threading.Condition()

    def emit(self, name, **data):
        with self.cond:
            data.update(event=name, seq=len(self.events), run_id=self.id)
            self.events.append(data)
            self.cond.notify_all()

    def finish(self, status, error=""):
        with self.cond:
            if self.status != "running":
                return
            self.output, self.error = self.last_text, error
            self.emit("run." + status, output=self.output, error=error, session_id=self.thread_id)
            self.status, self.finished_at = status, time.time()
            self.cond.notify_all()

    def steer(self, text):
        """Announce a steer once, whether the phone sent it or its item arrived from the daemon."""
        with self.cond:
            if text not in self.steered:
                self.steered.add(text)
                self.emit("run.steered", text=text)

    def message(self, item_id, text, completed=False):
        """Stream each item once; its completion supplies any text missing from deltas."""
        with self.cond:
            item_id = item_id or self.last_message_id or "message"
            seen = self.message_text.get(item_id, "")
            delta = text[len(seen):] if completed and text.startswith(seen) else "" if completed else text
            if delta:
                if self.last_message_id is not None and self.last_message_id != item_id:
                    self.emit("assistant.delta", delta="\n\n", segment=True)  # the phone starts a new bubble
                self.emit("assistant.delta", delta=delta)
                self.message_text[item_id] = seen + delta
                self.last_message_id = item_id
            if completed:
                self.last_text = text or self.last_text


def _rid(*parts):
    return int(hashlib.sha1("|".join(parts).encode()).hexdigest()[:13], 16)


def _text(content):
    text = "\n".join(c.get("text", "") for c in content or [] if c.get("type") == "text")
    if text.startswith(CONTEXT_OPEN) and CONTEXT_CLOSE in text:  # the phone's per-turn context is not part of the message
        text = text.split(CONTEXT_CLOSE, 1)[1].lstrip("\n")
    return text


def _content(content):
    """A user message for the phone: plain text, or text plus image parts when images were attached."""
    text = _text(content)
    images = [c.get("url") for c in content or [] if c.get("type") == "image" and str(c.get("url", "")).startswith("data:image/")]
    if not images:
        return text
    return [{"type": "text", "text": text}] + [{"type": "image_url", "image_url": u} for u in images]


def turn_input(value, instructions=""):
    """Hermes run input (text, or text and image_url parts) as Codex turn input; (items, text) or ValueError.

    Codex has no per-turn system prompt, so the phone's instructions ride in front of the message in a tagged block.
    """
    parts = [{"type": "text", "text": value}] if isinstance(value, str) else value
    if not isinstance(parts, list):
        raise ValueError("text input required")
    items, text = [], []
    for part in parts:
        kind = part.get("type") if isinstance(part, dict) else None
        if kind in ("text", "input_text"):
            text.append(str(part.get("text") or ""))
        elif kind in ("image_url", "input_image"):
            ref = part.get("image_url")
            url = ref.get("url") if isinstance(ref, dict) else ref
            if not str(url or "").startswith("data:image/"):
                raise ValueError("base64 image data URL required")
            items.append({"type": "image", "url": url})
        else:
            raise ValueError("unsupported input block")
    message = "\n".join(t for t in text if t.strip())
    if not message.strip() and not items:
        raise ValueError("text input required")
    shown = message if message.strip() else "(see attached image)"
    sent = (CONTEXT_OPEN + "\n" + instructions.strip() + "\n" + CONTEXT_CLOSE + "\n\n" if instructions.strip() else "") + shown
    return [{"type": "text", "text": sent, "text_elements": []}] + items, message


def _tail(text, n=4000):
    text = text or ""
    return text if len(text) <= n else "…" + text[-n:]


def _command(cmd):
    cmd = cmd or ""
    for prefix in ("/bin/bash -lc ", "bash -lc ", "/bin/sh -c ", "sh -c "):
        if cmd.startswith(prefix):
            cmd = cmd[len(prefix):].strip()
            if len(cmd) > 1 and cmd[0] == cmd[-1] and cmd[0] in "'\"":
                cmd = cmd[1:-1]
            break
    return cmd


def tool_of(item):
    """(name, args, result, failed, preview) for an item the phone shows as a tool card, else None."""
    kind, status = item.get("type"), item.get("status")
    failed = status in ("failed", "declined")
    if kind == "commandExecution":
        cmd, code = _command(item.get("command")), item.get("exitCode")
        return ("terminal", {"command": cmd, "cwd": item.get("cwd")},
                {"output": _tail(item.get("aggregatedOutput")), "exit_code": code, "status": status},
                failed or bool(code), cmd.splitlines()[0][:120] if cmd else "")
    if kind == "fileChange":
        changes = item.get("changes") or []
        files = [c.get("path", "") for c in changes]
        return ("patch", {"files": files}, {"diff": _tail("\n".join(c.get("diff", "") for c in changes)), "status": status},
                failed, ", ".join(Path(f).name for f in files)[:120])
    if kind == "mcpToolCall":
        result = {"error": item["error"]} if item.get("error") else {"result": item.get("result")}
        return (item.get("tool") or "mcp", item.get("arguments") or {}, result, failed or bool(item.get("error")),
                item.get("server") or "")
    if kind == "dynamicToolCall":
        return (item.get("tool") or "tool", item.get("arguments") or {}, {"result": item.get("contentItems")},
                failed or item.get("success") is False, "")
    if kind == "webSearch":
        return ("web_search", {"query": item.get("query")}, {"action": item.get("action")}, False, item.get("query") or "")
    if kind == "collabAgentToolCall":
        return ("delegate_task", {"prompt": item.get("prompt")}, {"status": status}, failed, (item.get("prompt") or "")[:120])
    if kind == "imageView":
        return ("vision_analyze", {"path": item.get("path")}, {}, False, item.get("path") or "")
    return None


def turn_rows(turn):
    """A finished turn as Hermes transcript rows; later user messages in a turn were steers."""
    rows, ts, first, reasoning = [], turn.get("startedAt") or 0, True, ""
    for item in turn.get("items") or []:
        kind, iid = item.get("type"), item.get("id", "")
        if kind == "userMessage":
            text = _content(item.get("content"))
            if not first:
                text = STEER_OPEN + "\n" + _text(item.get("content")) + "\n" + STEER_CLOSE
            first = False
            rows.append({"id": _rid(iid), "role": "user", "content": text, "timestamp": ts})
        elif kind == "agentMessage":
            rows.append({"id": _rid(iid), "role": "assistant", "content": item.get("text", ""),
                         "reasoning": reasoning, "timestamp": ts})
            reasoning = ""
        elif kind == "reasoning":
            reasoning += "\n".join(item.get("summary") or [])
        else:
            tool = tool_of(item)
            if tool:
                name, args, result = tool[:3]
                rows.append({"id": _rid(iid, "call"), "role": "assistant", "content": "", "timestamp": ts,
                             "tool_calls": [{"id": iid, "function": {"name": name, "arguments": json.dumps(args)}}]})
                rows.append({"id": _rid(iid, "result"), "role": "tool", "tool_call_id": iid, "tool_name": name,
                             "content": json.dumps(result), "timestamp": ts})
    return rows


class Bridge:
    def __init__(self, token, cwd, state_path, sock=None, connect=None):
        if not token:
            raise ValueError("CODEX_BRIDGE_TOKEN is required")
        self.token, self.cwd, self.state_path = token, str(cwd), Path(state_path)
        self.runs, self.by_thread, self.subscribed = {}, {}, set()
        self.runtime = {}
        self.lock = threading.RLock()
        try:
            self.state = json.loads(self.state_path.read_text())
        except Exception:
            self.state = {}
        for key in ("pinned", "unread", "model", "settings"):
            self.state.setdefault(key, {})
        self.rpc = Rpc(sock, self.on_message, self.on_ready, connect)

    def save(self):
        with self.lock:
            self.state_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            tmp = self.state_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.state))
            tmp.chmod(0o600)
            tmp.replace(self.state_path)

    # ── daemon events ──────────────────────────────────────────────────────────────────────────

    def run_for(self, thread_id, turn_id, create=True):
        with self.lock:
            run = self.runs.get(turn_id)
            if run is None and create and thread_id and turn_id:
                run = self.runs[turn_id] = Run(turn_id, thread_id)
                self.by_thread[thread_id] = run
                cutoff = time.time() - 3600
                for rid in [r.id for r in self.runs.values() if r.finished_at and r.finished_at < cutoff]:
                    self.runs.pop(rid, None)
            return run

    def on_message(self, msg):
        method, p = msg["method"], msg.get("params") or {}
        thread_id = p.get("threadId")
        turn_id = p.get("turnId") or (p.get("turn") or {}).get("id")
        if "id" in msg:  # a server request: the daemon waits for an answer
            if method in APPROVAL_METHODS:
                self.on_approval(msg["id"], method, p, self.run_for(thread_id, turn_id))
            return
        if method == "serverRequest/resolved":
            run = self.by_thread.get(thread_id)
            if run and run.approvals.pop(str(p.get("requestId")), None) is not None:
                run.emit("approval.resolved", request_id=str(p.get("requestId")))
            return
        if not turn_id:
            return
        run = self.run_for(thread_id, turn_id)
        if method == "turn/started":
            run.emit("run.started")
        elif method == "item/started":
            self.on_item(run, p["item"], started=True)
        elif method == "item/completed":
            self.on_item(run, p["item"], started=False)
        elif method == "item/agentMessage/delta":
            run.message(p.get("itemId"), p.get("delta", ""))
        elif method == "item/commandExecution/outputDelta":
            run.emit("tool.output", tool_name="terminal", preview=p.get("delta", ""))
        elif method in ("item/reasoning/summaryTextDelta", "item/reasoning/textDelta"):
            run.emit("reasoning.available", text=p.get("delta", ""))
        elif method == "turn/completed":
            turn = p.get("turn") or {}
            status = {"completed": "completed", "interrupted": "cancelled"}.get(turn.get("status"), "failed")
            run.finish(status, ((turn.get("error") or {}).get("message") or "") if status == "failed" else "")

    def on_item(self, run, item, started):
        kind = item.get("type")
        run.items[item.get("id")] = item
        if kind == "userMessage":
            text = _text(item.get("content"))
            if started and not run.user_text:
                run.user_text = text
            elif text and text != run.user_text and text not in run.steered:
                run.steer(text)  # a mid-turn message is a steer; this one came from another client
        elif kind == "agentMessage" and not started:
            run.message(item.get("id"), item.get("text", ""), completed=True)
        else:
            tool = tool_of(item)
            if tool:
                name, args, result, failed, preview = tool
                state = "started" if started else "failed" if failed else "completed"
                run.emit("tool." + state, tool_name=name, args=args, preview=preview if started else
                         _tail(result.get("output") or result.get("diff") or "", 300) or preview)

    def on_approval(self, rpc_id, method, p, run):
        if run is None:
            return
        rid = str(rpc_id)
        run.approvals[rid] = (rpc_id, method, p)
        item = run.items.get(p.get("itemId")) or {}
        if method == "item/commandExecution/requestApproval":
            command = _command(p.get("command") or item.get("command"))
            description = p.get("reason") or ("in " + p["cwd"] if p.get("cwd") else "")
        elif method == "item/fileChange/requestApproval":
            files = [c.get("path", "") for c in item.get("changes") or []]
            command = "Edit " + (", ".join(files) if files else "files")
            description = p.get("reason") or ""
        else:
            command = "Grant extra permissions"
            description = p.get("reason") or json.dumps(p.get("permissions"))[:300]
        run.emit("approval.request", request_id=rid, command=command, description=description,
                 choices=["once", "session", "deny"])

    def on_ready(self):
        """After a (re)connect: subscribe again to threads with live runs and settle any that ended meanwhile."""
        with self.lock:
            self.subscribed.clear()
            live = [r for r in self.runs.values() if r.status == "running"]
        for run in live:
            try:
                self.subscribe(run.thread_id)
                turns = self.rpc.call("thread/turns/list", {"threadId": run.thread_id, "limit": 10})["data"]
                status = next((t["status"] for t in turns if t["id"] == run.id), "completed")
                if status != "inProgress":
                    run.finish({"completed": "completed", "interrupted": "cancelled"}.get(status, "failed"))
            except Exception:
                pass

    # ── sessions ───────────────────────────────────────────────────────────────────────────────

    def subscribe(self, thread_id):
        if thread_id not in self.subscribed:
            self.runtime[thread_id] = self.rpc.call("thread/resume", {"threadId": thread_id, "excludeTurns": True})
            self.subscribed.add(thread_id)

    def session(self, t):
        tid = t["id"]
        run = self.by_thread.get(tid)
        live = run is not None and run.status == "running"
        override = self.state["model"].get(tid, "")
        title = t.get("name") or (t.get("preview") or "").strip().split("\n")[0][:60]
        return {"id": tid, "title": title, "source": "codex", "model": override or t.get("model") or "",
                "has_model_config": bool(override), "preview": (t.get("preview") or "")[:160],
                "message_count": 0, "tool_call_count": 0, "last_active": t.get("updatedAt") or t.get("createdAt") or 0,
                "pinned": bool(self.state["pinned"].get(tid)), "unread": bool(self.state["unread"].get(tid)),
                "archived": False, "cwd": t.get("cwd") or "", "status": (t.get("status") or {}).get("type", ""),
                "active_run_id": run.id if live else "", "active_run_input": run.user_text if live else ""}

    def attach(self, t):
        """A turn running elsewhere (the terminal): follow it, seeded with what it has done so far."""
        tid = t["id"]
        run = self.by_thread.get(tid)
        if (t.get("status") or {}).get("type") != "active" or (run and run.status == "running"):
            return
        self.subscribe(tid)
        turns = self.turns({"threadId": tid, "limit": 1, "itemsView": "full"})["data"]
        if not turns or turns[0]["status"] != "inProgress":
            return
        run = self.run_for(tid, turns[0]["id"])
        if run.events:
            return
        for item in turns[0].get("items") or []:
            if item.get("type") == "agentMessage":
                run.message(item.get("id"), item.get("text", ""), completed=True)
                continue
            self.on_item(run, item, started=True)
            if item.get("status") not in (None, "inProgress"):
                self.on_item(run, item, started=False)

    def thread(self, tid):
        t = self.rpc.call("thread/read", {"threadId": tid, "includeTurns": False})["thread"]
        self.attach(t)
        return t

    def sessions(self, limit, offset, title=None):
        out, cursor = [], None
        while len(out) < limit + offset:
            r = self.rpc.call("thread/list", {"limit": 100, "cursor": cursor, "sortKey": "updated_at"})
            out += [t for t in r["data"] if title is None or t.get("name") == title]
            cursor = r.get("nextCursor")
            if not cursor:
                break
        page = out[offset:offset + limit]
        page.sort(key=lambda t: not self.state["pinned"].get(t["id"]))
        return [self.session(t) for t in page]

    def create(self, title):
        runtime = self.rpc.call("thread/start", {"cwd": self.cwd})
        t = runtime["thread"]
        self.runtime[t["id"]] = runtime
        self.subscribed.add(t["id"])
        if title.strip().lower() not in PLACEHOLDER_TITLES:
            self.rpc.call("thread/name/set", {"threadId": t["id"], "name": title.strip()})
            t["name"] = title.strip()
        return self.session(t)

    def patch(self, tid, body):
        if isinstance(body.get("title"), str) and body["title"].strip():
            self.rpc.call("thread/name/set", {"threadId": tid, "name": body["title"].strip()})
        if "archived" in body:
            self.rpc.call("thread/archive" if body["archived"] else "thread/unarchive", {"threadId": tid})
        with self.lock:
            for flag in ("pinned", "unread"):
                if flag in body:
                    self.state[flag][tid] = bool(body[flag])
                    self.save()
        return self.session(self.thread(tid))

    def turns(self, params):
        """thread/turns/list, with a new thread that has no user message yet read as having no turns (Codex errors instead)."""
        try:
            return self.rpc.call("thread/turns/list", params)
        except RpcError as exc:
            if "not materialized" in str(exc):
                return {"data": []}
            raise

    def messages(self, tid, limit, offset):
        rows, cursor = [], None
        while len(rows) < limit + offset:
            r = self.turns({"threadId": tid, "limit": 20, "itemsView": "full", "cursor": cursor})
            for turn in r["data"]:  # newest first
                if turn["status"] != "inProgress":  # a live turn shows as its run
                    rows = turn_rows(turn) + rows
            cursor = r.get("nextCursor")
            if not cursor:
                break
        end = len(rows) - offset
        return rows[max(0, end - limit):max(0, end)]

    def models(self):
        data = [m for m in self.rpc.call("model/list", {})["data"] if not m.get("hidden")]
        default = next((m["id"] for m in data if m.get("isDefault")), "")
        return {"provider": "codex", "model": default,
                "providers": [{"slug": "codex", "name": "Codex", "authenticated": True,
                               "models": [m["id"] for m in data]}]}

    def runtime_settings(self, tid):
        try:
            runtime = self.rpc.call("thread/resume", {"threadId": tid, "excludeTurns": True})
        except RpcError as exc:
            # A new thread has no rollout until its first message. thread/start already gave its settings.
            if "no rollout found" not in str(exc) or tid not in self.runtime:
                raise
            runtime = self.runtime[tid]
        self.runtime[tid] = runtime
        self.subscribed.add(tid)
        return runtime

    def settings(self, tid, changes=None):
        """Read effective daemon settings; save explicit overrides only for future phone turns."""
        runtime = self.runtime_settings(tid)
        models = self.rpc.call("model/list", {})["data"]
        model_id = self.state["model"].get(tid) or runtime.get("model")
        model = next((m for m in models if model_id in (m["id"], m.get("model"))), None)
        if model is None:
            raise ValueError("The session model is not in the Codex model catalog")
        efforts = [{"value": e["reasoningEffort"], "label": e["reasoningEffort"].capitalize(),
                    "description": e.get("description", "")}
                   for e in model.get("supportedReasoningEfforts", [])]
        requirements = self.rpc.call("configRequirements/read", {}).get("requirements") or {}
        permission_options = []
        for value, (label, description, sandbox, policy) in PERMISSIONS.items():
            mode = "danger-full-access" if value == "full-access" else value
            allowed_modes, allowed_policies = requirements.get("allowedSandboxModes"), requirements.get("allowedApprovalPolicies")
            if allowed_modes is not None and mode not in allowed_modes:
                continue
            if allowed_policies is not None and policy not in allowed_policies:
                continue
            permission_options.append({"value": value, "label": label, "description": description})
        with self.lock:
            saved = dict(self.state["settings"].get(tid, {}))
            if changes is not None:
                if not changes or set(changes) - {"reasoning_effort", "permission_mode"}:
                    raise ValueError("reasoning_effort or permission_mode required")
                for key, options in (("reasoning_effort", efforts), ("permission_mode", permission_options)):
                    if key in changes:
                        if key == "permission_mode" and changes[key] == "custom" and not saved.get(key):
                            continue  # Selecting the current policy is a no-op, never a new grant.
                        if changes[key] not in [o["value"] for o in options]:
                            raise ValueError("Unsupported " + key)
                        saved[key] = changes[key]
                previous = self.state["settings"].get(tid)
                self.state["settings"][tid] = saved
                try:
                    self.save()
                except Exception:
                    if previous is None:
                        self.state["settings"].pop(tid, None)
                    else:
                        self.state["settings"][tid] = previous
                    raise
        inherited_effort = runtime.get("reasoningEffort") if model_id == runtime.get("model") else None
        effort = saved.get("reasoning_effort") or inherited_effort or model.get("defaultReasoningEffort", "")
        sandbox, policy = runtime.get("sandbox") or {}, runtime.get("approvalPolicy")
        permission = next((v for v, (_, _, s, a) in PERMISSIONS.items()
                           if sandbox == s and policy == a), "custom")
        if saved.get("permission_mode"):
            permission = saved["permission_mode"]
            _, _, sandbox, policy = PERMISSIONS[permission]
        elif permission == "custom":
            permission_options.insert(0, {"value": "custom", "label": "Current server permissions",
                                         "description": "Keep the agent's existing sandbox and approval policy."})
        return {"reasoning_effort": effort, "reasoning_options": efforts, "permission_mode": permission,
                "permission_options": permission_options, "sandbox": sandbox, "approval_policy": policy,
                "scope": "Changes apply to your next message in this conversation."}

    # ── runs ───────────────────────────────────────────────────────────────────────────────────

    def start(self, tid, value, instructions=""):
        self.subscribe(tid)
        items, text = turn_input(value, instructions)
        params = {"threadId": tid, "input": items}
        if self.state["model"].get(tid):
            params["model"] = self.state["model"][tid]
        with self.lock:
            settings = dict(self.state["settings"].get(tid, {}))
        if settings.get("reasoning_effort"):
            runtime = self.runtime_settings(tid)
            params["effort"] = settings["reasoning_effort"]
            # Collaboration mode takes precedence over the top-level model/effort overrides.
            if runtime.get("collaborationMode"):
                mode = runtime["collaborationMode"]
                params["collaborationMode"] = {**mode, "settings": {**mode["settings"],
                    "model": params.get("model") or runtime["model"], "reasoning_effort": params["effort"]}}
        if settings.get("permission_mode"):
            _, _, params["sandboxPolicy"], params["approvalPolicy"] = PERMISSIONS[settings["permission_mode"]]
            params["approvalsReviewer"] = "user"
        turn = self.rpc.call("turn/start", params)["turn"]
        run = self.run_for(tid, turn["id"])
        run.user_text = run.user_text or text
        return run

    # ── slash commands ─────────────────────────────────────────────────────────────────────────

    def command(self, tid, text):
        """Run a Codex slash command on the daemon. Reply shape matches the hub's /hermes/slash:
        {type: output|notice|send|run, ...}; unknown commands go to Codex as ordinary text."""
        name, _, arg = text.strip().lstrip("/").partition(" ")
        name, arg = name.lower(), arg.strip()
        if name not in dict(COMMANDS):
            return {"type": "send", "message": text.strip()}
        if name == "init":
            return {"type": "send", "message": "Create an AGENTS.md for this project: build and test commands, layout, "
                                               "conventions. Keep it short and specific to what is in the repo."}
        if name == "undo":
            count = int(arg) if arg.isdigit() and int(arg) > 0 else 1
            turns = self.turns({"threadId": tid, "limit": count, "itemsView": "full"})["data"]  # newest first
            if len(turns) < count or any(t["status"] == "inProgress" for t in turns):
                raise ValueError("Nothing to undo" if len(turns) < count else "Stop the current turn first")
            oldest = turns[count - 1]
            first = next((_text(i.get("content")) for i in oldest.get("items") or [] if i.get("type") == "userMessage"), "")
            self.rpc.call("thread/revert", {"threadId": tid, "beforeTurnId": oldest["id"]})
            return {"type": "undo", "text": first, "notice": "Undid %d turn%s" % (count, "" if count == 1 else "s")}
        if name == "compact":
            self.subscribe(tid)
            self.rpc.call("thread/compact/start", {"threadId": tid})
            return {"type": "notice", "notice": "Compacting the conversation…"}
        if name == "review":
            self.subscribe(tid)
            target = {"type": "custom", "instructions": arg} if arg else {"type": "uncommittedChanges"}
            turn = self.rpc.call("review/start", {"threadId": tid, "delivery": "inline", "target": target})["turn"]
            self.run_for(tid, turn["id"])
            return {"type": "run", "run_id": turn["id"], "notice": "Reviewing…"}
        if name == "diff":
            cwd = self.runtime_settings(tid).get("cwd")
            out = self.rpc.call("command/exec", {"command": ["git", "diff", "--stat", "--patch", "HEAD"], "cwd": cwd,
                                                 "timeoutMs": 15000})
            if out.get("exitCode"):
                return {"type": "output", "output": "Not a git repository: %s" % cwd}
            return {"type": "output", "output": _tail(out.get("stdout") or "No changes.", 20000)}
        if name == "mcp":
            data = self.rpc.call("mcpServerStatus/list", {"threadId": tid}).get("data") or []
            lines = ["%s: %s, %d tools" % (m.get("name"), m.get("runtimeStatus") or "?",
                                           len(m.get("tools") or {})) for m in data]
            return {"type": "output", "output": "\n".join(lines) or "No MCP servers configured."}
        if name == "skills":
            rows = []
            for entry in self.rpc.call("skills/list", {}).get("data") or []:
                rows += ["%s: %s" % (k.get("name"), (k.get("shortDescription") or k.get("description") or "").strip()[:100])
                         for k in entry.get("skills") or [] if k.get("enabled", True)]
            return {"type": "output", "output": "\n".join(rows) or "No skills installed."}
        if name == "goal":
            if arg:
                self.rpc.call("thread/goal/set", {"threadId": tid, "objective": arg})
                return {"type": "notice", "notice": "Goal set: " + arg}
            goal = self.rpc.call("thread/goal/get", {"threadId": tid}).get("goal")
            return {"type": "output", "output": (goal or {}).get("objective") or "No goal set. Use /goal <objective>."}
        # status
        runtime, info = self.runtime_settings(tid), self.settings(tid)
        lines = ["Model: %s" % (self.state["model"].get(tid) or runtime.get("model")),
                 "Reasoning: %s" % info.get("reasoning_effort"), "Permissions: %s" % info.get("permission_mode"),
                 "Directory: %s" % runtime.get("cwd")]
        try:
            limits = self.rpc.call("account/rateLimits/read", {}).get("rateLimits") or {}
            for label, key in (("5 h limit", "primary"), ("Weekly limit", "secondary")):
                window = limits.get(key)
                if window:
                    lines.append("%s: %s%% used" % (label, window.get("usedPercent")))
        except RpcError:
            pass
        return {"type": "output", "output": "\n".join(lines)}

    def steer(self, run, text):
        self.rpc.call("turn/steer", {"threadId": run.thread_id, "expectedTurnId": run.id,
                                     "input": [{"type": "text", "text": text, "text_elements": []}]})
        run.steer(text)

    def approve(self, run, choice, request_id):
        key = request_id or next(iter(run.approvals), None)
        entry = run.approvals.pop(key, None) if key else None
        if entry is None:
            raise KeyError("no pending approval")
        rpc_id, method, p = entry
        allow = choice != "deny"
        if method == "item/permissions/requestApproval":
            result = {"permissions": p.get("permissions") if allow else {},
                      "scope": "session" if choice in ("session", "always") else "turn"}
        else:
            result = {"decision": DECISIONS.get(choice, "decline")}
        self.rpc.respond(rpc_id, result)
        run.emit("approval.resolved", request_id=key)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 120  # idle keep-alive sockets close instead of going stale when the phone changes networks

    def log_message(self, format, *args):
        pass  # Prompts and tokens never reach logs.

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
        if length < 0 or length > 1_000_000:
            raise ValueError("request body too large")
        body = json.loads(self.rfile.read(length) or b"{}")
        if not isinstance(body, dict):
            raise ValueError("JSON object required")
        return body

    def events(self, run):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            i = int(self.headers.get("Last-Event-ID", "-1")) + 1
        except ValueError:
            i = 0
        while True:
            with run.cond:
                if i >= len(run.events) and run.status == "running":
                    run.cond.wait(10)
                batch, done = run.events[i:], run.status != "running"
            chunk = "".join("id: %d\ndata: %s\n\n" % (e["seq"], json.dumps(e)) for e in batch) or ": keepalive\n\n"
            self.wfile.write(chunk.encode())
            self.wfile.flush()
            i += len(batch)
            if done and i >= len(run.events):
                return

    def route(self):
        if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.bridge.token):
            return self.reply(401, {"error": "unauthorized"})
        url = urlsplit(self.path)
        parts, args, b = url.path.strip("/").split("/"), parse_qs(url.query), self.bridge
        arg = lambda name, default: args.get(name, [default])[0]
        cmd = self.command
        if parts[:2] == ["api", "sessions"]:
            if len(parts) == 2 and cmd == "GET":
                limit, offset = max(1, min(200, int(arg("limit", 100)))), max(0, int(arg("offset", 0)))
                return self.reply(200, {"data": b.sessions(limit, offset, arg("title", None))})
            if len(parts) == 2 and cmd == "POST":
                return self.reply(201, {"session": b.create(str(self.body().get("title") or ""))})
            tid, rest = parts[2], parts[3:]
            if not rest and cmd == "GET":
                return self.reply(200, {"session": b.session(b.thread(tid))})
            if not rest and cmd == "PATCH":
                return self.reply(200, {"session": b.patch(tid, self.body())})
            if not rest and cmd == "DELETE":
                b.rpc.call("thread/delete", {"threadId": tid})
                return self.reply(200, {"ok": True})
            if rest == ["messages"] and cmd == "GET":
                limit, offset = max(1, min(500, int(arg("limit", 120)))), max(0, int(arg("offset", 0)))
                return self.reply(200, {"data": b.messages(tid, limit, offset)})
            if rest == ["fork"] and cmd == "POST":
                return self.reply(200, {"session": b.session(b.rpc.call("thread/fork", {"threadId": tid})["thread"])})
            if rest == ["model"] and cmd == "POST":
                model = str(self.body().get("model") or "").strip()
                data = b.rpc.call("model/list", {})["data"]
                selected = next((m for m in data if model in (m["id"], m.get("model"))), None)
                with b.lock:
                    if model != b.state["model"].get(tid):
                        settings = b.state["settings"].get(tid, {})
                        if settings.get("reasoning_effort") and selected:
                            settings["reasoning_effort"] = selected["defaultReasoningEffort"]
                        else:
                            settings.pop("reasoning_effort", None)
                    b.state["model"][tid] = model
                    b.save()
                return self.reply(200, {"ok": True})
            if rest == ["settings"] and cmd in ("GET", "POST"):
                return self.reply(200, b.settings(tid, self.body() if cmd == "POST" else None))
            if rest == ["command"] and cmd == "POST":
                return self.reply(200, b.command(tid, str(self.body().get("command") or "")))
        if parts == ["v1", "commands"] and cmd == "GET":
            return self.reply(200, {"data": [{"name": n, "description": d} for n, d in COMMANDS]})
        if parts == ["api", "model", "options"] and cmd == "GET":
            return self.reply(200, b.models())
        if parts in (["v1", "capabilities"], ["health"]) and cmd == "GET":
            return self.reply(200, {"platform": "Codex", "status": "ok", "connected": b.rpc.ready.is_set()})
        if parts == ["v1", "runs"] and cmd == "POST":
            body = self.body()
            value, tid = body.get("input"), body.get("session_id")
            if not value or not tid:
                raise ValueError("session_id and input required")
            run = b.start(tid, value, str(body.get("instructions") or ""))
            return self.reply(202, {"run_id": run.id, "status": "running"})
        if len(parts) >= 3 and parts[:2] == ["v1", "runs"]:
            run = b.runs.get(parts[2])
            if run is None:
                return self.reply(404, {"error": "run not found"})
            rest = parts[3:]
            if not rest and cmd == "GET":
                return self.reply(200, {"run_id": run.id, "status": run.status, "output": run.output,
                                        "error": run.error, "session_id": run.thread_id})
            if rest == ["events"] and cmd == "GET":
                return self.events(run)
            if rest == ["stop"] and cmd == "POST":
                b.rpc.call("turn/interrupt", {"threadId": run.thread_id, "turnId": run.id})
                return self.reply(200, {"status": "stopping"})
            if rest == ["steer"] and cmd == "POST":
                body = self.body()
                text = body.get("text") or body.get("message")
                if not isinstance(text, str) or not text.strip():
                    raise ValueError("text required")
                b.steer(run, text)
                return self.reply(200, {"status": "steered"})
            if rest == ["approval"] and cmd == "POST":
                body = self.body()
                choice = str(body.get("choice") or "deny")
                b.approve(run, choice, str(body.get("request_id") or ""))
                return self.reply(200, {"choice": choice})
        return self.reply(501, {"error": "unsupported bridge operation"})

    def handle_route(self):
        try:
            self.route()
        except (ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
            self.reply(400, {"error": {"message": str(exc)}})
        except RpcError as exc:
            msg = str(exc)
            missing = any(s in msg.lower() for s in ("not found", "no thread", "no rollout found"))
            self.reply(404 if missing else 502, {"error": {"message": msg}})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:
            self.reply(500, {"error": {"message": str(exc) or type(exc).__name__}})

    do_GET = do_POST = do_PATCH = do_DELETE = handle_route


def main():
    home = Path.home()
    token = os.environ.get("CODEX_BRIDGE_TOKEN", "")
    token_file = os.environ.get("CODEX_BRIDGE_TOKEN_FILE", "")
    if not token and token_file:
        token = Path(token_file).read_text().strip()
    bridge = Bridge(token, os.environ.get("CODEX_BRIDGE_CWD", str(home)),
                    os.environ.get("CODEX_BRIDGE_STATE", str(home / ".local/share/hermes-companion/codex-bridge.json")),
                    os.environ.get("CODEX_BRIDGE_SOCK", str(home / ".codex/app-server-control/app-server-control.sock")))
    server = ThreadingHTTPServer((os.environ.get("CODEX_BRIDGE_HOST", "127.0.0.1"),
                                  int(os.environ.get("CODEX_BRIDGE_PORT", "8795"))), Handler)
    server.daemon_threads = True
    server.bridge = bridge
    server.serve_forever()


if __name__ == "__main__":
    main()
