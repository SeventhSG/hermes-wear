"""Companion gateway adapter against a fake gateway. Run with the Hermes venv python:
PYTHONPATH=/usr/local/lib/hermes-agent:<this dir's parent> python -m unittest companion.test_adapter
"""
import asyncio
import base64
import json
import os
import socket
import tempfile
import threading
import time
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest import mock

import aiohttp

from gateway.config import Platform, PlatformConfig

Platform._add_pseudo_member("companion")

from companion import adapter as ca  # noqa: E402
from gateway.platforms.event import ProcessingOutcome  # noqa: E402
from agent.account_usage import AccountUsageSnapshot, AccountUsageWindow  # noqa: E402

KEY = "test-key"
PNG = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * 64).decode()


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


class ToolEventIdsTest(unittest.TestCase):
    def test_hooks_forward_only_supplied_ids(self):
        run = mock.Mock()
        with mock.patch.object(ca, "_hook_run", return_value=run):
            for hook in (ca._pre_tool, ca._post_tool):
                hook(tool_name="terminal", tool_call_id="call-1")
                self.assertEqual("call-1", run.emit.call_args.kwargs["item_id"])
                self.assertEqual("call-1", run.emit.call_args.kwargs["tool_call_id"])
                hook(tool_name="terminal")
                self.assertNotIn("item_id", run.emit.call_args.kwargs)


class FakeStore:
    def __init__(self):
        self.switched = []
        self.entry = SimpleNamespace(session_id="gw-new", session_key="agent:main:companion:dm:S1")

    def get_or_create_session(self, source):
        return self.entry

    def switch_session(self, key, sid, preserve_prompt_pin=True):
        self.switched.append((key, sid))
        self.entry = SimpleNamespace(session_id=sid, session_key=key)
        return self.entry

    def lookup_by_session_key(self, key):
        return self.entry

    def set_session_yolo(self, key, enabled):
        self.entry.yolo = enabled


class Gateway:
    """Plays the gateway runner: what a turn does to the adapter, in order."""

    def __init__(self, adapter):
        self.a, self.seen, self.turn = adapter, [], None
        adapter.gateway_runner = SimpleNamespace(session_store=FakeStore(), _evict_cached_agent=lambda key: None,
                                               _profile_name_for_source=lambda *a, **k: None)

    async def handle(self, event):
        self.seen.append(event)
        sink = SimpleNamespace(chat_id=event.source.chat_id, on_delta=lambda t: None, on_segment_break=lambda: None,
                               on_commentary=lambda t: None)
        if event.text.startswith("/model"):
            await self.a.send(event.source.chat_id, "Model switched to " + event.text.split()[1])
            await self.a.on_processing_complete(event, ProcessingOutcome.SUCCESS)
        elif event.text.startswith("/"):
            await self.a.send(event.source.chat_id, "⏩ Steering")
        else:
            self.turn = asyncio.create_task(self.run_turn(event, sink))

    async def run_turn(self, event, sink):
        a, chat = self.a, event.source.chat_id
        with mock.patch("tools.approval_context.get_current_session_key", lambda d="": "agent:main:companion:dm:S1"):
            ca._stream_delta(delta="Checking", kind="text", session_id="S1")
            ca._stream_delta(delta="hmm", kind="reasoning", session_id="S1")
            ca._pre_tool(tool_name="terminal", args={"command": "ls -la"}, session_id="S1", tool_call_id="c1")
            if "approve" in event.text:
                await a.send_exec_approval(chat, "rm -rf /tmp/x", "agent:main:companion:dm:S1", "delete files")
                await asyncio.sleep(0.3)
            ca._post_tool(tool_name="terminal", result='{"output": "a b"}', session_id="S1", tool_call_id="c1",
                          duration_ms=1200, status="ok")
            await asyncio.sleep(0.3 if "slow" in event.text else 0)
            if "send the file" in event.text:
                ca._stream_delta(delta="\n\nHere: MEDIA:" + self.doc + "\n", kind="text", session_id="S1")
                await a.send_document(chat, self.doc)    # the reply's own MEDIA: line: no second card
                await a.send_voice(chat, self.voice)     # a TTS reply: a card, copied out of temp
            ca._stream_delta(delta="\n\nDone.", kind="text", session_id="S1")
            ca._post_llm(session_id="S1", assistant_response="Done.\n```hermes-action\n{}\n```")
        await a.send(chat, "Checking")          # the consumer echoing streamed text: not shown again
        await a.edit_message(chat, "m1", "Checking ▉")
        await a.on_processing_complete(event, ProcessingOutcome.SUCCESS)


