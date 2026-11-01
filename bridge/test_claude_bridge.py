import asyncio
import http.client
import json
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path

import claude_agent_sdk as sdk

from .claude_bridge import Bridge, Handler, Store, ThreadingHTTPServer, input_blocks, queued_steers, transcript_rows


class FakeSDK:
    ClaudeAgentOptions = sdk.ClaudeAgentOptions
    AssistantMessage = sdk.AssistantMessage
    UserMessage = sdk.UserMessage
    SystemMessage = sdk.SystemMessage
    StreamEvent = sdk.StreamEvent
    ResultMessage = sdk.ResultMessage
    TextBlock = sdk.TextBlock
    ThinkingBlock = sdk.ThinkingBlock
    ToolUseBlock = sdk.ToolUseBlock
    ToolResultBlock = sdk.ToolResultBlock
    PermissionResultAllow = sdk.PermissionResultAllow
    PermissionResultDeny = sdk.PermissionResultDeny
    PermissionUpdate = sdk.PermissionUpdate

    def __init__(self):
        self.sessions = {}
        self.transcripts = {}
        self.options = []
        self.clients = []
        self.mode = "normal"

    def list_sessions(self, directory=None, **kwargs):
        return list(self.sessions.values())

    def get_session_info(self, sid, directory=None):
        return self.sessions.get(sid)

    def get_session_messages(self, sid, directory=None):
        return self.transcripts.get(sid, [])

    project_key_for_directory = staticmethod(sdk.project_key_for_directory)

    def rename_session(self, sid, title, directory=None):
        self.sessions[sid].custom_title = title

    def fork_session(self, sid, directory=None, title=None):
        new = str(uuid.uuid4())
        self.sessions[new] = sdk.SDKSessionInfo(new, title or "Fork", 1700000000000)
        self.transcripts[new] = list(self.transcripts.get(sid, []))
        return sdk.ForkSessionResult(new)

    def delete_session(self, sid, directory=None):
        self.sessions.pop(sid)
        self.transcripts.pop(sid, None)

    def ClaudeSDKClient(self, options):
        self.options.append(options)
        client = FakeClient(self, options)
        self.clients.append(client)
        return client


