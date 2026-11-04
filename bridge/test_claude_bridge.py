import asyncio
import http.client
import json
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import claude_agent_sdk as sdk

from .claude_bridge import (MAX_TOOL_RESULT, Bridge, Handler, Store, ThreadingHTTPServer, explain, input_blocks,
                            queued_steers, transcript_rows)


class FakeSDK:
    ClaudeAgentOptions = sdk.ClaudeAgentOptions
    AssistantMessage = sdk.AssistantMessage
    UserMessage = sdk.UserMessage
    SystemMessage = sdk.SystemMessage
    StreamEvent = sdk.StreamEvent
    ResultMessage = sdk.ResultMessage
    RateLimitEvent = sdk.RateLimitEvent
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
        self.rate_info = None
        self.result_usage = None
        self.total_cost_usd = None
        self.context_usage = None
        self.usage_reply = None
        self.control_requests = []
        self.rewinds = []
        self.rewind_error = None
        self.questions = [{"question": "Which environment?", "header": "Environment", "options": [
            {"label": "Test", "description": "Use the test environment"},
            {"label": "Local", "description": "Use the local environment"}], "multiSelect": False}]

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
        if sid not in self.sessions:
            raise FileNotFoundError(sid)
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
        self.permission_modes = []
        self.permission_error = None
        self._query = self

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
        if self.options.resume_session_at:
            messages = self.fake.transcripts[sid]
            index = next(i for i, m in enumerate(messages) if m.uuid == self.options.resume_session_at)
            self.fake.transcripts[sid] = messages[:index + 1] + [sdk.SessionMessage("user", "u3", sid, {"content": prompt})]
        self.fake.sessions[sid] = sdk.SDKSessionInfo(sid, "SDK session", 1700000000000,
                                                       first_prompt="from terminal")
        await self.queue.put(sdk.SystemMessage("init", {"session_id": sid}))
        if self.fake.rate_info is not None:
            await self.queue.put(sdk.RateLimitEvent(self.fake.rate_info, "rate-event", sid))
        if self.fake.mode in ("approval", "questions", "plan_approval"):
            async def ask():
                context = sdk.ToolPermissionContext(description="Run command")
                name, args = ("AskUserQuestion", {"questions": self.fake.questions}) if self.fake.mode == "questions" else ("Bash", {"command": "pwd"})
                if self.fake.mode == "plan_approval":
                    name, args = "ExitPlanMode", {"plan": "## Plan\n\nRun the unit tests."}
                self.permission = await self.options.can_use_tool(name, args, context)
                await self.queue.put(sdk.ResultMessage("success", 1, 1, False, 1, sid, result="allowed"))
            asyncio.create_task(ask())
        elif self.fake.mode == "normal":
            await self.queue.put(sdk.StreamEvent("e", sid, {"type": "message_start", "message": {"id": "m1"}}))
            await self.queue.put(sdk.StreamEvent("e", sid, {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Hello"}}))
            await self.queue.put(sdk.AssistantMessage([sdk.TextBlock("Hello world")], "sonnet", message_id="m1"))
            await self.queue.put(sdk.ResultMessage("success", 1, 1, False, 1, sid, result="Hello world",
                                                  usage=self.fake.result_usage, total_cost_usd=self.fake.total_cost_usd))
        elif self.fake.mode == "limit":
            await self.queue.put(sdk.AssistantMessage([], "sonnet", error="rate_limit"))
            await self.queue.put(sdk.ResultMessage("success", 1, 1, True, 1, sid, result="You've hit your limit"))
        elif self.fake.mode == "tools":
            await self.queue.put(sdk.StreamEvent("e", sid, {"type": "content_block_delta", "delta":
                                                  {"type": "thinking_delta", "thinking": "consider"}}))
            await self.queue.put(sdk.AssistantMessage([sdk.TextBlock("First"),
                                                       sdk.ToolUseBlock("tool-1", "Bash", {"command": "pwd"}),
                                                       sdk.ToolUseBlock("tool-2", "Bash", {"command": "ls"})],
                                                      "sonnet", message_id="m1"))
            await self.queue.put(sdk.UserMessage([sdk.ToolResultBlock("tool-1", "oops", is_error=True),
                                                  sdk.ToolResultBlock("tool-2", "ok")]))
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
        if self.fake.mode == "stuck":  # a tool that ignores the interrupt
            return
        await self.queue.put(sdk.ResultMessage("success", 1, 1, False, 1, sid))

    async def get_mcp_status(self):
        return {"mcpServers": [{"name": "local", "status": "connected"}]}

    async def rewind_files(self, checkpoint):
        if self.fake.rewind_error:
            raise RuntimeError(self.fake.rewind_error)
        self.fake.rewinds.append(checkpoint)

    async def set_permission_mode(self, mode):
        if self.permission_error is not None:
            raise self.permission_error
        self.permission_modes.append(mode)

    async def get_context_usage(self):
        if isinstance(self.fake.context_usage, asyncio.Event):
            await self.fake.context_usage.wait()
        if self.fake.context_usage is None:
            raise RuntimeError("context source unavailable")
        return self.fake.context_usage

    async def _send_control_request(self, request):
        self.fake.control_requests.append(request)
        if isinstance(self.fake.usage_reply, asyncio.Event):
            await self.fake.usage_reply.wait()
        if self.fake.usage_reply is None:
            raise RuntimeError("get_usage is not supported in this context")
        return self.fake.usage_reply


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

    def undo_history(self, sid):
        self.fake.sessions[sid] = sdk.SDKSessionInfo(sid, "Undo test", 1700000000000)
        self.fake.transcripts[sid] = [
            sdk.SessionMessage("user", "u1", sid, {"content": "keep"}),
            sdk.SessionMessage("assistant", "a1", sid, {"content": "kept"}),
            sdk.SessionMessage("user", "u2", sid, {"content": "discard"}),
            sdk.SessionMessage("assistant", "a2", sid, {"content": [{"type": "tool_use", "id": "t", "name": "Write", "input": {}}]}),
            sdk.SessionMessage("user", "t2", sid, {"content": [{"type": "tool_result", "tool_use_id": "t", "content": "ok"}]}),
            sdk.SessionMessage("assistant", "a3", sid, {"content": "discarded"})]

    def test_undo_restores_before_trimming_and_resumes_earlier_point(self):
        sid = self.session()
        self.undo_history(sid)
        path = f"/api/sessions/{sid}/command"
        self.fake.rewind_error = "No file checkpoint found"
        status, body = self.request("POST", path, {"command": "/undo"})
        self.assertEqual(409, status)
        self.assertIn("checkpoint", body["error"])
        self.assertEqual(6, len(self.bridge.history(sid)))
        self.fake.rewind_error = None
        status, body = self.request("POST", path, {"command": "/undo"})
        self.assertEqual(200, status)
        self.assertEqual("undo", body["type"])
        self.assertEqual("discard", body["text"])  # back in the composer, like Claude Code rewind
        self.assertEqual(["u2"], self.fake.rewinds)
        self.assertTrue(self.fake.options[-1].enable_file_checkpointing)
        self.assertEqual(sid, self.fake.options[-1].resume)
        rows = self.request("GET", f"/api/sessions/{sid}/messages")[1]["data"]
        self.assertEqual(["keep", "kept"], [r["content"] for r in rows])
        # The pending cutoff survives bridge restarts in SQLite.
        restarted = Bridge(self.store, "secret", self.tmp.name, self.fake.ClaudeSDKClient, self.fake)
        self.assertEqual(["u1", "a1"], [m.uuid for m in restarted.history(sid)])
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "next"})[1]["run_id"]
        self.request("GET", f"/v1/runs/{rid}/events")
        self.assertEqual("a1", self.fake.options[-1].resume_session_at)
        self.assertTrue(self.fake.options[-1].enable_file_checkpointing)
        self.assertIn("replay-user-messages", self.fake.options[-1].extra_args)
        self.wait_for(lambda: not self.store.get(sid)["rewind_drop"])
        # A newly written branch is visible without retaining the abandoned tail.
        self.assertEqual(["u1", "a1", "u3"], [m.uuid for m in self.bridge.history(sid)])
        # Simulate a crash after the SDK writes the new branch but before metadata clears.
        self.store.patch(sid, {"rewind_at": "a1", "rewind_drop": "u2"})
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "continue"})[1]["run_id"]
        self.request("GET", f"/v1/runs/{rid}/events")
        self.assertIsNone(self.fake.options[-1].resume_session_at)

    def test_undo_all_repeated_undo_and_input_validation(self):
        sid = self.session()
        path = f"/api/sessions/{sid}/command"
        self.assertEqual(400, self.request("POST", path, {"command": "/undo"})[0])
        self.undo_history(sid)
        for command in ("/undo 0", "/undo -1", "/undo nope", "/undo 1 2", "/undo 3"):
            self.assertEqual(400, self.request("POST", path, {"command": command})[0])
        self.assertEqual([], self.fake.rewinds)
        self.assertEqual(200, self.request("POST", path, {"command": "/undo"})[0])
        self.assertEqual(200, self.request("POST", path, {"command": "/undo"})[0])
        self.assertEqual(["u2", "u1"], self.fake.rewinds)
        self.assertEqual([], self.request("GET", f"/api/sessions/{sid}/messages")[1]["data"])
        self.assertNotIn(sid, self.fake.sessions)
        self.assertEqual("Phone", self.store.get(sid)["title"])
        self.assertEqual(400, self.request("POST", path, {"command": "/undo"})[0])
        self.undo_history(sid)
        self.assertEqual(200, self.request("POST", path, {"command": "/undo 2"})[0])
        self.assertEqual("u1", self.fake.rewinds[-1])

    def test_undo_refuses_active_run_and_mcp_works_idle_or_active(self):
        sid = self.session()
        path = f"/api/sessions/{sid}/command"
        status, body = self.request("POST", path, {"command": "/mcp"})
        self.assertEqual(200, status)
        self.assertEqual("local: connected", body["output"])
        self.assertEqual([], self.fake.clients[-1].queries)
        self.fake.mode = "wait"
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "wait"})[1]["run_id"]
        self.wait_for(lambda: self.bridge.runs[rid].ready.is_set())
        self.assertEqual(409, self.request("POST", path, {"command": "/undo"})[0])
        clients = len(self.fake.clients)
        self.assertEqual("local: connected", self.request("POST", path, {"command": "/mcp"})[1]["output"])
        self.assertEqual(clients, len(self.fake.clients))
        self.request("POST", f"/v1/runs/{rid}/stop", {})
        self.wait_for(lambda: self.bridge.runs[rid].status != "running")
        catalog = {c["name"] for c in self.request("GET", "/v1/commands")[1]["data"]}
        self.assertTrue({"undo", "mcp"}.issubset(catalog))

    def test_internal_metadata_uses_real_conversation_text(self):
        sid = str(uuid.uuid4())
        for internal in ("<task-notification><task-id>secret</task-id></task-notification>",
                         "<command-name>/clear</command-name>", "/clear",
                         "This session is being continued from a previous conversation that ran out of context. /root/private.jsonl"):
            with self.subTest(internal=internal):
                self.fake.sessions[sid] = sdk.SDKSessionInfo(sid, internal, 1700000000000, first_prompt=internal)
                self.fake.transcripts[sid] = [
                    sdk.SessionMessage("user", "u1", sid, {"content": "Fix the login screen"}),
                    sdk.SessionMessage("assistant", "a1", sid, {"content": "The login screen is fixed"}),
                    sdk.SessionMessage("user", "u2", sid, {"content": internal})]
                session = self.request("GET", "/api/sessions")[1]["data"][0]
                self.assertEqual("Fix the login screen", session["title"])
                self.assertEqual("The login screen is fixed", session["preview"])
                self.fake.sessions[sid].custom_title = "Login polish"
                self.assertEqual("Login polish", self.bridge.find(sid)["title"])

    def test_internal_only_metadata_has_no_raw_fallback(self):
        sid = str(uuid.uuid4())
        internal = "<task-notification><task-id>secret</task-id></task-notification>"
        self.fake.sessions[sid] = sdk.SDKSessionInfo(sid, internal, 1700000000000, first_prompt=internal)
        self.fake.transcripts[sid] = [sdk.SessionMessage("user", "u", sid, {"content": internal})]
        session = self.bridge.find(sid)
        self.assertEqual("", session["title"])
        self.assertEqual("", session["preview"])

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
        self.assertIn("opus", [model["id"] for model in self.request("GET", "/v1/models")[1]["providers"][0]["models"]])
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

    def test_rate_limits_stream_and_usage_route_without_control_source(self):
        sid = self.session()
        self.fake.rate_info = sdk.RateLimitInfo(status="allowed_warning", utilization=.8,
                                               resets_at=1762260000, rate_limit_type="five_hour")
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "hello"})[1]["run_id"]
        self.request("GET", f"/v1/runs/{rid}/events")
        status, usage = self.request("GET", f"/api/sessions/{sid}/usage")
        expected = {"id": "five_hour", "label": "5-hour limit", "used_percent": 80.0,
                    "resets_at": 1762260000, "status": "warning"}
        self.assertEqual(200, status)
        self.assertEqual([expected], usage["limits"])
        self.assertNotIn("context", usage)
        self.assertNotIn("session", usage)
        self.assertEqual("completed", self.bridge.runs[rid].status)
        self.assertTrue(any(e["event"] == "usage.updated" and e.get("limits") == [expected]
                            for e in self.bridge.runs[rid].events))
        self.assertEqual([{"subtype": "get_usage", "skip_behaviors": True}], self.fake.control_requests)
        # Idle refresh uses the cache and never creates an SDK client.
        self.assertEqual(usage, self.request("GET", f"/api/sessions/{sid}/usage?refresh=1")[1])
        self.assertEqual(1, len(self.fake.clients))
        self.assertEqual(401, self.request("GET", f"/api/sessions/{sid}/usage", token="bad")[0])
        self.assertEqual(404, self.request("GET", "/api/sessions/missing/usage")[0])

    def test_result_usage_context_and_cost_survive_restart(self):
        sid = self.session()
        self.fake.result_usage = {"input_tokens": 120, "output_tokens": 8, "cache_read_input_tokens": 90}
        self.fake.total_cost_usd = .5
        self.fake.context_usage = {"totalTokens": 1000, "rawMaxTokens": 200000, "percentage": .5,
                                   "autoCompactThreshold": 160000}
        for count in (1, 2):
            rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "hello"})[1]["run_id"]
            self.request("GET", f"/v1/runs/{rid}/events")
            usage = self.request("GET", f"/api/sessions/{sid}/usage")[1]
            self.assertEqual({"used_tokens": 1000, "window_tokens": 200000, "percent": .5,
                              "auto_compact_at": 160000, "source": "last_turn"}, usage["context"])
            self.assertEqual({"input_tokens": 210 * count, "output_tokens": 8 * count,
                              "cached_input_tokens": 90 * count, "cost_usd": .5,
                              "cost_label": "API-equivalent"}, usage["session"])
            events = self.bridge.runs[rid].events
            self.assertLess(next(e["seq"] for e in events if e["event"] == "usage.updated"),
                            next(e["seq"] for e in events if e["event"] == "run.completed"))
        restarted = Bridge(Store(self.store.path), "secret", self.tmp.name, self.fake.ClaudeSDKClient, self.fake)
        self.assertEqual(usage, restarted.usage(sid))

    def test_usage_control_response_iso_limits_and_model_buckets(self):
        sid = self.session()
        self.fake.usage_reply = {"subscription_type": "max", "rate_limits": {
            "five_hour": {"utilization": 42, "resets_at": "2026-01-01T00:00:00Z"},
            "seven_day": None, "seven_day_opus": {"utilization": None, "resets_at": "bad"},
            "model_scoped": [{"display_name": "Fable", "utilization": 11,
                              "resets_at": "2026-01-01T02:00:00+02:00"}]}}
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "hello"})[1]["run_id"]
        self.request("GET", f"/v1/runs/{rid}/events")
        usage = self.request("GET", f"/api/sessions/{sid}/usage")[1]
        self.assertEqual("max", usage["plan"])
        self.assertEqual([{"id": "five_hour", "label": "5-hour limit", "used_percent": 42,
                           "resets_at": 1767225600}, {"id": "seven_day_opus", "label": "Weekly Opus limit"},
                          {"id": "model:Fable", "label": "Weekly Fable limit", "used_percent": 11,
                           "resets_at": 1767225600}], usage["limits"])

    def test_active_refresh_emits_and_preserves_streamed_warning_status(self):
        sid = self.session()
        self.fake.mode = "idle"
        self.fake.context_usage = {"totalTokens": 2000, "rawMaxTokens": 200000, "percentage": 1}
        self.fake.rate_info = sdk.RateLimitInfo(status="allowed_warning", utilization=.8,
                                               rate_limit_type="five_hour")
        self.fake.usage_reply = {"rate_limits": {"five_hour": {"utilization": 81}}}
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "wait"})[1]["run_id"]
        run = self.bridge.runs[rid]
        self.wait_for(lambda: any(e["event"] == "usage.updated" for e in run.events))
        usage = self.request("GET", f"/api/sessions/{sid}/usage?refresh=1")[1]
        self.assertEqual(2000, usage["context"]["used_tokens"])
        self.assertEqual("warning", usage["limits"][0]["status"])
        self.assertEqual(81, usage["limits"][0]["used_percent"])
        self.assertEqual(2, len([e for e in run.events if e["event"] == "usage.updated"]))
        self.request("POST", f"/v1/runs/{rid}/stop", {})
        self.wait_for(lambda: run.status == "cancelled")

    def test_optional_source_timeouts_do_not_delay_run_indefinitely(self):
        sid = self.session()
        self.fake.context_usage = asyncio.Event()
        self.fake.usage_reply = asyncio.Event()
        wait_for = asyncio.wait_for
        timeouts = []

        async def short_timeout(awaitable, timeout):
            timeouts.append(timeout)
            return await wait_for(awaitable, .01 if timeout == 5 else timeout)

        with patch("bridge.claude_bridge.asyncio.wait_for", side_effect=short_timeout):
            rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "hello"})[1]["run_id"]
            self.request("GET", f"/v1/runs/{rid}/events")
        self.assertEqual([5, 5], timeouts)
        self.assertEqual("completed", self.bridge.runs[rid].status)
        self.assertEqual({}, self.request("GET", f"/api/sessions/{sid}/usage")[1])
        self.assertTrue(self.bridge.runs[rid].usage_probe.acquire(blocking=False))
        self.bridge.runs[rid].usage_probe.release()

    def test_permission_and_model_options_have_plain_copy(self):
        sid = self.session()
        settings = self.request("GET", f"/api/sessions/{sid}/settings")[1]
        self.assertEqual(["Ask before edits", "Edit automatically", "Plan mode", "Bypass permissions"],
                         [option["label"] for option in settings["permission_options"]])
        self.assertTrue(all(option["description"] for option in settings["permission_options"]))
        catalog = self.request("GET", "/api/model/options")[1]
        models = catalog["providers"][0]["models"]
        self.assertIn({"id": "sonnet", "description": "Balanced speed and capability"}, models)
        self.assertIn("claude-opus-5-5", [model["id"] for model in models])

    def test_settings_apply_permission_live_and_keep_effort_for_next_turn(self):
        sid = self.session()
        route = f"/api/sessions/{sid}/settings"
        reply = self.request("POST", route, {"permission_mode": "acceptEdits"})[1]
        self.assertEqual("next_turn", reply["applies"])
        self.assertEqual([], self.fake.clients)
        self.assertEqual(["default", "acceptEdits", "plan", "bypassPermissions"],
                         [o["value"] for o in reply["permission_options"]])
        self.fake.mode = "idle"
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "wait"})[1]["run_id"]
        self.wait_for(lambda: self.bridge.runs[rid].ready.is_set())
        self.assertEqual("now", self.request("POST", route, {"permission_mode": "plan"})[1]["applies"])
        self.assertEqual(["plan"], self.fake.clients[-1].permission_modes)
        self.assertEqual("next_turn", self.request("POST", route, {"reasoning_effort": "low"})[1]["applies"])
        self.assertEqual(["plan"], self.fake.clients[-1].permission_modes)
        self.fake.clients[-1].permission_error = OSError("client disconnected")
        self.assertEqual(500, self.request("POST", route, {"permission_mode": "bypassPermissions"})[0])
        self.assertEqual("plan", self.bridge.settings(sid)["permission_mode"])
        self.fake.clients[-1].permission_error = None
        for changes in ({}, {"permission_mode": "wrong"}, {"reasoning_effort": "wrong"},
                        {"plan_mode": True}, {"fast_mode": "fast"}):
            self.assertEqual(400, self.request("POST", route, changes)[0])
        self.assertEqual(404, self.request("POST", "/api/sessions/missing/settings", {"permission_mode": "plan"})[0])
        self.request("POST", f"/v1/runs/{rid}/stop", {})
        self.wait_for(lambda: self.bridge.runs[rid].status == "cancelled")

    def test_exit_plan_approval_shows_plan_and_changes_mode_only_on_approval(self):
        self.bridge.approval_timeout = 2
        sid = self.session()
        self.fake.mode = "plan_approval"
        for choice in ("deny", "once", "session"):
            self.bridge.settings(sid, {"permission_mode": "plan"})
            rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "plan"})[1]["run_id"]
            run = self.bridge.runs[rid]
            self.wait_for(lambda: any(e["event"] == "approval.request" for e in run.events))
            event = next(e for e in run.events if e["event"] == "approval.request")
            self.assertEqual("## Plan\n\nRun the unit tests.", event["description"])
            self.request("POST", f"/v1/runs/{rid}/approval", {"request_id": event["request_id"], "choice": choice})
            self.wait_for(lambda: run.status == "completed")
            self.assertEqual("plan" if choice == "deny" else "acceptEdits", self.bridge.settings(sid)["permission_mode"])
            self.assertEqual([] if choice == "deny" else ["acceptEdits"], self.fake.clients[-1].permission_modes)
            self.assertEqual("deny" if choice == "deny" else "allow", self.fake.clients[-1].permission.behavior)

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
            if choice is None:
                self.assertIn("seconds", result.message)
            self.assertTrue(any(e["event"] == "approval.resolved" for e in run.events))

    def question_run(self):
        self.fake.mode = "questions"
        rid = self.request("POST", "/v1/runs", {"session_id": self.session(), "input": "ask"})[1]["run_id"]
        run = self.bridge.runs[rid]
        self.wait_for(lambda: bool(run.questions) or run.status != "running")
        return run, f"/v1/runs/{rid}/clarify"

    def test_question_batch_is_sequential_and_returns_cli_answer_strings(self):
        self.bridge.approval_timeout = 2
        self.fake.questions.append({"question": "Which checks?", "header": "Checks", "options": [
            {"label": "Unit", "description": "Fast tests"}, {"label": "HTTP", "description": "Route tests"}],
            "multiSelect": True})
        run, route = self.question_run()
        first = next(e for e in run.events if e["event"] == "clarify.request")
        self.assertEqual(["Test", "Local"], first["choices"])
        self.assertIn("Use the test environment", first["question"])
        self.assertFalse(first["multi_select"])
        self.assertEqual({}, run.approvals)
        self.assertEqual(200, self.request("POST", route, {"request_id": first["prompt_id"], "answer": "Test"})[0])
        self.wait_for(lambda: len([e for e in run.events if e["event"] == "clarify.request"]) == 2)
        second = [e for e in run.events if e["event"] == "clarify.request"][-1]
        self.assertTrue(second["multi_select"])
        self.assertNotEqual(first["prompt_id"], second["prompt_id"])
        self.assertEqual(404, self.request("POST", route, {"request_id": first["prompt_id"], "answer": "Local"})[0])
        self.assertEqual(200, self.request("POST", route, {"request_id": second["prompt_id"], "answer": '["Unit", "HTTP"]'})[0])
        self.wait_for(lambda: run.status == "completed")
        permission = self.fake.clients[-1].permission
        self.assertEqual("allow", permission.behavior)
        self.assertEqual({"questions": self.fake.questions, "answers": {
            "Which environment?": "Test", "Which checks?": "Unit, HTTP"}}, permission.updated_input)
        self.assertEqual(["clarify.request", "clarify.resolved", "clarify.request", "clarify.resolved"],
                         [e["event"] for e in run.events if e["event"].startswith("clarify.")])

    def test_question_route_validation_and_typed_multi_answer(self):
        self.bridge.approval_timeout = 2
        self.fake.questions[0]["multiSelect"] = True
        run, route = self.question_run()
        qid = next(iter(run.questions))
        for body in ({}, {"request_id": qid, "answer": " "}, {"request_id": qid, "answer": []},
                     {"request_id": 7, "answer": "Test"}, {"request_id": qid, "answer": '[1]'},
                     {"request_id": qid, "answer": '[]'}, {"request_id": qid, "answer": '[broken'}):
            self.assertEqual(400, self.request("POST", route, body)[0], body)
        self.assertEqual(401, self.request("POST", route, {"request_id": qid, "answer": "Test"}, token="bad")[0])
        self.assertEqual(404, self.request("POST", route, {"request_id": "missing", "answer": "Test"})[0])
        self.assertEqual(200, self.request("POST", route, {"request_id": qid, "answer": " My own answer "})[0])
        self.wait_for(lambda: run.status == "completed")
        self.assertEqual("My own answer", self.fake.clients[-1].permission.updated_input["answers"]["Which environment?"])
        self.assertEqual(409, self.request("POST", route, {"request_id": qid, "answer": "Test"})[0])
        self.assertEqual(404, self.request("POST", "/v1/runs/missing/clarify", {"request_id": qid, "answer": "Test"})[0])

    def test_unanswered_question_times_out_and_withdraws_card(self):
        run, route = self.question_run()
        self.wait_for(lambda: run.status == "completed")
        permission = self.fake.clients[-1].permission
        self.assertEqual("deny", permission.behavior)
        self.assertIn("Nobody answered", permission.message)
        self.assertEqual({}, run.questions)
        self.assertEqual(1, len([e for e in run.events if e["event"] == "clarify.resolved"]))

    def test_stop_and_finish_withdraw_questions_without_asking_the_next_one(self):
        self.bridge.approval_timeout = 2
        self.fake.questions.append({"question": "Any details?", "header": "Details", "options": []})
        for stop in (True, False):
            run, route = self.question_run()
            qid = next(iter(run.questions))
            if stop:
                self.assertEqual(200, self.request("POST", f"/v1/runs/{run.id}/stop", {})[0])
            else:
                run.finish("failed", "test shutdown")
            self.wait_for(lambda: self.fake.clients[-1].permission is not None)
            self.wait_for(lambda: run.status != "running")
            self.assertEqual("deny", self.fake.clients[-1].permission.behavior)
            self.assertEqual({}, run.questions)
            self.assertEqual(409, self.request("POST", route, {"request_id": qid, "answer": "Test"})[0])
            self.assertEqual(["clarify.request", "clarify.resolved"],
                             [e["event"] for e in run.events if e["event"].startswith("clarify.")])

    def test_invalid_question_batch_denies_without_showing_partial_cards(self):
        self.fake.questions.append({"question": ""})
        run, _ = self.question_run()
        self.wait_for(lambda: run.status == "completed")
        self.assertEqual("deny", self.fake.clients[-1].permission.behavior)
        self.assertFalse(any(e["event"] == "clarify.request" for e in run.events))

    def test_tool_reasoning_and_message_segments(self):
        sid = self.session()
        self.fake.mode = "tools"
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "work"})[1]["run_id"]
        self.request("GET", f"/v1/runs/{rid}/events")
        events = self.bridge.runs[rid].events
        self.assertEqual(["reasoning.available", "tool.started", "tool.started", "tool.failed", "tool.completed"],
                         [e["event"] for e in events if e["event"] in
                          ("reasoning.available", "tool.started", "tool.failed", "tool.completed")])
        self.assertEqual([("tool.started", "tool-1"), ("tool.started", "tool-2"),
                          ("tool.failed", "tool-1"), ("tool.completed", "tool-2")],
                         [(e["event"], e["item_id"]) for e in events if e["event"].startswith("tool.")])
        self.assertEqual("terminal", next(e for e in events if e["event"] == "tool.started")["tool_name"])
        self.assertEqual("First\n\nSecond", "".join(e["delta"] for e in events if e["event"] == "assistant.delta"))

    def test_sdk_failures_become_readable_run_errors(self):
        self.assertIn("overloaded", explain("", status=529))
        self.assertIn("/compact", explain("Prompt is too long"))
        self.assertEqual("ValueError", explain("ValueError"))
        sid = self.session()
        self.fake.mode = "limit"
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "go"})[1]["run_id"]
        self.request("GET", f"/v1/runs/{rid}/events")
        run = self.request("GET", f"/v1/runs/{rid}")[1]
        self.assertEqual("failed", run["status"])
        self.assertIn("usage or rate limit", run["error"])
        self.assertIn("hit your limit", run["error"])
        # setup errors used to leave the run "running" and the session locked
        calls = []

        def flaky(sid, directory=None):
            calls.append(sid)
            if len(calls) > 1:
                raise OSError("disk")
            return self.fake.sessions.get(sid)
        self.fake.get_session_info = flaky
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "go"})[1]["run_id"]
        self.wait_for(lambda: self.bridge.runs[rid].status == "failed")
        self.assertIn("disk", self.bridge.runs[rid].error)
        self.wait_for(lambda: self.store.get_run(rid).get("status") == "failed")  # saved in finally, after finish

    def test_stop_cancels_a_turn_that_ignores_the_interrupt(self):
        sid = self.session()
        self.fake.mode = "stuck"
        self.bridge.stop_grace = .2
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "slow"})[1]["run_id"]
        self.wait_for(lambda: bool(self.fake.clients and self.fake.clients[-1].queries))
        self.assertEqual(200, self.request("POST", f"/v1/runs/{rid}/stop", {})[0])
        self.wait_for(lambda: self.bridge.runs[rid].status == "cancelled")
        self.assertEqual("cancelled", self.store.get_run(rid)["status"])
        self.assertEqual(202, self.request("POST", "/v1/runs", {"session_id": sid, "input": "again"})[0])

    def test_run_in_flight_at_restart_reports_failed(self):
        sid = self.session()
        self.store.run_state("old-run", sid, "running")
        self.store.fail_orphans()
        self.assertEqual(404, self.request("GET", "/v1/runs/old-run/events")[0])
        run = self.request("GET", "/v1/runs/old-run")[1]
        self.assertEqual(("failed", sid), (run["status"], run["session_id"]))
        self.assertIn("restarted", run["error"])

    def test_http_edges(self):
        sid = self.session()
        self.assertEqual(401, self.request("GET", "/v1/capabilities", token="s\xe9cret")[0])
        self.assertEqual(501, self.request("DELETE", "/api/sessions")[0])
        self.assertEqual(400, self.request("PATCH", f"/api/sessions/{sid}", {"pinned": "yes"})[0])
        self.assertEqual(400, self.request("POST", f"/api/sessions/{sid}/fork", {})[0])  # no transcript yet
        self.fake.list_sessions = lambda **kw: (_ for _ in ()).throw(OSError("disk"))
        status, body = self.request("GET", "/api/sessions")
        self.assertEqual((500, "disk"), (status, body["error"]))
        self.fake.mode = "idle"
        rid = self.request("POST", "/v1/runs", {"session_id": sid, "input": "hold"})[1]["run_id"]
        self.assertEqual(409, self.request("DELETE", f"/api/sessions/{sid}")[0])
        self.assertEqual(409, self.request("POST", "/v1/runs", {"session_id": sid, "input": "second"})[0])
        self.request("POST", f"/v1/runs/{rid}/stop", {})
        self.wait_for(lambda: self.bridge.runs[rid].status == "cancelled")
        self.assertIn("run.cancelled", self.request("GET", f"/v1/runs/{rid}/events", last=-7)[1])

    def test_finished_runs_are_released_and_inputs_are_trimmed(self):
        first, second = self.session(), self.session()
        rid = self.request("POST", "/v1/runs", {"session_id": first, "input": "a"})[1]["run_id"]
        self.request("GET", f"/v1/runs/{rid}/events")
        self.bridge.runs[rid].finished_at = time.time() - 7200
        self.request("POST", "/v1/runs", {"session_id": second, "input": "b"})
        self.assertNotIn(rid, self.bridge.runs)
        self.assertNotIn(first, self.bridge.by_session)
        blocks = input_blocks([{"type": "text", "text": " "}, {"type": "image_url", "image_url": "data:image/png;base64,YQ=="}])
        self.assertEqual(["image"], [b["type"] for b in blocks])
        big = sdk.SessionMessage("user", "u", "s", {"content": [{"type": "tool_result", "tool_use_id": "t", "content": "x" * 100000}]})
        self.assertLess(len(transcript_rows([big])[0]["content"]), MAX_TOOL_RESULT + 100)


