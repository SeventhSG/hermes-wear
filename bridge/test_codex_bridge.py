import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path

from .codex_bridge import Bridge, Handler, RpcError, ThreadingHTTPServer, turn_rows


class FakeRpc:
    """Stands in for the daemon connection: records calls, answers turn/start and thread reads."""

    def __init__(self):
        self.calls, self.responses = [], []
        self.turns = 0
        self.ready = threading.Event()
        self.ready.set()
        self.runtime = {"model": "test-model", "reasoningEffort": "high", "sandbox": {"type": "dangerFullAccess"},
                        "approvalPolicy": "never", "cwd": "/tmp/project"}
        self.requirements = None

    def call(self, method, params, timeout=60):
        self.calls.append((method, params))
        if method == "turn/start":
            self.turns += 1
            return {"turn": {"id": "turn-" + str(self.turns), "items": [], "status": "inProgress"}}
        if method == "thread/read":
            return {"thread": {"id": params["threadId"], "name": "Fix CI", "status": {"type": "idle"}, "updatedAt": 5}}
        if method == "thread/resume":
            return self.runtime
        if method == "model/list":
            return {"data": [{"id": "test-model", "model": "test-model", "isDefault": True,
                              "defaultReasoningEffort": "medium", "supportedReasoningEfforts": [
                                  {"reasoningEffort": v, "description": "Use " + v} for v in ("low", "medium", "high", "ultra")]},
                             {"id": "new-model", "model": "new-model", "defaultReasoningEffort": "medium",
                              "supportedReasoningEfforts": [{"reasoningEffort": "medium"}]}]}
        if method == "configRequirements/read":
            return {"requirements": self.requirements}
        return {}

    def respond(self, rid, result):
        self.responses.append((rid, result))


def offline():
    raise OSError("no daemon in tests")


class CodexBridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bridge = Bridge("tok", self.tmp.name, Path(self.tmp.name) / "state.json", connect=offline)
        self.rpc = self.bridge.rpc = FakeRpc()
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
        events = [e for e in self.bridge.runs["turn-1"].events if e["event"] == "tool.output"]
        self.assertEqual([("terminal", "compiling\n")], [(e["tool_name"], e["preview"]) for e in events])

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
        self.assertEqual(["thread/resume", "turn/start", "turn/steer", "turn/interrupt"], methods)
        self.assertEqual("turn-1", self.rpc.calls[2][1]["expectedTurnId"])

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


if __name__ == "__main__":
    unittest.main()
