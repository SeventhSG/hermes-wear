"""Companion gateway adapter against a fake gateway. Run with the Hermes venv python:
PYTHONPATH=/usr/local/lib/hermes-agent:<this dir's parent> python -m unittest companion.test_adapter
"""
import asyncio
import base64
import json
import os
import socket
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

import aiohttp

from gateway.config import Platform, PlatformConfig

Platform._add_pseudo_member("companion")

from companion import adapter as ca  # noqa: E402
from gateway.platforms.event import ProcessingOutcome  # noqa: E402

KEY = "test-key"
PNG = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * 64).decode()


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


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


class CompanionTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.port = free_port()
        self.env = mock.patch.object(ca, "get_scoped_secret", lambda k, d="": {
            "API_SERVER_KEY": KEY, "COMPANION_GATEWAY_PORT": str(self.port), "COMPANION_GATEWAY_HOST": "127.0.0.1"}.get(k, d))
        self.env.start()
        self.a = ca.CompanionAdapter(PlatformConfig(enabled=True, extra={}))
        self.gw = Gateway(self.a)
        self.a.handle_message = self.gw.handle
        self.assertTrue(await self.a.connect())
        self.http = aiohttp.ClientSession(f"http://127.0.0.1:{self.port}", headers={"Authorization": "Bearer " + KEY})

    async def asyncTearDown(self):
        await self.http.close()
        await self.a.disconnect()
        self.env.stop()

    async def start(self, **body):
        async with self.http.post("/v1/runs", json={"session_id": "S1", **body}) as r:
            self.assertEqual(r.status, 200, await r.text())
            return (await r.json())["run_id"]

    async def events(self, rid, until="run."):
        out = []
        async with self.http.get(f"/v1/runs/{rid}/events") as r:
            async for line in r.content:
                line = line.decode().strip()
                if line.startswith("data:"):
                    e = json.loads(line[5:])
                    out.append(e)
                    if e["event"].startswith(until) and e["event"] not in ("run.started", "run.steered", "run.stopping"):
                        return out
        return out

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
        self.assertIn("hermes-action", ev[-1]["output"])
        self.assertEqual(self.gw.a.gateway_runner.session_store.switched, [("agent:main:companion:dm:S1", "S1")])
        self.assertEqual(self.gw.seen[0].channel_prompt, "device context")
        self.assertEqual([e["seq"] for e in ev], list(range(len(ev))))

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
            open(self.gw.doc, "w").write("{}")
            self.gw.voice = voice.name
            with mock.patch.object(ca, "FILES_DIR", os.path.join(d, "store")):
                ev = await self.events(await self.start(input="send the file"))
            cards = [e["delta"].strip() for e in ev if e["event"] == "assistant.delta" and e["delta"].strip().startswith("MEDIA:")]
            self.assertEqual(len(cards), 1)
            stored = cards[0][6:]
            self.assertTrue(stored.startswith(os.path.join(d, "store")) and stored.endswith(".mp3") and os.path.isfile(stored))
            self.assertIn(cards[0], ev[-1]["output"])
            self.assertEqual(ev[-1]["output"].count("MEDIA:"), 1)   # final text is the model's; the card is appended

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
