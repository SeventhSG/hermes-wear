# Harness adapters

`AgentBackend` is the app-facing boundary for sessions, transcripts, streaming turns, run control, models, and catalog screens. `Backends.create(SettingsStore)` selects it by server type. `HermesApi` supplies the shared wire client and models; each adapter reports supported actions through `capabilitiesForUi()`.

| Capability | Hermes | Claude Code bridge | Codex bridge |
| --- | --- | --- | --- |
| Sessions and transcripts | yes | Claude Code SDK sessions | Codex threads and turns |
| Tools, approvals, steer, stop | yes | SDK client and permission callback | app-server events and requests |
| Reasoning, images, models | yes | yes | reasoning and models; images pending |
| Skills | yes | Claude Code skill files | no |
| Jobs, toolsets, hub | yes | no | no |

The Claude Code bridge reads terminal sessions through `list_sessions`, `get_session_info`, and `get_session_messages`. SDK operations rename, fork, and delete sessions. SQLite holds only phone flags, model and settings overrides, and steer markers. Live runs have sequenced SSE replay, tool and reasoning events, approvals, steering through the connected SDK client, and interrupt based stop. The app uses the shared Hermes run wire contract.

The Codex bridge uses the app-server daemon over JSON-RPC. It maps sessions to threads and turns to runs, with local pin, unread, model, and setting overrides. It cannot provide Hermes jobs, toolsets, or hub.
