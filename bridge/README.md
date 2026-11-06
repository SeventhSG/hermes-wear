# Claude Code bridge

This bridge lets Hermes Companion control Claude Code sessions in `CLAUDE_BRIDGE_CWD`, including sessions started from the terminal. It uses `claude-agent-sdk==0.2.160` for session history and live turns. The phone can read transcripts, continue or fork sessions, select models and settings, steer or stop live runs, answer tool approvals, send images, and browse skills.

Install `bridge/requirements.txt` in a dedicated Python environment. Set `CLAUDE_BRIDGE_TOKEN` to a random bearer token and `CLAUDE_BRIDGE_CWD` to the Claude project directory. Run `python -m bridge.claude_bridge` from the repo root. The bridge binds `127.0.0.1:8792` by default; override with `CLAUDE_BRIDGE_HOST` and `CLAUDE_BRIDGE_PORT`. Put an authenticated HTTPS/Tailscale proxy in front of it for phone access. Never expose the port directly.

Claude Code owns transcripts and session titles. Private SQLite at `~/.local/share/hermes-companion/claude-bridge.sqlite3` (override with `CLAUDE_BRIDGE_DB`) stores phone flags, per-session overrides, and steer markers. Existing duplicate message tables are removed on startup. No service is installed or started by the repository.

Run `/root/hc-agent/wt/claude-bridge-venv/bin/python -m unittest -v bridge.test_claude_bridge bridge.test_codex_bridge` for bridge wire tests. No Claude login or network is needed. `bypassPermissions` applies only after an explicit `POST /api/sessions/{id}/settings`; the default is `default`.

# Codex remote

`codex_bridge.py` is the phone's remote for Codex CLI (installed by the server kit, see `server-kit/README.md`).
It connects to the Codex app-server daemon (WebSocket over `~/.codex/app-server-control/app-server-control.sock`)
and serves the Hermes contract on `CODEX_BRIDGE_HOST:CODEX_BRIDGE_PORT` (default `127.0.0.1:8795`) behind
`CODEX_BRIDGE_TOKEN`: sessions are Codex threads, `/v1/runs` are turns (SSE replay by seq), steer is
`turn/steer`, stop is `turn/interrupt`, approvals answer the daemon's `requestApproval` server requests.
`GET /api/sessions/{id}` follows a turn started elsewhere and reports it as `active_run_id`. Pins, unread
flags and per-thread model choice live in `~/.local/share/hermes-companion/codex-bridge.json`.
Run `python3 -m unittest -v bridge.test_codex_bridge` (no daemon needed).

Codex chats expose **Reasoning effort** and **Agent permissions** in the app's chat menu and command palette.
Authenticated `GET /api/sessions/{id}/settings` reports the effective effort, model-advertised effort choices,
and sandbox/approval policy. `POST` to the same route accepts `reasoning_effort` or `permission_mode`.
The permission presets are `read-only` (no network, approvals on request), `workspace-write` (project writes,
no network, approvals on request), and `full-access` (unrestricted host access without approval).
Managed Codex requirements restrict the advertised and accepted presets. Opening the selector preserves
existing terminal settings; explicit choices are saved in the bridge state and applied on the next phone turn.
They also become that thread's subsequent Codex defaults. A running turn is not changed. Phone action
permissions remain separate. Full access requires confirmation in the app.

Agent message completions supply any text missing from streamed deltas. Item text and event sequence numbers
prevent duplicate display during replay. Live SSE delivery and reconnect-tail replay are covered by the bridge tests.