class FakeClient:
    def __init__(self, fake, options):
        self.fake, self.options = fake, options
        self.queue = asyncio.Queue()
        self.queries = []
        self.interrupted = False
        self.permission = None

    async def connect(self):
        pass

    async def disconnect(self):
        pass

    async def query(self, prompt):
        if not isinstance(prompt, str):
            prompt = [x async for x in prompt]
        self.queries.append(prompt)
        if len(self.queries) > 1:
            return
        sid = self.options.session_id or self.options.resume
        self.fake.sessions[sid] = sdk.SDKSessionInfo(sid, "SDK session", 1700000000000,
                                                       first_prompt="from terminal")
        await self.queue.put(sdk.SystemMessage("init", {"session_id": sid}))
        if self.fake.mode == "approval":
            async def ask():
                context = sdk.ToolPermissionContext(description="Run command")
                self.permission = await self.options.can_use_tool("Bash", {"command": "pwd"}, context)
                await self.queue.put(sdk.ResultMessage("success", 1, 1, False, 1, sid, result="allowed"))
            asyncio.create_task(ask())
        elif self.fake.mode == "normal":
            await self.queue.put(sdk.StreamEvent("e", sid, {"type": "message_start", "message": {"id": "m1"}}))
            await self.queue.put(sdk.StreamEvent("e", sid, {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Hello"}}))
            await self.queue.put(sdk.AssistantMessage([sdk.TextBlock("Hello world")], "sonnet", message_id="m1"))
            await self.queue.put(sdk.ResultMessage("success", 1, 1, False, 1, sid, result="Hello world"))
        elif self.fake.mode == "tools":
            await self.queue.put(sdk.StreamEvent("e", sid, {"type": "content_block_delta", "delta":
                                                  {"type": "thinking_delta", "thinking": "consider"}}))
            await self.queue.put(sdk.AssistantMessage([sdk.TextBlock("First"),
                                                       sdk.ToolUseBlock("tool-1", "Bash", {"command": "pwd"})],
                                                      "sonnet", message_id="m1"))
            await self.queue.put(sdk.UserMessage([sdk.ToolResultBlock("tool-1", "oops", is_error=True)]))
            await self.queue.put(sdk.AssistantMessage([sdk.TextBlock("Second")], "sonnet", message_id="m2"))
            await self.queue.put(sdk.ResultMessage("success", 1, 1, False, 1, sid, result="Second"))

    async def receive_response(self):
        while True:
            item = await self.queue.get()
            yield item
            if isinstance(item, sdk.ResultMessage):
                return

    async def interrupt(self):
        self.interrupted = True
        sid = self.options.session_id or self.options.resume
        await self.queue.put(sdk.ResultMessage("success", 1, 1, False, 1, sid))

    async def get_mcp_status(self):
        return {"mcpServers": [{"name": "local", "status": "connected"}]}


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.fake = FakeSDK()
        self.store = Store(Path(self.tmp.name) / "bridge.sqlite3")
        self.bridge = Bridge(self.store, "secret", self.tmp.name, self.fake.ClaudeSDKClient,
                             self.fake, approval_timeout=.15)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.bridge = self.bridge
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def request(self, method, path, body=None, token="secret", last=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        headers = {"Authorization": "Bearer " + token}
        if last is not None:
            headers["Last-Event-ID"] = str(last)
        conn.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = conn.getresponse()
        data = response.read().decode()
        status = response.status
        conn.close()
        return status, data if "text/event-stream" in response.getheader("Content-Type", "") else json.loads(data)

    def session(self):
        return self.request("POST", "/api/sessions", {"title": "Phone"})[1]["session"]["id"]

    def wait_for(self, predicate):
        for _ in range(100):
            if predicate():
                return
            time.sleep(.01)
        self.fail("condition timed out")

    def test_sdk_sessions_metadata_and_transcript(self):
        sid = str(uuid.uuid4())
        self.fake.sessions[sid] = sdk.SDKSessionInfo(sid, "Terminal", 1700000000000, first_prompt="hi")
        self.store.patch(sid, {"pinned": 1, "unread": 1})
        data = self.request("GET", "/api/sessions")[1]["data"]
        self.assertEqual((sid, True, "Terminal"), (data[0]["id"], data[0]["pinned"], data[0]["title"]))
        self.assertEqual(sid, Store(self.store.path).get(sid)["id"])
        self.fake.transcripts[sid] = [
            sdk.SessionMessage("user", "u1", sid, {"content": "hi"}),
            sdk.SessionMessage("assistant", "a1", sid, {"content": [
                {"type": "thinking", "thinking": "reason"}, {"type": "text", "text": "doing"},
                {"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": "pwd"}}]}),
            sdk.SessionMessage("user", "u2", sid, {"content": [{"type": "tool_result", "tool_use_id": "t1", "content": "ok"}]}),
            sdk.SessionMessage("user", "u3", sid, {"content": "redirect"})]
        self.store.patch(sid, {"steers": json.dumps(["redirect"])})
        rows = self.request("GET", f"/api/sessions/{sid}/messages")[1]["data"]
        self.assertEqual(["user", "assistant", "assistant", "tool", "user"], [r["role"] for r in rows])
        self.assertEqual("reason", rows[1]["reasoning"])
        self.assertEqual("terminal", rows[2]["tool_calls"][0]["function"]["name"])
        self.assertEqual("t1", rows[3]["tool_call_id"])
        self.assertIn("[OUT-OF-BAND USER MESSAGE]", rows[4]["content"])
        self.assertEqual("redirect", rows[4]["content"].splitlines()[1])
        image_rows = transcript_rows([sdk.SessionMessage("user", "image", sid, {"content": [
            {"type": "text", "text": "look"}, {"type": "image", "source":
             {"type": "base64", "media_type": "image/png", "data": "YQ=="}}]})])
        self.assertEqual("data:image/png;base64,YQ==", image_rows[0]["content"][1]["image_url"])
        self.assertIn("[OUT-OF-BAND USER MESSAGE]", Bridge(Store(self.store.path), "secret", self.tmp.name,
                      self.fake.ClaudeSDKClient, self.fake).messages(sid, 120, 0)[-1]["content"])
        self.assertEqual(200, self.request("PATCH", f"/api/sessions/{sid}", {"title": "Renamed"})[0])
        self.assertEqual("Renamed", self.fake.sessions[sid].custom_title)
        fork = self.request("POST", f"/api/sessions/{sid}/fork", {"title": "Fork"})[1]["session"]
        self.assertEqual("Fork", fork["title"])
        self.assertEqual(200, self.request("DELETE", f"/api/sessions/{sid}")[0])
        self.assertEqual(404, self.request("GET", f"/api/sessions/{sid}")[0])

    def test_run_replay_settings_models_skills_auth_images(self):
        sid = self.session()
        self.assertEqual(401, self.request("GET", "/v1/capabilities", token="wrong")[0])
        self.assertTrue(self.request("GET", "/v1/capabilities")[1]["images"])
        skill = Path(self.tmp.name) / ".claude/skills/check/SKILL.md"
        skill.parent.mkdir(parents=True)
        skill.write_text("---\nname: check\ndescription: Check files\n---\n")
        self.assertIn({"name": "check", "description": "Check files"},
                      self.request("GET", "/v1/skills")[1]["data"])
        command = Path(self.tmp.name) / ".claude/commands/deploy.md"
        command.parent.mkdir(parents=True)
        command.write_text("---\ndescription: Ship it\n---\nDeploy.\n")
        catalog = {c["name"]: c["description"] for c in self.request("GET", "/v1/commands")[1]["data"]}
        self.assertEqual("Ship it", catalog["deploy"])
        self.assertIn("compact", catalog)
        self.assertIn("check", catalog)
        self.assertEqual({"type": "send", "message": "/compact focus"},
                         self.request("POST", f"/api/sessions/{sid}/command", {"command": "/compact focus"})[1])
        self.assertEqual(400, self.request("POST", f"/api/sessions/{sid}/command", {"command": "no slash"})[0])
        self.assertIn("opus", self.request("GET", "/v1/models")[1]["providers"][0]["models"])
        self.assertEqual("default", self.request("GET", f"/api/sessions/{sid}/settings")[1]["permission_mode"])
        self.assertEqual(400, self.request("POST", f"/api/sessions/{sid}/settings", {"permission_mode": "bad"})[0])
        self.assertEqual(200, self.request("POST", f"/api/sessions/{sid}/settings",
                                           {"reasoning_effort": "low", "permission_mode": "acceptEdits"})[0])
        self.assertEqual("low", self.request("GET", f"/api/sessions/{sid}/settings")[1]["reasoning_effort"])
        self.assertEqual(200, self.request("POST", f"/api/sessions/{sid}/model", {"model": "opus"})[0])
        run_id = self.request("POST", "/v1/runs", {"session_id": sid, "input": "world"})[1]["run_id"]
        events = self.request("GET", f"/v1/runs/{run_id}/events")[1]
        self.assertIn('"delta": "Hello"', events)
        self.assertIn('"delta": " world"', events)
        self.assertEqual("completed", self.request("GET", f"/v1/runs/{run_id}")[1]["status"])
        self.assertEqual("opus", self.fake.options[0].model)
        self.assertEqual("acceptEdits", self.fake.options[0].permission_mode)
        self.assertEqual("low", self.fake.options[0].effort)
        replay = self.request("GET", f"/v1/runs/{run_id}/events", last=1)[1]
        self.assertNotIn('id: 0\n', replay)
        self.assertNotIn('id: 1\n', replay)
        self.assertIn("run.completed", replay)
        self.assertEqual("pending_steer", self.request("POST", f"/v1/runs/{run_id}/steer", {"text": "later"})[1]["status"])
        self.assertEqual("later", self.request("GET", f"/v1/runs/{run_id}")[1]["pending_steer"])
        blocks = input_blocks([{"type": "text", "text": "see"},
                               {"type": "image_url", "image_url": "data:image/png;base64,YQ=="}])
        self.assertEqual("image", blocks[1]["type"])
        run_id = self.request("POST", "/v1/runs", {"session_id": sid, "input": [
            {"type": "text", "text": "see"}, {"type": "image_url", "image_url": "data:image/png;base64,YQ=="}]})[1]["run_id"]
        self.request("GET", f"/v1/runs/{run_id}/events")
        self.assertEqual("image", self.fake.clients[-1].queries[0][0]["message"]["content"][1]["type"])
        self.assertEqual(sid, self.fake.options[-1].resume)

    def test_steer_stop_and_approvals(self):
        sid = self.session()
        self.fake.mode = "idle"
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "/compact"})[1]["run_id"]
        self.wait_for(lambda: bool(self.fake.clients and self.fake.clients[-1].queries))
        self.assertEqual("/compact", self.fake.clients[-1].queries[0])
        self.assertEqual("connected", self.request("GET", f"/v1/runs/{rid}/mcp/status")[1]["mcpServers"][0]["status"])
        self.assertEqual("steered", self.request("POST", f"/v1/runs/{rid}/steer", {"text": "change"})[1]["status"])
        self.assertEqual("change", self.fake.clients[-1].queries[1])
        self.assertEqual(200, self.request("POST", f"/v1/runs/{rid}/stop", {})[0])
        self.wait_for(lambda: self.bridge.runs[rid].status == "cancelled")
        self.assertTrue(self.fake.clients[-1].interrupted)
        self.fake.mode = "approval"
        for choice in ("once", "session", "deny", None):
            rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "run"})[1]["run_id"]
            run = self.bridge.runs[rid]
            self.wait_for(lambda: bool(run.approvals))
            request_id = next(iter(run.approvals))
            self.assertTrue(any(e["event"] == "approval.request" for e in run.events))
            if choice:
                self.assertEqual(200, self.request("POST", f"/v1/runs/{rid}/approval",
                                                   {"choice": choice, "request_id": request_id})[0])
            self.wait_for(lambda: run.status == "completed")
            result = self.fake.clients[-1].permission
            self.assertEqual("deny" if choice in ("deny", None) else "allow", result.behavior)
            if choice == "session":
                self.assertEqual("session", result.updated_permissions[0].destination)
            self.assertTrue(any(e["event"] == "approval.resolved" for e in run.events))

    def test_tool_reasoning_and_message_segments(self):
        sid = self.session()
        self.fake.mode = "tools"
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "work"})[1]["run_id"]
        self.request("GET", f"/v1/runs/{rid}/events")
        events = self.bridge.runs[rid].events
        self.assertEqual(["reasoning.available", "tool.started", "tool.failed"],
                         [e["event"] for e in events if e["event"] in
                          ("reasoning.available", "tool.started", "tool.failed")])
        self.assertEqual("terminal", next(e for e in events if e["event"] == "tool.started")["tool_name"])
        self.assertEqual("First\n\nSecond", "".join(e["delta"] for e in events if e["event"] == "assistant.delta"))


if __name__ == "__main__":
    unittest.main()


class QueuedSteerTest(unittest.TestCase):
    def test_queued_commands_show_after_the_entry_they_followed(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.jsonl"
            path.write_text("\n".join(json.dumps(e) for e in [
                {"type": "assistant", "uuid": "a1", "parentUuid": None},
                {"type": "attachment", "uuid": "x1", "parentUuid": "a1", "attachment": {"type": "other"}},
                {"type": "attachment", "uuid": "x2", "parentUuid": "x1",
                 "attachment": {"type": "queued_command", "prompt": "also B"}}]) + "\nnot json\n")
            queued = queued_steers(path, {"a1", "u1"})
            self.assertEqual({"a1": ["also B"]}, queued)
            entry = lambda t, u, m: sdk.SessionMessage(type=t, uuid=u, session_id="s", message=m)
            rows = transcript_rows([entry("assistant", "a1", {"role": "assistant", "content": [{"type": "text", "text": "A"}]}),
                                    entry("user", "u1", {"role": "user", "content": "next"})], (), queued)
            self.assertEqual(["A", "[OUT-OF-BAND USER MESSAGE]\nalso B\n[/OUT-OF-BAND USER MESSAGE]", "next"],
                             [r["content"] for r in rows])