class TranscriptCleanupTest(unittest.TestCase):
    def rows(self, *messages):
        return transcript_rows([sdk.SessionMessage("user", str(i), "s", m if isinstance(m, dict) else {"content": m})
                                for i, m in enumerate(messages)])

    def test_compaction_is_one_system_marker_without_summary_or_path(self):
        for prefix in ("This session", "This conversation"):
            with self.subTest(prefix=prefix):
                rows = self.rows(prefix + " is being continued from a previous conversation that ran out of context.\n"
                                 "Summary: secret\n/root/.claude/projects/private.jsonl\nDo not acknowledge the summary",
                                 "<command-name>/compact</command-name><command-message>compact</command-message>",
                                 "<local-command-stdout>Compacted (ctrl+o to see full summary)</local-command-stdout>",
                                 "Next question")
                self.assertEqual([("system", "Compacted"), ("user", "Next question")],
                                 [(r["role"], r["content"]) for r in rows])
                self.assertEqual(rows, self.rows(prefix + " is being continued from a previous conversation that ran out of context.\n"
                                                "different private summary", "<command-name>/compact</command-name>",
                                                "<local-command-stdout>Compacted</local-command-stdout>", "Next question"))

    def test_commands_merge_separate_or_inline_output(self):
        for output in ("stdout", "stderr"):
            with self.subTest(output=output):
                rows = self.rows("<local-command-caveat>Internal advice</local-command-caveat>"
                                 "<command-name>/clear</command-name><command-message>clear</command-message><command-args></command-args>",
                                 f"<local-command-{output}>Conversation cleared</local-command-{output}>")
                self.assertEqual([("system", "/clear\nConversation cleared")], [(r["role"], r["content"]) for r in rows])
        rows = self.rows("<command-name>/cost</command-name><command-args>today</command-args>"
                         "<local-command-stdout>$1.00</local-command-stdout>")
        self.assertEqual("/cost today\n$1.00", rows[0]["content"])
        rows = self.rows("<command-message>cost</command-message><command-name>/cost</command-name>"
                         "<local-command-stderr>Unavailable</local-command-stderr>")
        self.assertEqual("/cost\nUnavailable", rows[0]["content"])
        self.assertEqual("Output only", self.rows("<local-command-stdout>Output only</local-command-stdout>")[0]["content"])

    def test_meta_and_notifications_are_hidden_but_quoted_examples_survive(self):
        rows = self.rows({"isMeta": True, "content": "Internal instructions"},
                         "<task-notification><task-id>123</task-id></task-notification>",
                         "<system-reminder>Internal context</system-reminder>",
                         "<local-command-caveat>Internal advice</local-command-caveat>",
                         "<session-start-hook>Hook output</session-start-hook>",
                         "Explain <task-notification> wrappers", "```xml\n<command-name>/clear</command-name>\n```")
        self.assertEqual(["Explain <task-notification> wrappers", "```xml\n<command-name>/clear</command-name>\n```"],
                         [r["content"] for r in rows])
        self.assertEqual("Compacted", self.rows({"isCompactSummary": True, "content": "Opaque summary"})[0]["content"])


if __name__ == "__main__":
    unittest.main()


class QueuedSteerTest(unittest.TestCase):
    def test_undone_branch_cannot_leak_queued_messages_into_kept_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.jsonl"
            path.write_text("\n".join(json.dumps(e) for e in [
                {"type": "assistant", "uuid": "kept", "parentUuid": None},
                {"type": "user", "uuid": "undone", "parentUuid": "kept"},
                {"type": "attachment", "uuid": "steer", "parentUuid": "undone",
                 "attachment": {"type": "queued_command", "prompt": "also forgotten"}},
                {"type": "attachment", "uuid": "cycle", "parentUuid": "cycle",
                 "attachment": {"type": "queued_command", "prompt": "malformed"}}
            ]))
            self.assertEqual({}, queued_steers(path, {"kept"}))

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