class RunStorageTest(unittest.TestCase):
    def test_unread_deltas_coalesce_without_crossing_kinds_or_segments(self):
        loop = mock.Mock()
        run = ca.Run("r1", "S1", loop)
        for _ in range(10000):
            run.delta("x")
        for _ in range(10000):
            run.emit("reasoning.available", text="y")
        self.assertEqual(len(run.events), 2)
        self.assertEqual(run.events[0]["delta"], "x" * 10000)
        self.assertEqual(run.events[1]["text"], "y" * 10000)
        loop.call_soon_threadsafe.assert_called_once()   # no per-token callback backlog either
        run.end_segment()
        run.delta("next")
        run.delta(" part")
        run.emit("tool.started", tool_name="terminal")
        run.delta("last")
        run.finish("completed")
        self.assertEqual([e["seq"] for e in run.events], list(range(7)))
        self.assertEqual(run.events[2]["delta"], "\n\n")
        self.assertTrue(run.events[2]["segment"])
        self.assertEqual(run.events[3]["delta"], "next part")
        self.assertEqual(run.events[4]["event"], "tool.started")
        self.assertEqual(run.events[5]["delta"], "last")
        self.assertEqual(run.events[6]["event"], "run.completed")


class FileStorageTest(unittest.TestCase):
    def test_age_and_total_size_eviction_keep_the_new_copy_and_newer_files(self):
        with tempfile.TemporaryDirectory() as root, mock.patch.object(ca, "FILES_DIR", root), \
                mock.patch.object(ca, "MAX_FILES_BYTES", 12):
            now = time.time()
            paths = {}
            for name, age, size in (("expired", ca.KEEP_FILES_S + 1, 1), ("old", 100, 5), ("newer", 50, 4)):
                directory = os.path.join(root, name)
                os.mkdir(directory)
                paths[name] = os.path.join(directory, "file")
                with open(paths[name], "wb") as f:
                    f.write(b"x" * size)
                os.utime(directory, (now - age, now - age))
            with tempfile.NamedTemporaryFile(dir="/tmp") as source:
                source.write(b"new file")
                source.flush()
                stored = ca._stable(source.name)
            self.assertFalse(os.path.exists(paths["expired"]))
            self.assertFalse(os.path.exists(paths["old"]))
            self.assertTrue(os.path.exists(paths["newer"]))
            with open(stored, "rb") as f:
                self.assertEqual(f.read(), b"new file")
            self.assertEqual(sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(root) for f in fs), 12)

    def test_oversized_copy_is_rejected_without_evicting_existing_files(self):
        with tempfile.TemporaryDirectory() as root, mock.patch.object(ca, "FILES_DIR", root), \
                mock.patch.object(ca, "MAX_FILES_BYTES", 4), tempfile.NamedTemporaryFile(dir="/tmp") as source:
            existing = os.path.join(root, "existing")
            os.mkdir(existing)
            with open(os.path.join(existing, "file"), "wb") as f:
                f.write(b"kept")
            source.write(b"small")
            source.flush()
            with self.assertRaisesRegex(OSError, "storage limit"):
                ca._stable(source.name)
            self.assertEqual(os.listdir(root), ["existing"])
            self.assertTrue(os.path.isfile(os.path.join(existing, "file")))

    def test_failed_copy_leaves_no_partial_attachment(self):
        with tempfile.TemporaryDirectory() as root, mock.patch.object(ca, "FILES_DIR", root), \
                tempfile.NamedTemporaryFile(dir="/tmp") as source:
            def fail(src, dest):
                with open(dest, "wb") as f:
                    f.write(b"partial")
                raise OSError("copy failed")
            with mock.patch.object(ca.shutil, "copyfile", side_effect=fail), self.assertRaisesRegex(OSError, "copy failed"):
                ca._stable(source.name)
            self.assertEqual(os.listdir(root), [])


class CompanionTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.port = free_port()
        self.env = mock.patch.object(ca, "get_scoped_secret", lambda k, d="": {
            "API_SERVER_KEY": KEY, "COMPANION_GATEWAY_PORT": str(self.port), "COMPANION_GATEWAY_HOST": "127.0.0.1"}.get(k, d))
        self.env.start()
        self.provider = mock.patch("gateway.slash_commands_status._configured_provider", return_value=None)
        self.provider.start()
        self.a = ca.CompanionAdapter(PlatformConfig(enabled=True, extra={}))
        self.gw = Gateway(self.a)
        self.a.handle_message = self.gw.handle
        self.assertTrue(await self.a.connect())
        self.http = aiohttp.ClientSession(f"http://127.0.0.1:{self.port}", headers={"Authorization": "Bearer " + KEY})

    async def asyncTearDown(self):
        await self.http.close()
        await self.a.disconnect()
        self.env.stop()
        self.provider.stop()

    async def start(self, **body):
        async with self.http.post("/v1/runs", json={"session_id": "S1", **body}) as r:
            self.assertEqual(r.status, 200, await r.text())
            return (await r.json())["run_id"]

    async def events(self, rid, until="run.", last_seq=None):
        out = []
        headers = {"Last-Event-ID": str(last_seq)} if last_seq is not None else {}
        async with self.http.get(f"/v1/runs/{rid}/events", headers=headers) as r:
            async for line in r.content:
                line = line.decode().strip()
                if line.startswith("data:"):
                    e = json.loads(line[5:])
                    out.append(e)
                    if e["event"].startswith(until) and e["event"] not in ("run.started", "run.steered", "run.stopping"):
                        return out
        return out

    async def usage(self, **query):
        async with self.http.get("/v1/usage", params={"session_id": "S1", **query}) as response:
            self.assertEqual(response.status, 200, await response.text())
            return await response.json()

    async def test_usage_auth_and_required_session(self):
        async with aiohttp.ClientSession(f"http://127.0.0.1:{self.port}") as client:
            async with client.get("/v1/usage?session_id=S1") as response:
                self.assertEqual(response.status, 401)
            async with client.post("/v1/settings", json={"session_id": "S1", "yolo": True}) as response:
                self.assertEqual(response.status, 401)
        async with self.http.get("/v1/usage") as response:
            self.assertEqual(response.status, 400)

    async def test_usage_live_context_and_session_counters(self):
        agent = SimpleNamespace(context_compressor=SimpleNamespace(last_prompt_tokens=5000, context_length=128000),
                                session_prompt_tokens=6000, session_completion_tokens=120,
                                session_total_tokens=6120, session_cache_read_tokens=4000,
                                session_cache_write_tokens=100)
        resident = mock.Mock(return_value=agent)
        self.a.gateway_runner._resident_agent_for = resident
        data = await self.usage()
        resident.assert_called_once_with("agent:main:companion:dm:S1")
        self.assertEqual(data["context"]["used_tokens"], 5000)
        self.assertEqual(data["context"]["window_tokens"], 128000)
        self.assertAlmostEqual(data["context"]["percent"], 3.90625)
        self.assertEqual(data["context"]["source"], "live")
        self.assertEqual(data["session"], {"input_tokens": 6000, "output_tokens": 120, "total_tokens": 6120,
                                           "cached_input_tokens": 4000, "cache_write_tokens": 100})
        self.assertNotIn("limits", data)
        self.assertNotIn("cost_usd", data["session"])

    async def test_usage_limits_cache_refresh_failure_and_credit_availability(self):
        dt = datetime(2026, 11, 4, 8, 30, tzinfo=timezone.utc)
        snapshot = AccountUsageSnapshot(provider="openai-codex", source="test", fetched_at=dt, plan="plus",
                                        windows=(AccountUsageWindow(label="Session", used_percent=12.0, reset_at=dt),),
                                        raw={"rate_limit_reset_credits": {"available_count": 1}})
        self.a.gateway_runner._resident_agent_for = lambda key: SimpleNamespace(provider="openai-codex")
        with mock.patch("agent.account_usage.fetch_account_usage", return_value=snapshot) as fetch:
            data = await self.usage()
            self.assertEqual(data["limits"], [{"id": "session", "label": "Session", "used_percent": 12.0,
                                               "resets_at": int(dt.timestamp())}])
            self.assertEqual(data["plan"], "plus")
            self.assertEqual(data["resets"]["available"], 1)
            await self.usage()
            fetch.assert_called_once_with("openai-codex", base_url=None, api_key=None)
            await self.usage(refresh="1")
            self.assertEqual(fetch.call_count, 2)
            cache_key, fetched, cached = self.a._account_cache
            self.a._account_cache = (cache_key, fetched - 61, cached)
            await self.usage()
            self.assertEqual(fetch.call_count, 3)
        with mock.patch("agent.account_usage.fetch_account_usage", side_effect=RuntimeError("offline")) as fetch:
            data = await self.usage(refresh="1")
            self.assertNotIn("limits", data)
            self.assertNotIn("resets", data)
            await self.usage()
            fetch.assert_called_once()  # unavailable sources are cached too

    async def test_usage_cache_isolated_by_provider_credentials(self):
        agent = SimpleNamespace(provider="openai-codex", base_url="https://example.invalid", api_key="first")
        self.a.gateway_runner._resident_agent_for = lambda key: agent
        with mock.patch("agent.account_usage.fetch_account_usage", return_value=None) as fetch:
            await self.usage()
            agent.api_key = "second"
            await self.usage()
            self.assertEqual(fetch.call_count, 2)

    async def test_usage_fetch_timeout_keeps_one_worker_and_concurrent_requests_share_it(self):
        finished = threading.Event()
        real_wait_for = asyncio.wait_for
        async def short_wait(future, timeout):
            return await real_wait_for(future, .01)
        with mock.patch("agent.account_usage.fetch_account_usage", side_effect=lambda *a, **k: finished.wait(2)) as fetch:
            try:
                with mock.patch.object(ca.asyncio, "wait_for", side_effect=short_wait):
                    self.assertIsNone(await self.a._account_usage("test", None, None, False))
                    pending = self.a._account_task
                    self.assertFalse(pending.done())
                    self.assertIsNone(await self.a._account_usage("other", None, None, True))
                    self.assertIsNone(await self.a._account_usage("test", None, None, True))
                    self.assertIs(self.a._account_task, pending)
                    self.assertEqual(fetch.call_count, 1)
            finally:
                finished.set()
            await pending
        self.a._account_task = None
        self.a._account_cache = None
        with mock.patch("agent.account_usage.fetch_account_usage", return_value=None) as fetch:
            await asyncio.gather(*(self.a._account_usage("test", None, None, True) for _ in range(10)))
            fetch.assert_called_once()

    async def test_usage_last_turn_and_missing_private_helpers(self):
        self.a.gateway_runner.session_store.entry.last_prompt_tokens = 2000
        data = await self.usage()
        self.assertEqual(data["context"], {"used_tokens": 2000, "source": "last_turn"})
        self.assertNotIn("session", data)
        resolver = mock.AsyncMock(return_value=(2000, 10000, "test-model"))
        self.a.gateway_runner._resolve_context_figures = resolver
        data = await self.usage()
        self.assertEqual(data["context"]["percent"], 20)
        resolver.assert_awaited_once_with(None, None, self.a.gateway_runner.session_store.entry, mock.ANY)
        resolver.side_effect = RuntimeError("unavailable")
        self.assertNotIn("window_tokens", (await self.usage())["context"])

    async def test_usage_persisted_and_configured_provider_fallbacks(self):
        self.a.gateway_runner._persisted_billing_route = mock.AsyncMock(return_value=("anthropic", "https://example.invalid"))
        with mock.patch("agent.account_usage.fetch_account_usage", return_value=None) as fetch:
            await self.usage()
            fetch.assert_called_once_with("anthropic", base_url="https://example.invalid", api_key=None)
            self.a.gateway_runner._persisted_billing_route.side_effect = RuntimeError("unavailable")
            with mock.patch("gateway.slash_commands_status._configured_provider", return_value="openai-codex"):
                await self.usage()
            self.assertEqual(fetch.call_args, mock.call("openai-codex", base_url=None, api_key=None))

    async def test_settings_targets_gateway_yolo_and_reads_persisted_state(self):
        key = "agent:main:companion:dm:S1"
        self.a.gateway_runner.session_store.entry.yolo = True
        with mock.patch("tools.approval.is_session_yolo_enabled", return_value=False):
            self.assertTrue((await self.usage())["yolo"])
        with mock.patch("tools.approval_yolo.toggle_session_yolo", return_value=True) as toggle:
            async with self.http.post("/v1/settings", json={"session_id": "S1", "yolo": True}) as response:
                self.assertEqual(response.status, 200, await response.text())
                self.assertEqual(await response.json(), {"ok": True, "yolo": True, "applies": "now"})
            toggle.assert_called_once_with(key, enabled=True, persisted=True, persist=mock.ANY)
            toggle.call_args.kwargs["persist"](False)
            self.assertFalse(self.a.gateway_runner.session_store.entry.yolo)

    async def test_settings_validates_json_and_never_coerces_yolo(self):
        with mock.patch("tools.approval_yolo.toggle_session_yolo") as toggle:
            for body in (None, [], {}, {"session_id": "S1"}, {"session_id": 1, "yolo": True},
                         {"session_id": " ", "yolo": True}, {"session_id": "S1", "yolo": "false"},
                         {"session_id": "S1", "yolo": 1}, {"session_id": "S1", "yolo": True, "global": True}):
                async with self.http.post("/v1/settings", json=body) as response:
                    self.assertEqual(response.status, 400, await response.text())
            async with self.http.post("/v1/settings", data="{") as response:
                self.assertEqual(response.status, 400)
            toggle.assert_not_called()

    async def test_settings_round_trip_with_installed_yolo_helpers(self):
        from tools.approval import disable_session_yolo
        try:
            for enabled in (True, False):
                async with self.http.post("/v1/settings", json={"session_id": "S1", "yolo": enabled}) as response:
                    self.assertEqual(response.status, 200, await response.text())
                    self.assertEqual((await response.json())["yolo"], enabled)
                self.assertEqual((await self.usage())["yolo"], enabled)
                self.assertEqual(self.a.gateway_runner.session_store.entry.yolo, enabled)
        finally:
            disable_session_yolo("agent:main:companion:dm:S1")

    async def test_coalesced_events_replay_after_disconnect_without_changing_sent_sequences(self):
        run = ca.Run("gw_replay", "S1", self.a._loop)
        self.a.runs[run.id] = run
        run.emit("run.started")
        run.delta("a")
        run.delta("b")
        run.emit("reasoning.available", text="one")
        run.emit("reasoning.available", text="two")

        async def read_event(response):
            while True:
                line = await asyncio.wait_for(response.content.readline(), 2)
                self.assertTrue(line, "SSE closed before the expected event")
                if line.startswith(b"data:"):
                    return json.loads(line[5:])

        async with self.http.get(f"/v1/runs/{run.id}/events") as response:
            first = [await read_event(response) for _ in range(3)]
            self.assertEqual(first[1]["delta"], "ab")
            self.assertEqual(first[2]["text"], "onetwo")
            run.emit("reasoning.available", text="three")
            run.emit("reasoning.available", text="four")
            sent = await read_event(response)
            self.assertEqual((sent["seq"], sent["text"]), (3, "threefour"))
        # Once exposed, an event stays immutable even when no readers are connected.
        run.emit("reasoning.available", text="five")
        run.emit("reasoning.available", text="six")
        run.delta("c")
        run.delta("d")
        run.finish("completed")
        replay = await self.events(run.id, last_seq=2)
        self.assertEqual([e["seq"] for e in replay], [3, 4, 5, 6])
        self.assertEqual(replay[0], sent)
        self.assertEqual(replay[1]["text"], "fivesix")
        self.assertEqual(replay[2]["delta"], "cd")
        full = await self.events(run.id)
        self.assertEqual(full[:3], first)
        self.assertEqual(full[3:], replay)
        self.assertEqual("".join(e["delta"] for e in full if e["event"] == "assistant.delta"), "abcd")
        self.assertEqual(full[-1]["output"], "abcd")

    async def test_idle_timer_prunes_finished_runs_and_cancels_on_disconnect(self):
        self.a._gc_timer.cancel()
        with mock.patch.object(ca, "PRUNE_RUNS_S", 0.01):
            self.a._prune_runs()
            old = ca.Run("old", "old-chat", self.a._loop)
            old.finish("completed")
            old.finished_at = time.time() - ca.KEEP_FINISHED_S - 1
            replaced = ca.Run("replaced", "active-chat", self.a._loop)
            replaced.finish("completed")
            replaced.finished_at = old.finished_at
            active = ca.Run("active", "active-chat", self.a._loop)
            recent = ca.Run("recent", "recent-chat", self.a._loop)
            recent.finish("completed")
            self.a.runs.update({r.id: r for r in (old, replaced, active, recent)})
            self.a.chats.update({r.chat_id: r for r in (old, active, recent)})
            for _ in range(100):
                if old.id not in self.a.runs:
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(set(self.a.runs), {"active", "recent"})
            self.assertEqual(self.a.chats, {"active-chat": active, "recent-chat": recent})
            timer = self.a._gc_timer
            self.assertFalse(timer.cancelled())
            await self.a.disconnect()
            self.assertTrue(timer.cancelled())
            self.assertIsNone(self.a._gc_timer)

    async def test_turn_streams_segments_tools_and_exact_final_into_the_app_session(self):
        rid = await self.start(input="list files", instructions="device context")
        ev = await self.events(rid)
        names = [e["event"] for e in ev]
        self.assertEqual(names, ["run.started", "assistant.delta", "reasoning.available", "tool.started", "tool.completed",
                                 "assistant.delta", "assistant.delta", "run.completed"])
        self.assertTrue(ev[5]["segment"])
        self.assertEqual(ev[6]["delta"], "Done.")
        self.assertEqual(ev[3]["preview"], "ls -la")
        self.assertEqual(ev[4]["duration"], 1.2)
        self.assertEqual(["c1", "c1"], [e["item_id"] for e in ev if e["event"].startswith("tool.")])
        self.assertIn("hermes-action", ev[-1]["output"])
        self.assertEqual(self.gw.a.gateway_runner.session_store.switched, [("agent:main:companion:dm:S1", "S1")])
        self.assertEqual(self.gw.seen[0].channel_prompt, "device context")
        self.assertEqual([e["seq"] for e in ev], list(range(len(ev))))

    async def test_message_sent_while_busy_keeps_the_run_open_until_the_turn_ends(self):
        busy = {"agent:main:companion:dm:S1": object()}
        self.a.gateway_runner._snapshot_running_agents = lambda: dict(busy)
        self.a.handle_message = lambda event: None   # queued behind the running turn
        async def handle(event):
            await self.a.on_processing_complete(event, ProcessingOutcome.SUCCESS)   # the queue "completes" it at once
        self.a.handle_message = handle
        rid = await self.start(input="go")
        await asyncio.sleep(1.5)
        self.assertEqual("running", self.a.runs[rid].status)
        busy.clear()   # the gateway's turn ends
        await asyncio.sleep(1.5)
        self.assertEqual("completed", self.a.runs[rid].status)

    async def test_approval_round_trip(self):
        rid = await self.start(input="please approve")
        resolved = []
        with mock.patch("tools.approval.resolve_gateway_approval", lambda key, choice: resolved.append((key, choice)) or 1):
            ev = await self.events(rid, until="approval.request")
            self.assertEqual(ev[-1]["choices"], ["once", "session", "always", "deny"])
            async with self.http.post(f"/v1/runs/{rid}/approval", json={"choice": "once"}) as r:
                self.assertEqual((await r.json())["resolved"], 1)
        self.assertEqual(resolved, [("agent:main:companion:dm:S1", "once")])
        await self.gw.turn

    async def test_steer_goes_in_as_slash_steer_and_its_ack_is_muted(self):
        rid = await self.start(input="slow job")
        await asyncio.sleep(0.05)
        async with self.http.post(f"/v1/runs/{rid}/steer", json={"text": "also check logs"}) as r:
            self.assertEqual(r.status, 200)
        ev = await self.events(rid)
        self.assertIn("/steer also check logs", [e.text for e in self.gw.seen])
        self.assertEqual([e["text"] for e in ev if e["event"] == "run.steered"], ["also check logs"])
        self.assertNotIn("assistant.commentary", [e["event"] for e in ev])
        async with self.http.post(f"/v1/runs/{rid}/steer", json={"text": "late"}) as r:
            self.assertEqual(r.status, 409)
            self.assertEqual((await r.json())["pending_steer"], "late")

    async def test_model_is_applied_once_with_slash_model_quietly(self):
        rid = await self.start(input="hi", model="claude-haiku-5-5", provider="anthropic")
        ev = await self.events(rid)
        self.assertEqual(self.gw.seen[0].text, "/model claude-haiku-5-5 --provider anthropic")
        self.assertNotIn("Model switched", json.dumps(ev))
        await self.events(await self.start(input="again", model="claude-haiku-5-5"))
        self.assertEqual(sum(e.text.startswith("/model") for e in self.gw.seen), 1)

    async def test_images_become_media(self):
        with mock.patch.object(ca, "cache_image_from_bytes", lambda data, ext: "/cache/img" + ext):
            rid = await self.start(input=[{"type": "text", "text": "what is this"},
                                          {"type": "image_url", "image_url": {"url": "data:image/png;base64," + PNG}}])
            await self.events(rid)
        e = self.gw.seen[0]
        self.assertEqual((e.text, e.media_urls, e.media_types), ("what is this", ["/cache/img.png"], ["image/png"]))

    async def test_sent_files_become_media_cards_that_outlive_temp_files(self):
        with tempfile.TemporaryDirectory() as d, tempfile.NamedTemporaryFile(suffix=".mp3", dir="/tmp") as voice:
            self.gw.doc = os.path.join(d, "report.json")
            with open(self.gw.doc, "w") as f:
                f.write("{}")
            self.gw.voice = voice.name
            with mock.patch.object(ca, "FILES_DIR", os.path.join(d, "store")):
                ev = await self.events(await self.start(input="send the file"))
            streamed = "".join(e["delta"] for e in ev if e["event"] == "assistant.delta")
            cards = [line.strip() for line in streamed.splitlines() if line.strip().startswith("MEDIA:")]
            self.assertEqual(len(cards), 1)
            stored = cards[0][6:]
            self.assertTrue(stored.startswith(os.path.join(d, "store")) and stored.endswith(".mp3") and os.path.isfile(stored))
            self.assertIn(cards[0], ev[-1]["output"])
            self.assertEqual(ev[-1]["output"].count("MEDIA:"), 1)   # final text is the model's; the card is appended

    async def test_clarify_is_a_card_answered_through_the_gateway(self):
        rid = await self.start(input="slow job")
        await asyncio.sleep(0.05)
        resolved = []
        with mock.patch("tools.clarify_gateway.get_pending", lambda cid: SimpleNamespace(multi_select=True)), \
                mock.patch("tools.clarify_gateway.resolve_gateway_clarify", lambda cid, a: resolved.append((cid, a)) or True):
            await self.a.send_clarify("S1", "Which modules?", ["Voice", "Media"], "c9", "agent:main:companion:dm:S1")
            async with self.http.post(f"/v1/runs/{rid}/clarify", json={"request_id": "c9", "answer": '["Voice"]'}) as r:
                self.assertEqual(r.status, 200, await r.text())
        ev = await self.events(rid)
        q = next(e for e in ev if e["event"] == "clarify.request")
        self.assertEqual((q["prompt_id"], q["question"], q["choices"], q["multi_select"]), ("c9", "Which modules?", ["Voice", "Media"], True))
        self.assertIn("clarify.resolved", [e["event"] for e in ev])
        self.assertEqual(resolved, [("c9", '["Voice"]')])

    async def test_a_message_the_gateway_drops_ends_the_run_instead_of_blocking_the_chat(self):
        async def drop(event):
            self.gw.seen.append(event)   # no handler while it shuts down: no hook ever fires
        self.a.handle_message = drop
        with mock.patch.object(ca, "WATCH_S", 0.05):
            ev = await self.events(await self.start(input="hello"))
            self.assertEqual(ev[-1]["event"], "run.failed")
            self.assertIn("Send it again", ev[-1]["error"])
            self.a.handle_message = self.gw.handle
            await self.events(await self.start(input="hello again"))   # not refused as busy

    async def test_a_draining_gateway_fails_the_run_at_once(self):
        self.a.gateway_runner._draining = True
        ev = await self.events(await self.start(input="hello"))
        self.assertEqual((ev[-1]["event"], self.gw.seen), ("run.failed", []))
        self.assertIn("restarting", ev[-1]["error"])

    async def test_stop_and_steer_while_parked_after_a_restart(self):
        self.a.gateway_runner._startup_restore_in_progress = True
        rid = await self.start(input="first")
        async with self.http.post(f"/v1/runs/{rid}/stop") as r:
            self.assertEqual((await r.json())["status"], "cancelled")
        self.assertEqual(self.a.runs[rid].status, "cancelled")
        rid = await self.start(input="second")
        async with self.http.post(f"/v1/runs/{rid}/steer", json={"text": "and third"}) as r:
            self.assertEqual(r.status, 200)
        self.a.gateway_runner._startup_restore_in_progress = False
        await self.events(rid, until="run.completed")
        self.assertEqual([e.text for e in self.gw.seen], ["second\n\nand third"])   # the stopped one never ran

    async def test_retiring_one_question_leaves_another_chats_card(self):
        rid = await self.start(input="slow job")
        await asyncio.sleep(0.05)
        with mock.patch("tools.clarify_gateway.get_pending", lambda cid: None):
            await self.a.send_clarify("S1", "Which?", ["A"], "c1", "agent:main:companion:dm:S1")
        await self.a.retire_clarify_card("other")
        self.assertTrue(self.a.runs[rid].clarify)
        await self.a.retire_clarify_card("c1")
        self.assertFalse(self.a.runs[rid].clarify)
        await self.events(rid)

    async def test_auth_and_busy(self):
        async with aiohttp.ClientSession() as s:
            async with s.post(f"http://127.0.0.1:{self.port}/v1/runs", json={"session_id": "S1", "input": "x"}) as r:
                self.assertEqual(r.status, 401)
        rid = await self.start(input="slow")
        async with self.http.post("/v1/runs", json={"session_id": "S1", "input": "again"}) as r:
            self.assertEqual(r.status, 409)
        await self.events(rid)

    async def test_message_outside_a_run_is_pushed(self):
        pushed = []

        async def push(chat, text):
            pushed.append((chat, text))
        self.a._push = push
        await self.a.send("S9", "Cron: backups done")
        self.assertEqual(pushed, [("S9", "Cron: backups done")])


if __name__ == "__main__":
    unittest.main()
