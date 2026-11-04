import http.client
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from .codex_bridge import (Bridge, Handler, Rpc, RpcError, RpcUnavailable, Run, ThreadingHTTPServer,
                           MAX_TOOL_OUTPUT, OFFLINE_HINT, capped_output, turn_rows)


class FakeRpc:
    """Stands in for the daemon connection: records calls, answers turn/start and thread reads."""

    def __init__(self):
        self.calls, self.responses, self.errors = [], [], []
        self.turns, self.turn_status, self.turn_errors = 0, {}, {}
        self.ready = threading.Event()
        self.ready.set()
        self.runtime = {"model": "test-model", "reasoningEffort": "high", "sandbox": {"type": "dangerFullAccess"},
                        "approvalPolicy": "never", "cwd": "/tmp/project"}
        self.requirements = None
        self.rate_limits, self.thread_usage, self.fail_methods = {}, {}, set()
        self.model_catalog = [{"id": "test-model", "model": "test-model", "isDefault": True,
                               "defaultReasoningEffort": "medium", "supportedReasoningEfforts": [
                                   {"reasoningEffort": v, "description": "Use " + v} for v in ("low", "medium", "high", "ultra")]},
                              {"id": "new-model", "model": "new-model", "defaultReasoningEffort": "medium",
                               "supportedReasoningEfforts": [{"reasoningEffort": "medium"}]}]

    def call(self, method, params, timeout=60):
        self.calls.append((method, params))
        if method in self.fail_methods:
            raise RpcError("Source unavailable: " + method)
        if method == "turn/start":
            self.turns += 1
            return {"turn": {"id": "turn-" + str(self.turns), "items": [], "status": "inProgress"}}
        if method == "thread/read":
            if params["threadId"] == "missing":
                raise RpcError("thread not found")
            busy = "inProgress" in self.turn_status.values() or any(
                "turn-%d" % i not in self.turn_status for i in range(1, self.turns + 1))
            return {"thread": {"id": params["threadId"], "name": "Fix CI", "updatedAt": 5,
                               "status": {"type": "active", "activeFlags": []} if busy else {"type": "idle"}}}
        if method == "thread/resume":
            return self.runtime
        if method == "thread/turns/list":
            ids = ["turn-" + str(i) for i in range(self.turns, 0, -1)]
            return {"data": [{"id": i, "status": self.turn_status.get(i, "inProgress"),
                              "error": self.turn_errors.get(i)} for i in ids]}
        if method == "model/list":
            return {"data": self.model_catalog}
        if method == "account/rateLimits/read":
            return self.rate_limits
        if method == "account/usage/read":
            return self.thread_usage
        if method == "account/rateLimitResetCredit/consume":
            return {"outcome": "reset"}
        if method == "configRequirements/read":
            return {"requirements": self.requirements}
        return {}

    def respond(self, rid, result):
        self.responses.append((rid, result))

    def respond_error(self, rid, message):
        self.errors.append((rid, message))


def offline():
    raise OSError("no daemon in tests")


class CodexBridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.rpc = FakeRpc()
        with patch("bridge.codex_bridge.Rpc", return_value=self.rpc):
            self.bridge = Bridge("tok", self.tmp.name, Path(self.tmp.name) / "state.json")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.bridge = self.bridge
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def request(self, method, path, body=None, token="tok", headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=10)
        c.request(method, path, json.dumps(body) if body is not None else None,
                  {"Authorization": "Bearer " + token, **(headers or {})})
        r = c.getresponse()
        data = r.read().decode()
        c.close()
        return r.status, data

    def notify(self, method, **params):
        self.bridge.on_message({"method": method, "params": dict(threadId="th", turnId="turn-1", **params)})

    def test_auth_required(self):
        self.assertEqual(401, self.request("GET", "/api/sessions", token="nope")[0])

    def test_turn_streams_as_hermes_run(self):
        status, body = self.request("POST", "/v1/runs", {"session_id": "th", "input": "fix it"})
        self.assertEqual((202, "turn-1"), (status, json.loads(body)["run_id"]))
        self.notify("turn/started")
        self.notify("item/started", item={"type": "commandExecution", "id": "c1", "command": "/bin/bash -lc 'make test'"})
        self.notify("item/completed", item={"type": "commandExecution", "id": "c1", "command": "make test",
                                            "aggregatedOutput": "boom", "exitCode": 2, "status": "failed"})
        self.notify("item/agentMessage/delta", itemId="m1", delta="Fixed")
        self.notify("item/completed", item={"type": "agentMessage", "id": "m1", "text": "Fixed"})
        self.bridge.on_message({"id": 7, "method": "item/fileChange/requestApproval",
                                "params": {"threadId": "th", "turnId": "turn-1", "itemId": "f1", "reason": "edit"}})
        self.assertEqual(200, self.request("POST", "/v1/runs/turn-1/approval", {"choice": "session", "request_id": "7"})[0])
        self.assertEqual([(7, {"decision": "acceptForSession"})], self.rpc.responses)
        self.notify("turn/completed", turn={"id": "turn-1", "status": "completed"})
        status, stream = self.request("GET", "/v1/runs/turn-1/events")
        events = [json.loads(line[5:]) for line in stream.splitlines() if line.startswith("data:")]
        names = [e["event"] for e in events]
        self.assertEqual(["run.started", "tool.started", "tool.failed", "assistant.delta",
                          "approval.request", "approval.resolved", "run.completed"], names)
        self.assertEqual("make test", events[1]["preview"])
        self.assertEqual(["c1", "c1"], [e["item_id"] for e in events if e["event"].startswith("tool.")])
        self.assertEqual("Fixed", events[-1]["output"])
        self.assertEqual("completed", json.loads(self.request("GET", "/v1/runs/turn-1")[1])["status"])

    def test_phone_instructions_and_images_reach_the_turn_without_showing_in_history(self):
        png = "data:image/png;base64,iVBORw0KGgo="
        body = {"session_id": "th", "instructions": "Send files as MEDIA:/path", "input": [
            {"type": "text", "text": "what is this"}, {"type": "image_url", "image_url": {"url": png}}]}
        self.assertEqual(202, self.request("POST", "/v1/runs", body)[0])
        items = [p for m, p in self.rpc.calls if m == "turn/start"][0]["input"]
        self.assertTrue(items[0]["text"].startswith("<phone_context>\nSend files as MEDIA:/path\n</phone_context>\n\nwhat is this"))
        self.assertEqual({"type": "image", "url": png}, items[1])
        self.assertEqual(400, self.request("POST", "/v1/runs", {"session_id": "th", "input": [{"type": "image_url", "image_url": "http://x/y.png"}]})[0])
        rows = turn_rows({"items": [{"type": "userMessage", "id": "u1", "content": [
            {"type": "text", "text": items[0]["text"]}, {"type": "image", "url": png}]}]})
        self.assertEqual([{"type": "text", "text": "what is this"}, {"type": "image_url", "image_url": png}], rows[0]["content"])

    def test_command_output_streams_into_the_running_tool(self):
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "build"})
        self.notify("item/commandExecution/outputDelta", itemId="c1", delta="compiling\n")
        run = self.bridge.runs["turn-1"]
        with run.cond:
            self.assertTrue(run.cond.wait_for(lambda: bool(run.events), timeout=2))
        events = [e for e in run.events if e["event"] == "tool.output"]
        self.assertEqual([("terminal", "compiling\n")], [(e["tool_name"], e["preview"]) for e in events])
        self.assertEqual(["c1"], [e["item_id"] for e in events])

    def test_output_burst_is_bounded_coalesced_and_identical_after_reload_and_replay(self):
        run = self.start_run()
        self.notify("item/started", item={"type": "commandExecution", "id": "c1", "command": "build"})
        chunks = ["head\n"] + ["%04d:" % i + "x" * 100 for i in range(2000)] + ["\ntail"]
        # Keep the timer from firing until the burst ends; completion must flush it itself.
        with patch("bridge.codex_bridge.threading.Timer"):
            for chunk in chunks:
                self.notify("item/commandExecution/outputDelta", itemId="c1", delta=chunk)
        expected = capped_output("".join(chunks))
        self.assertEqual(expected, run.items["c1"]["aggregatedOutput"])
        self.assertLessEqual(len(run.output_totals["c1"][0]), MAX_TOOL_OUTPUT)
        self.assertLessEqual(len(run.output_pending["c1"][0]), MAX_TOOL_OUTPUT)
        self.notify("item/completed", item={"type": "commandExecution", "id": "c1", "command": "build",
                                          "aggregatedOutput": "".join(chunks), "exitCode": 0, "status": "completed"})
        self.notify("turn/completed", turn={"id": run.id, "status": "completed"})
        outputs = [e for e in run.events if e["event"] == "tool.output"]
        self.assertEqual(1, len(outputs))
        self.assertEqual(expected, outputs[0]["preview"])
        final = next(e for e in run.events if e["event"] == "tool.completed")
        self.assertEqual(expected, final["preview"])
        self.assertTrue(all(e["item_id"] == "c1" for e in run.events if e["event"].startswith("tool.")))
        self.assertEqual(expected, json.loads(turn_rows({"items": [run.items["c1"]]})[-1]["content"])["output"])
        # History reads the daemon's original, uncapped item too.
        original = {**run.items["c1"], "aggregatedOutput": "".join(chunks)}
        self.assertEqual(expected, json.loads(turn_rows({"items": [original]})[-1]["content"])["output"])
        self.assertTrue(expected.startswith("head\n"))
        self.assertTrue(expected.endswith("\ntail"))
        self.assertIn("%d characters omitted" % (len("".join(chunks)) - MAX_TOOL_OUTPUT), expected)
        before = [dict(e) for e in run.events]
        _, stream = self.request("GET", "/v1/runs/" + run.id + "/events",
                                 headers={"Last-Event-ID": str(outputs[0]["seq"])})
        replay = [json.loads(line[5:]) for line in stream.splitlines() if line.startswith("data:")]
        self.assertEqual(before[outputs[0]["seq"] + 1:], replay)
        self.assertEqual(list(range(len(run.events))), [e["seq"] for e in run.events])
        self.assertEqual(before, run.events)
        self.assertIsNone(run.output_timer)
        self.assertEqual({}, run.output_pending)

    def test_file_change_output_uses_the_same_bounded_stream_and_history(self):
        run = self.start_run()
        self.notify("item/started", item={"type": "fileChange", "id": "patch1", "changes": []})
        text = "head" + "x" * 100_000 + "tail"
        with patch("bridge.codex_bridge.threading.Timer"):
            self.notify("item/fileChange/outputDelta", itemId="patch1", delta=text)
        # The daemon persists the diff, not patch-application logs. Completion must match that history.
        diff = "+" + "changed line\n" * 4000
        completed = {"type": "fileChange", "id": "patch1", "changes": [{"path": "file", "diff": diff}], "status": "completed"}
        self.notify("item/completed", item=completed)
        output = next(e for e in run.events if e["event"] == "tool.output")
        self.assertEqual("patch", output["tool_name"])
        self.assertEqual(capped_output(text), output["preview"])
        self.assertEqual(capped_output(diff), run.events[-1]["preview"])
        self.assertEqual(capped_output(diff), json.loads(turn_rows({"items": [completed]})[-1]["content"])["diff"])

    def test_output_continues_from_an_attached_item_without_losing_omission_count(self):
        run = self.start_run()
        initial = "head" + "x" * 100_000
        self.notify("item/started", item={"type": "commandExecution", "id": "c1", "command": "build",
                                        "aggregatedOutput": initial})
        with patch("bridge.codex_bridge.threading.Timer"):
            run.tool_delta("c1", "tail")
            run.flush_output()
        self.assertEqual(capped_output(initial + "tail"), run.items["c1"]["aggregatedOutput"])

    def test_output_replay_storage_stays_bounded_across_many_flushes(self):
        run = self.start_run()
        original = ""
        with patch("bridge.codex_bridge.threading.Timer"):
            for i in range(100):
                chunk = str(i) + "x" * 2000
                original += chunk
                run.tool_delta("c1", chunk)
                run.flush_output()
        outputs = [e for e in run.events if e["event"] == "tool.output"]
        self.assertLessEqual(sum(len(e["preview"]) for e in outputs), MAX_TOOL_OUTPUT)
        self.assertEqual(capped_output(original), run.items["c1"]["aggregatedOutput"])
        self.assertEqual({}, run.output_pending)
        self.assertIsNone(run.output_timer)
        self.notify("item/completed", item={"type": "commandExecution", "id": "c1", "status": "completed"})
        self.assertEqual(capped_output(original), run.events[-1]["preview"])
        self.assertEqual(capped_output(original), json.loads(turn_rows({"items": [run.items["c1"]]})[-1]["content"])["output"])

    def test_output_flushes_each_item_and_at_turn_end_without_completion(self):
        run = self.start_run()
        with patch("bridge.codex_bridge.threading.Timer"):
            self.notify("item/commandExecution/outputDelta", itemId="one", delta="first")
            self.notify("item/commandExecution/outputDelta", itemId="two", delta="second")
            run.finish("failed", "stopped")
        self.assertEqual([("one", "first"), ("two", "second")],
                         [(e["item_id"], e["preview"]) for e in run.events if e["event"] == "tool.output"])
        self.assertEqual("run.failed", run.events[-1]["event"])
        self.assertEqual([("one", "first"), ("two", "second")],
                         [(e["item_id"], e["preview"]) for e in run.events if e["event"] == "tool.failed"])
        self.assertEqual({}, run.output_totals)
        before = list(run.events)
        run.tool_delta("one", "late")
        self.assertEqual(before, run.events)

    def test_offline_hint_reaches_active_and_attached_runs_and_new_runs_fail_fast(self):
        run = self.start_run()
        self.rpc.ready.clear()
        self.bridge.on_lost()
        self.bridge.on_ready()  # A delayed callback from the previous connection must not hide the outage.
        self.assertTrue(self.bridge.daemon_offline)
        self.assertEqual(("assistant.commentary", OFFLINE_HINT), (run.events[-1]["event"], run.events[-1]["text"]))
        attached = self.bridge.run_for("another", "another-turn")
        self.assertEqual(OFFLINE_HINT, attached.events[-1]["text"])
        before = list(self.rpc.calls)
        start = time.monotonic()
        status, body = self.request("POST", "/v1/runs", {"session_id": "th", "input": "private prompt"})
        self.assertEqual(503, status)
        self.assertEqual(OFFLINE_HINT, json.loads(body)["error"]["message"])
        self.assertLess(time.monotonic() - start, 4.5)  # 3 s reconnect grace, far under the app timeout
        self.assertEqual(before, self.rpc.calls)
        self.assertEqual("running", run.status)
        self.rpc.ready.set()
        self.bridge.on_ready()
        self.assertFalse(self.bridge.daemon_offline)
        self.assertEqual(202, self.request("POST", "/v1/runs", {"session_id": "th", "input": "go"})[0])

    def test_rpc_logs_one_loss_and_restore_without_payloads(self):
        class StopLoop(BaseException):
            pass
        class Socket:
            def __init__(self, error):
                self.messages = iter([json.dumps({"id": "init"}), error])
            def send(self, data):
                pass
            def recv(self, timeout=None):
                value = next(self.messages)
                if isinstance(value, BaseException):
                    raise value
                return value
            def close(self):
                pass
        attempts = iter([Socket(OSError("SECRET token prompt")), OSError("SECRET auth header"), Socket(StopLoop())])
        def connect():
            value = next(attempts)
            if isinstance(value, Exception):
                raise value
            return value
        lost = []
        with patch("bridge.codex_bridge.threading.Thread.start"), patch("bridge.codex_bridge.time.sleep"), \
                self.assertLogs("bridge.codex_bridge", level="DEBUG") as logs:
            rpc = Rpc("unused", lambda m: None, connect=connect, on_lost=lambda: lost.append(True))
            with self.assertRaises(StopLoop):
                rpc._loop()
        output = "\n".join(logs.output)
        self.assertEqual(1, output.count("connection lost"))
        self.assertEqual(1, output.count("connection restored"))
        self.assertIn("attempt 2", output)
        self.assertIn("reconnect delay 2s", output)
        self.assertNotIn("SECRET", output)
        self.assertEqual([True], lost)
        self.assertTrue(rpc.ready.is_set())
        rpc.ready.clear()
        with self.assertRaisesRegex(RpcUnavailable, "reconnecting"):
            rpc.call("turn/start", {"input": "SECRET"})

    def test_swallowed_notification_and_settle_errors_are_logged_without_message_text(self):
        def broken(msg):
            raise ValueError("SECRET prompt")
        with patch("bridge.codex_bridge.threading.Thread.start"):
            rpc = Rpc("unused", broken)
        with self.assertLogs("bridge.codex_bridge", level="WARNING") as logs:
            rpc._dispatch({"method": "anything", "params": {"text": "SECRET"}})
            self.rpc.fail_methods.add("thread/turns/list")
            self.bridge.settle(self.start_run())
        self.assertIn("notification handler failed (ValueError)", "\n".join(logs.output))
        self.assertIn("Could not settle Codex run (RpcError)", "\n".join(logs.output))
        self.assertNotIn("SECRET", "\n".join(logs.output))

    def test_slash_commands_run_natively_or_pass_through(self):
        names = [c["name"] for c in json.loads(self.request("GET", "/v1/commands")[1])["data"]]
        self.assertIn("compact", names)
        post = lambda text: json.loads(self.request("POST", "/api/sessions/th/command", {"command": text})[1])
        self.assertEqual("notice", post("/compact")["type"])
        self.assertIn(("thread/compact/start", {"threadId": "th"}), self.rpc.calls)
        self.assertEqual({"type": "send", "message": "/frobnicate now"}, post("/frobnicate now"))

    def test_steer_and_stop_reach_codex(self):
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "go"})
        self.assertEqual(200, self.request("POST", "/v1/runs/turn-1/steer", {"text": "use port 80"})[0])
        self.assertEqual(200, self.request("POST", "/v1/runs/turn-1/stop", {})[0])
        methods = [m for m, _ in self.rpc.calls]
        self.assertEqual(["thread/resume", "turn/start", "turn/steer", "turn/interrupt", "thread/turns/list"], methods)
        self.assertEqual("turn-1", self.rpc.calls[2][1]["expectedTurnId"])

    def test_a_turn_codex_already_ended_is_not_followed_again(self):
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "go"})
        self.assertEqual("turn-1", json.loads(self.request("GET", "/api/sessions/th")[1])["session"]["active_run_id"])
        self.rpc.turn_status["turn-1"] = "interrupted"  # its turn/completed never arrived
        self.assertEqual("", json.loads(self.request("GET", "/api/sessions/th")[1])["session"]["active_run_id"])
        self.assertEqual("cancelled", self.bridge.runs["turn-1"].status)

    def test_title_and_preview_skip_the_phone_context(self):
        t = {"id": "th", "preview": "<phone_context>\nPHONE CONTEXT ...\n</phone_context>\n\nPlan my trip to Rome\nmore", "status": {"type": "idle"}}
        s = self.bridge.session(t)
        self.assertEqual("Plan my trip to Rome", s["title"])
        self.assertTrue(s["preview"].startswith("Plan my trip"))

    def test_session_flags_persist_locally(self):
        status, body = self.request("PATCH", "/api/sessions/th", {"pinned": True})
        self.assertTrue(json.loads(body)["session"]["pinned"])
        self.assertEqual({"th": True}, json.loads((Path(self.tmp.name) / "state.json").read_text())["pinned"])

    def test_turn_rows_mark_steers_and_pair_tools(self):
        rows = turn_rows({"startedAt": 9, "items": [
            {"type": "userMessage", "id": "u1", "content": [{"type": "text", "text": "build"}]},
            {"type": "commandExecution", "id": "c1", "command": "ls", "aggregatedOutput": "a", "exitCode": 0},
            {"type": "userMessage", "id": "u2", "content": [{"type": "text", "text": "faster"}]},
            {"type": "agentMessage", "id": "m1", "text": "done"}]})
        self.assertEqual(["user", "assistant", "tool", "user", "assistant"], [r["role"] for r in rows])
        self.assertEqual("c1", rows[2]["tool_call_id"])
        self.assertTrue(rows[3]["content"].startswith("[OUT-OF-BAND USER MESSAGE]\nfaster"))

    def test_settings_reach_next_turn_and_persist_without_changing_running_turn(self):
        status, body = self.request("GET", "/api/sessions/th/settings")
        self.assertEqual(200, status)
        settings = json.loads(body)
        self.assertEqual("high", settings["reasoning_effort"])
        self.assertEqual("full-access", settings["permission_mode"])
        self.assertEqual(["low", "medium", "high", "ultra"], [o["value"] for o in settings["reasoning_options"]])
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "first"})
        self.rpc.calls.clear()
        self.assertEqual(200, self.request("POST", "/api/sessions/th/settings",
                         {"reasoning_effort": "ultra", "permission_mode": "read-only"})[0])
        self.assertNotIn("turn/start", [m for m, _ in self.rpc.calls])
        self.assertEqual("running", self.bridge.runs["turn-1"].status)
        saved = json.loads((Path(self.tmp.name) / "state.json").read_text())["settings"]["th"]
        self.assertEqual({"reasoning_effort": "ultra", "permission_mode": "read-only"}, saved)
        self.notify("turn/completed", turn={"id": "turn-1", "status": "completed"})
        restored = Bridge("tok", self.tmp.name, Path(self.tmp.name) / "state.json", connect=offline)
        restored.rpc = self.rpc
        self.server.bridge = self.bridge = restored
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "next"})
        params = next(p for m, p in reversed(self.rpc.calls) if m == "turn/start")
        self.assertEqual("ultra", params["effort"])
        self.assertEqual({"type": "readOnly", "networkAccess": False}, params["sandboxPolicy"])
        self.assertEqual("on-request", params["approvalPolicy"])
        self.assertEqual("user", params["approvalsReviewer"])
        self.request("POST", "/v1/runs", {"session_id": "other", "input": "separate chat"})
        other = next(p for m, p in reversed(self.rpc.calls) if m == "turn/start")
        self.assertNotIn("effort", other)
        self.assertNotIn("sandboxPolicy", other)

    def test_settings_validate_atomically_and_honor_managed_requirements(self):
        self.rpc.requirements = {"allowedSandboxModes": ["read-only", "workspace-write"],
                                 "allowedApprovalPolicies": ["on-request"]}
        settings = json.loads(self.request("GET", "/api/sessions/th/settings")[1])
        self.assertNotIn("full-access", [o["value"] for o in settings["permission_options"]])
        for changes in ({"reasoning_effort": "unknown"}, {"permission_mode": "full-access"},
                        {"reasoning_effort": "low", "permission_mode": "invalid"},
                        {"sandboxPolicy": {"type": "dangerFullAccess"}}, {"reasoning_effort": []}):
            self.assertEqual(400, self.request("POST", "/api/sessions/th/settings", changes)[0])
            self.assertNotIn("th", self.bridge.state["settings"])
        self.assertEqual(401, self.request("POST", "/api/sessions/th/settings",
                         {"permission_mode": "full-access"}, token="bad")[0])

    def test_effort_updates_collaboration_mode_and_model_change_uses_new_default(self):
        self.rpc.runtime["collaborationMode"] = {"mode": "plan", "settings": {
            "model": "test-model", "reasoning_effort": "high", "developer_instructions": "Keep planning"}}
        self.request("POST", "/api/sessions/th/settings", {"reasoning_effort": "low", "permission_mode": "workspace-write"})
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "go"})
        params = next(p for m, p in reversed(self.rpc.calls) if m == "turn/start")
        self.assertEqual("low", params["collaborationMode"]["settings"]["reasoning_effort"])
        self.assertEqual("Keep planning", params["collaborationMode"]["settings"]["developer_instructions"])
        self.request("POST", "/api/sessions/th/model", {"model": "new-model"})
        self.assertEqual({"permission_mode": "workspace-write", "reasoning_effort": "medium"}, self.bridge.state["settings"]["th"])

    def test_new_threads_use_start_settings_until_the_first_rollout_exists(self):
        self.bridge.runtime["new"] = dict(self.rpc.runtime)
        call = self.rpc.call
        def missing_rollout(method, params, timeout=60):
            if method == "thread/resume":
                raise RpcError("no rollout found for thread id " + params["threadId"])
            return call(method, params, timeout)
        self.rpc.call = missing_rollout
        self.assertEqual(200, self.request("GET", "/api/sessions/new/settings")[0])
        self.assertEqual(404, self.request("GET", "/api/sessions/missing/settings")[0])

    def test_failed_save_never_enables_unsaved_permissions(self):
        def disk_full():
            raise OSError("disk full")
        self.bridge.save = disk_full
        self.assertEqual(500, self.request("POST", "/api/sessions/th/settings", {"permission_mode": "full-access"})[0])
        self.assertNotIn("th", self.bridge.state["settings"])

    def test_completed_items_fill_missing_deltas_without_duplicates(self):
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "go"})
        self.notify("item/agentMessage/delta", itemId="m1", delta="Checking")
        self.notify("item/completed", item={"type": "agentMessage", "id": "m1", "text": "Checking files"})
        self.notify("item/completed", item={"type": "agentMessage", "id": "m1", "text": "Checking files"})
        self.notify("item/completed", item={"type": "agentMessage", "id": "m2", "text": "Done"})
        self.notify("turn/completed", turn={"id": "turn-1", "status": "completed"})
        events = self.bridge.runs["turn-1"].events
        self.assertEqual("Checking files\n\nDone", "".join(e.get("delta", "") for e in events))
        self.assertEqual("Done", events[-1]["output"])
        self.assertEqual(list(range(len(events))), [e["seq"] for e in events])

    def test_messages_are_segments_and_steers_from_any_client_show_once(self):
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "go"})
        self.notify("item/started", item={"type": "userMessage", "id": "u1", "content": [{"type": "text", "text": "go"}]})
        self.notify("item/completed", item={"type": "agentMessage", "id": "m1", "text": "On it"})
        self.request("POST", "/v1/runs/turn-1/steer", {"text": "use port 80"})
        self.notify("item/started", item={"type": "userMessage", "id": "u2", "content": [{"type": "text", "text": "use port 80"}]})
        self.notify("item/started", item={"type": "userMessage", "id": "u3", "content": [{"type": "text", "text": "from tty"}]})
        self.notify("item/completed", item={"type": "agentMessage", "id": "m2", "text": "Done"})
        events = self.bridge.runs["turn-1"].events
        self.assertEqual(["use port 80", "from tty"], [e["text"] for e in events if e["event"] == "run.steered"])
        self.assertEqual([True], [e["segment"] for e in events if "segment" in e])

    def test_live_sse_delivers_before_completion_and_reconnect_replays_only_tail(self):
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "go"})
        c = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=2)
        c.request("GET", "/v1/runs/turn-1/events", headers={"Authorization": "Bearer tok"})
        stream = c.getresponse()
        self.notify("item/agentMessage/delta", itemId="m1", delta="First ")
        self.assertEqual(b"id: 0\n", stream.readline())
        first = json.loads(stream.readline()[5:])
        self.assertEqual("First ", first["delta"])
        self.assertEqual("running", self.bridge.runs["turn-1"].status)
        self.notify("item/agentMessage/delta", itemId="m1", delta="second")
        self.notify("item/completed", item={"type": "agentMessage", "id": "m1", "text": "First second"})
        self.notify("turn/completed", turn={"id": "turn-1", "status": "completed"})
        stream.read()
        c.close()
        status, replay = self.request("GET", "/v1/runs/turn-1/events", headers={"Last-Event-ID": "0"})
        events = [json.loads(line[5:]) for line in replay.splitlines() if line.startswith("data:")]
        self.assertEqual(200, status)
        self.assertEqual([1, 2], [e["seq"] for e in events])
        self.assertEqual("second", events[0]["delta"])
        self.assertEqual("First second", events[-1]["output"])

    # ── hardening regressions ──────────────────────────────────────────────────────────────────

    def start_run(self):
        self.request("POST", "/v1/runs", {"session_id": "th", "input": "go"})
        return self.bridge.runs["turn-1"]

    def test_server_requests_the_phone_cannot_answer_get_an_error_not_silence(self):
        self.start_run()
        self.bridge.on_message({"id": 9, "method": "item/tool/requestUserInput", "params": {"threadId": "th", "turnId": "turn-1"}})
        self.bridge.on_message({"id": 10, "method": "item/commandExecution/requestApproval", "params": {"threadId": "th"}})
        self.assertEqual([9, 10], [rid for rid, _ in self.rpc.errors])
        self.assertEqual({}, self.bridge.runs["turn-1"].approvals)

    def ask_questions(self, questions=None, rpc_id=9):
        self.bridge.on_message({"id": rpc_id, "method": "item/tool/requestUserInput", "params": {
            "threadId": "th", "turnId": "turn-1", "itemId": "ask1", "isBlocking": True,
            "questions": questions if questions is not None else [{"id": "env", "header": "Environment",
                "question": "Which environment?", "options": [{"label": "Test", "description": "Test safely"},
                                                            {"label": "Local", "description": "Run locally"}]}]}})

    def test_questions_show_sequential_cards_and_reply_once_with_all_answers(self):
        run = self.start_run()
        self.ask_questions([
            {"id": "env", "header": "Environment", "question": "Which environment?", "options": [
                {"label": "Test", "description": "Test safely"}, {"label": "Local", "description": "Run locally"}]},
            {"id": "detail", "header": "Details", "question": "Any details?", "options": None}])
        first = run.events[-1]
        self.assertEqual("clarify.request", first["event"])
        self.assertEqual(["Test", "Local"], first["choices"])
        self.assertIn("Test safely", first["question"])
        self.assertFalse(first["multi_select"])
        self.assertEqual({}, run.approvals)
        route = "/v1/runs/turn-1/clarify"
        self.assertEqual(200, self.request("POST", route, {"request_id": first["prompt_id"], "answer": "Test"})[0])
        second = run.events[-1]
        self.assertEqual("Any details?", second["question"])
        self.assertEqual([], second["choices"])
        self.assertNotEqual(first["prompt_id"], second["prompt_id"])
        self.assertEqual([], self.rpc.responses)
        self.assertEqual(404, self.request("POST", route, {"request_id": first["prompt_id"], "answer": "Local"})[0])
        self.assertEqual(200, self.request("POST", route, {"request_id": second["prompt_id"], "answer": " Custom detail "})[0])
        self.assertEqual([(9, {"answers": {"env": {"answers": ["Test"]}, "detail": {"answers": ["Custom detail"]}}})],
                         self.rpc.responses)
        self.assertEqual({}, run.questions)
        self.assertEqual(["clarify.request", "clarify.resolved", "clarify.request", "clarify.resolved"],
                         [e["event"] for e in run.events])

    def test_question_route_validates_answers_ids_run_state_and_auth(self):
        run = self.start_run()
        self.ask_questions()
        qid = next(iter(run.questions))
        route = "/v1/runs/turn-1/clarify"
        for body in ({}, [], {"request_id": qid, "answer": " "}, {"request_id": qid, "answer": []},
                     {"request_id": 9, "answer": "Test"}):
            self.assertEqual(400, self.request("POST", route, body)[0], body)
        self.assertEqual(401, self.request("POST", route, {"request_id": qid, "answer": "Test"}, token="bad")[0])
        self.assertEqual(404, self.request("POST", route, {"request_id": "missing", "answer": "Test"})[0])
        self.assertEqual(404, self.request("POST", "/v1/runs/missing/clarify", {"request_id": qid, "answer": "Test"})[0])
        self.notify("turn/completed", turn={"id": "turn-1", "status": "completed"})
        self.assertEqual({}, run.questions)
        self.assertEqual(409, self.request("POST", route, {"request_id": qid, "answer": "Test"})[0])
        self.assertEqual("clarify.resolved", run.events[-2]["event"])
        self.assertEqual([], self.rpc.responses)

    def test_question_answer_can_be_retried_after_connection_failure(self):
        run = self.start_run()
        self.ask_questions()
        qid = next(iter(run.questions))
        respond = self.rpc.respond
        def broken(rid, result):
            raise OSError("socket closed")
        self.rpc.respond = broken
        route = "/v1/runs/turn-1/clarify"
        body = {"request_id": qid, "answer": "Custom answer"}
        self.assertEqual(502, self.request("POST", route, body)[0])
        self.assertIn(qid, run.questions)
        self.assertEqual("clarify.request", run.events[-1]["event"])
        self.rpc.respond = respond
        self.assertEqual(200, self.request("POST", route, body)[0])
        self.assertEqual([(9, {"answers": {"env": {"answers": ["Custom answer"]}}})], self.rpc.responses)
        self.assertEqual(404, self.request("POST", route, body)[0])

    def test_question_cards_withdraw_on_server_resolution_stop_and_reconnect(self):
        run = self.start_run()
        for action in ("resolve", "stop", "reconnect"):
            self.ask_questions(rpc_id="ask/9")
            qid = next(iter(run.questions))
            if action == "resolve":
                self.bridge.on_message({"method": "serverRequest/resolved", "params": {"threadId": "th", "requestId": "ask/9"}})
            elif action == "stop":
                self.assertEqual(200, self.request("POST", "/v1/runs/turn-1/stop", {})[0])
            else:
                self.bridge.on_ready()
            self.assertEqual({}, run.questions, action)
            self.assertEqual(("clarify.resolved", qid), (run.events[-1]["event"], run.events[-1]["request_id"]))
            self.assertEqual(404, self.request("POST", "/v1/runs/turn-1/clarify", {"request_id": qid, "answer": "Test"})[0])

    def test_bad_question_batches_and_elicitations_are_rejected_without_cards(self):
        run = self.start_run()
        question = {"id": "env", "question": "Which environment?"}
        for questions in ([], [question, question], [question, {"id": "bad", "question": ""}],
                          [{**question, "isSecret": True}], [{**question, "options": ["Test"]}]):
            self.ask_questions(questions)
            self.assertEqual({}, run.questions)
        self.bridge.on_message({"id": 10, "method": "mcpServer/elicitation/request", "params": {
            "threadId": "th", "turnId": "turn-1", "mode": "form", "message": "Details?",
            "requestedSchema": {"type": "object", "properties": {"age": {"type": "integer"}}}}})
        self.assertEqual([9, 9, 9, 9, 9, 10], [rid for rid, _ in self.rpc.errors])
        self.assertEqual([], run.events)

    def test_failed_turn_reports_the_codex_reason_with_a_fallback(self):
        run = self.start_run()
        self.notify("error", error={"message": "You hit your usage limit"}, willRetry=False)
        self.notify("turn/completed", turn={"id": "turn-1", "status": "failed"})
        self.assertEqual("You hit your usage limit", run.error)
        self.bridge.run_for("th", "turn-2")
        self.bridge.on_message({"method": "turn/completed", "params": {"threadId": "th", "turn": {
            "id": "turn-2", "status": "failed", "error": {"message": "", "codexErrorInfo": "usageLimitExceeded"}}}})
        self.assertIn("usage limit", self.bridge.runs["turn-2"].error)
        self.bridge.on_message({"method": "turn/completed", "params": {"threadId": "th", "turn": {"id": "turn-3", "status": "failed"}}})
        self.assertTrue(self.bridge.runs["turn-3"].error)

    def test_a_turn_that_died_with_the_daemon_ends_the_run(self):
        run = self.start_run()
        self.rpc.turn_status["turn-1"] = "inProgress"  # the rollout never recorded an end
        orig = self.rpc.call
        self.rpc.call = lambda m, p, timeout=60: ({"thread": {"id": "th", "status": {"type": "idle"}}} if m == "thread/read" else orig(m, p, timeout))
        self.bridge.settle(run)
        self.assertEqual("running", run.status)  # a brand new turn gets a grace period
        run.started_at -= 60
        self.bridge.on_message({"method": "thread/status/changed", "params": {"threadId": "th", "status": {"type": "idle"}}})
        for _ in range(100):
            if run.status != "running":
                break
            threading.Event().wait(0.02)
        self.assertEqual("failed", run.status)
        self.assertIn("stopped", run.error)

    def test_reconnect_settles_runs_even_when_resubscribing_fails_and_drops_stale_approvals(self):
        run = self.start_run()
        self.bridge.on_message({"id": 7, "method": "item/fileChange/requestApproval",
                                "params": {"threadId": "th", "turnId": "turn-1", "itemId": "f1"}})
        self.rpc.turn_status["turn-1"] = "interrupted"
        orig = self.rpc.call
        def gone(method, params, timeout=60):
            if method == "thread/resume":
                raise RpcError("thread not found")
            return orig(method, params, timeout)
        self.rpc.call = gone
        self.bridge.on_ready()
        self.assertEqual("cancelled", run.status)
        self.assertEqual({}, run.approvals)
        self.assertEqual(["approval.request", "approval.resolved"], [e["event"] for e in run.events if e["event"].startswith("approval")])

    def test_finished_run_withdraws_pending_approvals_and_refuses_steers(self):
        run = self.start_run()
        self.bridge.on_message({"id": 7, "method": "item/fileChange/requestApproval",
                                "params": {"threadId": "th", "turnId": "turn-1", "itemId": "f1"}})
        self.notify("turn/completed", turn={"id": "turn-1", "status": "completed"})
        self.assertIn("approval.resolved", [e["event"] for e in run.events])
        self.assertEqual(400, self.request("POST", "/v1/runs/turn-1/steer", {"text": "more"})[0])
        self.assertNotIn("turn/steer", [m for m, _ in self.rpc.calls])

    def test_approval_survives_a_failed_answer_and_stop_reports_a_lost_connection(self):
        run = self.start_run()
        self.bridge.on_message({"id": 7, "method": "item/fileChange/requestApproval",
                                "params": {"threadId": "th", "turnId": "turn-1", "itemId": "f1"}})
        def broken(rid, result):
            raise OSError("socket closed")
        self.rpc.respond = broken
        self.assertEqual(502, self.request("POST", "/v1/runs/turn-1/approval", {"choice": "once", "request_id": "7"})[0])
        self.assertIn("7", run.approvals)
        orig = self.rpc.call
        def lost(method, params, timeout=60):
            if method == "turn/interrupt":
                raise RpcError("Codex daemon connection lost")
            return orig(method, params, timeout)
        self.rpc.call = lost
        self.assertEqual(502, self.request("POST", "/v1/runs/turn-1/stop", {})[0])

    def test_rpc_send_failure_is_an_rpc_error_and_leaves_no_pending_slot(self):
        class Dead:
            def send(self, data):
                if '"initialize"' not in data and "initialized" not in data:
                    raise OSError("closed")
            def recv(self, timeout=None):
                if not getattr(self, "sent", False):
                    self.sent = True
                    return json.dumps({"id": "init", "result": {}})
                threading.Event().wait(5)
            def close(self):
                pass
        rpc = Rpc("x", lambda m: None, connect=Dead)
        self.assertTrue(rpc.ready.wait(5))
        with self.assertRaises(RpcError):
            rpc.call("thread/list", {})
        self.assertEqual({}, rpc.pending)

    def test_delete_forgets_local_state_and_archive_answers_even_if_the_thread_is_unreadable(self):
        self.request("PATCH", "/api/sessions/th", {"pinned": True})
        orig = self.rpc.call
        def unreadable(method, params, timeout=60):
            if method == "thread/read":
                raise RpcError("thread is archived")
            return orig(method, params, timeout)
        self.rpc.call = unreadable
        status, body = self.request("PATCH", "/api/sessions/th", {"archived": True})
        self.assertEqual(200, status)
        self.assertTrue(json.loads(body)["session"]["archived"])
        self.assertEqual(200, self.request("DELETE", "/api/sessions/th")[0])
        self.assertEqual({}, self.bridge.state["pinned"])

    def test_large_image_bodies_are_accepted(self):
        big = "data:image/jpeg;base64," + "A" * 3_000_000
        self.assertEqual(202, self.request("POST", "/v1/runs", {"session_id": "th", "input": [
            {"type": "text", "text": "look"}, {"type": "image_url", "image_url": big}]})[0])

    def token_usage(self):
        return {"last": {"totalTokens": 1000}, "modelContextWindow": 200000,
                "total": {"inputTokens": 4000, "outputTokens": 500, "cachedInputTokens": 3000,
                          "reasoningOutputTokens": 200, "totalTokens": 4500}}

    def rate_snapshot(self):
        return {"limitId": "codex", "limitName": "Codex", "planType": "pro",
                "primary": {"usedPercent": 42, "resetsAt": 1762260000, "windowDurationMins": 300},
                "secondary": {"usedPercent": 12, "resetsAt": 1762860000, "windowDurationMins": 10080}}

    def get_usage(self, refresh=False):
        status, body = self.request("GET", "/api/sessions/th/usage" + ("?refresh=1" if refresh else ""))
        self.assertEqual(200, status, body)
        return json.loads(body)

    def test_token_usage_reaches_http_live_events_and_survives_restart(self):
        run = self.start_run()
        self.notify("thread/tokenUsage/updated", tokenUsage=self.token_usage())
        data = self.get_usage()
        self.assertEqual({"used_tokens": 1000, "window_tokens": 200000, "percent": 0.5, "source": "live"}, data["context"])
        self.assertEqual({"input_tokens": 4000, "output_tokens": 500, "cached_input_tokens": 3000,
                          "reasoning_tokens": 200}, data["session"])
        self.assertEqual("usage.updated", run.events[-1]["event"])
        self.assertEqual(data["context"], run.events[-1]["context"])
        path = Path(self.tmp.name) / "state.json"
        self.assertEqual(self.token_usage()["total"], json.loads(path.read_text())["usage"]["th"]["total"])
        with patch("bridge.codex_bridge.Rpc", return_value=self.rpc):
            reopened = Bridge("tok", self.tmp.name, path)
        self.server.bridge = reopened
        restored = self.get_usage()
        self.assertEqual(data["session"], restored["session"])
        self.assertEqual(1000, restored["context"]["used_tokens"])
        self.assertEqual("last_turn", restored["context"]["source"])

    def test_compaction_marks_estimate_until_the_next_token_update(self):
        run = self.start_run()
        self.notify("thread/tokenUsage/updated", tokenUsage=self.token_usage())
        self.bridge.on_message({"method": "thread/compacted", "params": {"threadId": "th"}})
        self.assertEqual("estimate", run.events[-1]["context"]["source"])
        self.assertEqual("estimate", self.get_usage()["context"]["source"])
        self.notify("thread/tokenUsage/updated", tokenUsage=self.token_usage())
        self.assertEqual("live", self.get_usage()["context"]["source"])

    def test_account_notification_without_thread_id_emits_to_all_active_runs(self):
        first = self.start_run()
        second = self.bridge.start("other", "go")
        done = self.bridge.run_for("finished", "old-turn")
        done.finish("completed")
        before = list(self.rpc.calls)
        self.bridge.on_message({"method": "account/rateLimits/updated", "params": {"rateLimits": self.rate_snapshot()}})
        self.assertEqual(before, self.rpc.calls)  # reader callbacks cannot call RPC and wait for themselves
        self.assertEqual(42, self.bridge.limits["rateLimits"]["primary"]["usedPercent"])
        for run in (first, second):
            self.assertEqual("usage.updated", run.events[-1]["event"])
            self.assertEqual(["5-hour limit", "Weekly limit"], [r["label"] for r in run.events[-1]["limits"]])
        self.assertEqual("run.completed", done.events[-1]["event"])

    def test_sparse_limit_updates_preserve_metadata_and_other_buckets(self):
        self.rpc.rate_limits = {"rateLimitsByLimitId": {"codex": self.rate_snapshot(),
            "other": {"limitName": "Other", "primary": {"usedPercent": 9, "windowDurationMins": 60}}},
            "rateLimitResetCredits": {"availableCount": 1}}
        self.get_usage()
        self.bridge.on_message({"method": "account/rateLimits/updated", "params": {"rateLimits": {
            "limitId": "codex", "limitName": None, "planType": None,
            "primary": {"usedPercent": 43, "resetsAt": None, "windowDurationMins": None}, "secondary": None}}})
        data = self.get_usage()
        self.assertEqual("pro", data["plan"])
        self.assertEqual(1, data["resets"]["available"])
        self.assertEqual(["Codex · 5-hour limit", "Codex · Weekly limit", "Other · 1-hour limit"],
                         [r["label"] for r in data["limits"]])
        self.assertEqual(43, data["limits"][0]["used_percent"])
        self.assertEqual(1762260000, data["limits"][0]["resets_at"])

    def test_rolling_updates_do_not_delay_reads_of_reset_credit_metadata(self):
        self.rpc.rate_limits = {"rateLimits": self.rate_snapshot(), "rateLimitResetCredits": {"availableCount": 1}}
        self.bridge.on_message({"method": "account/rateLimits/updated", "params": {"rateLimits": self.rate_snapshot()}})
        self.assertEqual(1, self.get_usage()["resets"]["available"])
        self.assertIn("account/rateLimits/read", [m for m, _ in self.rpc.calls])
        self.bridge.limits_at -= 61
        at = self.bridge.limits_at
        self.bridge.on_message({"method": "account/rateLimits/updated", "params": {"rateLimits": self.rate_snapshot()}})
        self.assertEqual(at, self.bridge.limits_at)

    def test_rolling_limits_survive_an_unavailable_full_read(self):
        self.bridge.on_message({"method": "account/rateLimits/updated", "params": {"rateLimits": self.rate_snapshot()}})
        self.rpc.fail_methods.add("account/rateLimits/read")
        data = self.get_usage()
        self.assertEqual(42, data["limits"][0]["used_percent"])
        self.assertNotIn("resets", data)

    def test_new_bucket_notification_preserves_the_legacy_single_bucket(self):
        self.rpc.rate_limits = {"rateLimits": self.rate_snapshot()}
        self.get_usage()
        self.bridge.on_message({"method": "account/rateLimits/updated", "params": {"rateLimits": {
            "limitId": "other", "limitName": "Other", "primary": {"usedPercent": 9, "windowDurationMins": 60}}}})
        self.assertEqual(["codex:primary", "codex:secondary", "other:primary"],
                         [row["id"] for row in self.get_usage()["limits"]])

    def test_limit_reads_prefer_buckets_and_support_legacy_single_bucket(self):
        snapshot = self.rate_snapshot()
        self.rpc.rate_limits = {"rateLimitsByLimitId": {"codex": snapshot},
                                "rateLimits": {"primary": {"usedPercent": 99}}}
        data = self.get_usage()
        self.assertEqual("pro", data["plan"])
        self.assertEqual([{"id": "codex:primary", "label": "5-hour limit", "used_percent": 42, "resets_at": 1762260000},
                          {"id": "codex:secondary", "label": "Weekly limit", "used_percent": 12, "resets_at": 1762860000}],
                         data["limits"])
        self.rpc.rate_limits = {"rateLimits": snapshot}
        self.assertEqual(data["limits"], self.get_usage(refresh=True)["limits"])

    def test_reset_credit_is_explicitly_consumed_and_limits_are_reread(self):
        self.rpc.rate_limits = {"rateLimits": self.rate_snapshot(), "rateLimitResetCredits": {"availableCount": 1}}
        self.assertEqual(1, self.get_usage()["resets"]["available"])
        call = self.rpc.call
        def consume(method, params, timeout=60):
            result = call(method, params, timeout)
            if method == "account/rateLimitResetCredit/consume":
                self.rpc.rate_limits = {"rateLimits": self.rate_snapshot(), "rateLimitResetCredits": {"availableCount": 0}}
            return result
        self.rpc.call = consume
        status, body = self.request("POST", "/api/sessions/th/usage/reset", {"credit_id": "credit-1"})
        self.assertEqual(200, status, body)
        data = json.loads(body)
        self.assertTrue(data["ok"])
        self.assertNotIn("resets", data["usage"])
        params = next(p for m, p in self.rpc.calls if m == "account/rateLimitResetCredit/consume")
        self.assertTrue(params["idempotencyKey"])
        self.assertEqual("credit-1", params["creditId"])
        self.assertEqual(2, sum(m == "account/rateLimits/read" for m, _ in self.rpc.calls))
        self.request("POST", "/api/sessions/th/usage/reset", {})
        automatic = next(p for m, p in reversed(self.rpc.calls) if m == "account/rateLimitResetCredit/consume")
        self.assertNotIn("creditId", automatic)
        self.assertNotEqual(params["idempotencyKey"], automatic["idempotencyKey"])

    def test_reset_reports_native_noop_outcomes_without_claiming_success(self):
        call = self.rpc.call
        for outcome in ("noCredit", "nothingToReset", "alreadyRedeemed"):
            with self.subTest(outcome=outcome):
                self.rpc.call = lambda m, p, timeout=60: ({"outcome": outcome} if m == "account/rateLimitResetCredit/consume"
                                                         else call(m, p, timeout))
                status, body = self.request("POST", "/api/sessions/th/usage/reset", {})
                self.assertEqual(200, status)
                self.assertEqual(outcome == "alreadyRedeemed", json.loads(body)["ok"])

    def test_usage_routes_validate_session_body_and_auth_before_consuming(self):
        for body in ({"credit_id": ""}, {"credit_id": []}, {"credit_id": None}, {"unexpected": True}, []):
            self.assertEqual(400, self.request("POST", "/api/sessions/th/usage/reset", body)[0])
        for method, route in (("GET", "/usage"), ("POST", "/usage/reset")):
            self.assertEqual(401, self.request(method, "/api/sessions/th" + route, {}, token="bad")[0])
            self.assertEqual(404, self.request(method, "/api/sessions/missing" + route, {})[0])
        self.assertNotIn("account/rateLimitResetCredit/consume", [m for m, _ in self.rpc.calls])

    def test_optional_usage_sources_are_cached_refreshable_and_can_fail(self):
        self.rpc.fail_methods = {"account/usage/read", "account/rateLimits/read"}
        self.notify("thread/tokenUsage/updated", tokenUsage=self.token_usage())
        data = self.get_usage()
        self.assertNotIn("cost_usd", data["session"])
        self.assertNotIn("limits", data)
        self.get_usage()
        for method in self.rpc.fail_methods:
            self.assertEqual(1, sum(m == method for m, _ in self.rpc.calls))
        self.rpc.fail_methods.clear()
        self.rpc.thread_usage = {"threadUsage": {"estimatedUsageUsdMicros": 2500000}}
        self.assertEqual(2.5, self.get_usage(refresh=True)["session"]["cost_usd"])
        self.rpc.thread_usage = {"threadUsage": {"estimatedUsageUsdMicros": 0}}
        self.assertEqual(2.5, self.get_usage()["session"]["cost_usd"])
        at, cost = self.bridge.costs["th"]
        self.bridge.costs["th"] = (at - 61, cost)
        self.assertEqual(0, self.get_usage()["session"]["cost_usd"])
        self.rpc.fail_methods.add("account/usage/read")
        self.assertNotIn("cost_usd", self.get_usage(refresh=True)["session"])

    def test_missing_context_window_is_omitted_and_percent_is_clamped(self):
        usage = self.token_usage()
        usage["modelContextWindow"] = None
        self.notify("thread/tokenUsage/updated", tokenUsage=usage)
        self.assertNotIn("context", self.get_usage())
        usage["modelContextWindow"] = 500
        self.notify("thread/tokenUsage/updated", tokenUsage=usage)
        self.assertEqual(100, self.get_usage()["context"]["percent"])

    def test_plan_mode_sends_collaboration_settings_without_effort_override(self):
        status, body = self.request("POST", "/api/sessions/th/settings", {"plan_mode": True})
        self.assertEqual(200, status, body)
        self.assertEqual(True, json.loads(body)["plan_mode"])
        self.assertEqual("next_turn", json.loads(body)["applies"])
        self.assertEqual({"plan_mode": True}, self.bridge.state["settings"]["th"])
        self.assertEqual(True, json.loads((Path(self.tmp.name) / "state.json").read_text())["settings"]["th"]["plan_mode"])
        self.start_run()
        params = next(p for m, p in reversed(self.rpc.calls) if m == "turn/start")
        self.assertEqual({"mode": "plan", "settings": {"model": "test-model", "reasoning_effort": "high",
                                                      "developer_instructions": None}}, params["collaborationMode"])
        self.assertNotIn("effort", params)

    def test_plan_mode_inherits_runtime_and_can_exit_with_a_model_default(self):
        self.rpc.runtime["collaborationMode"] = {"mode": "plan", "settings": {
            "model": "test-model", "reasoning_effort": "high", "developer_instructions": "Plan only"}}
        self.assertTrue(json.loads(self.request("GET", "/api/sessions/th/settings")[1])["plan_mode"])
        self.request("POST", "/api/sessions/th/settings", {"plan_mode": False})
        self.request("POST", "/api/sessions/th/model", {"model": "new-model"})
        self.start_run()
        params = next(p for m, p in reversed(self.rpc.calls) if m == "turn/start")
        self.assertEqual({"mode": "default", "settings": {"model": "new-model", "reasoning_effort": "medium",
                                                         "developer_instructions": None}}, params["collaborationMode"])

    def test_fast_mode_uses_catalog_options_and_is_omitted_without_tiers(self):
        settings = json.loads(self.request("GET", "/api/sessions/th/settings")[1])
        self.assertNotIn("fast_mode", settings)
        self.assertNotIn("fast_options", settings)
        self.rpc.model_catalog[0].update(defaultServiceTier="off", serviceTiers=[
            {"id": "off", "name": "Standard", "description": "Normal speed"},
            {"id": "fast", "name": "Fast", "description": "Faster responses"}])
        settings = json.loads(self.request("GET", "/api/sessions/th/settings")[1])
        self.assertEqual("off", settings["fast_mode"])
        self.assertEqual(["off", "fast"], [v["value"] for v in settings["fast_options"]])
        self.assertEqual(200, self.request("POST", "/api/sessions/th/settings", {"fast_mode": "fast"})[0])
        self.start_run()
        params = next(p for m, p in reversed(self.rpc.calls) if m == "turn/start")
        self.assertEqual("fast", params["serviceTier"])
        self.rpc.model_catalog[0]["serviceTiers"] = []
        self.start_run()
        params = next(p for m, p in reversed(self.rpc.calls) if m == "turn/start")
        self.assertNotIn("serviceTier", params)

    def test_new_settings_validate_atomically_and_permission_cycle_order_is_unchanged(self):
        for changes in ({"plan_mode": "true"}, {"plan_mode": 1}, {"plan_mode": None}, {"fast_mode": "fast"},
                        {"plan_mode": True, "fast_mode": "unknown"}, {"plan_mode": True, "permission_mode": "invalid"}):
            self.assertEqual(400, self.request("POST", "/api/sessions/th/settings", changes)[0], changes)
            self.assertNotIn("th", self.bridge.state["settings"])
        settings = json.loads(self.request("GET", "/api/sessions/th/settings")[1])
        self.assertEqual(["read-only", "workspace-write", "full-access"], [o["value"] for o in settings["permission_options"]])

    def test_delete_removes_persisted_usage_and_cost_cache(self):
        self.notify("thread/tokenUsage/updated", tokenUsage=self.token_usage())
        self.get_usage()
        self.assertEqual(200, self.request("DELETE", "/api/sessions/th")[0])
        self.assertNotIn("th", self.bridge.state["usage"])
        self.assertNotIn("th", self.bridge.costs)


if __name__ == "__main__":
    unittest.main()
