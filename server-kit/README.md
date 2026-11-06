# Hermes Companion server kit

Everything a Hermes Agent server needs to work with the Hermes Companion Android app, as **modules you choose**: the hub (push, System screen, files), ten Hermes skills you can take one by one, phone actions for every chat platform, slash commands and undo, the Codex remote, Proxmox status and your own voice servers.

One installer does it all, safely and repeatably:

    python3 install.py                 # a checklist on your terminal
    python3 install.py list            # every module and what it is for
    python3 install.py --preset recommended --yes

## Quick start

You need Hermes Agent with its API server enabled (`API_SERVER_KEY` set in `~/.hermes/.env`) and Python 3.8 or newer. No pip packages.

    unzip hermes-companion-kit.zip && cd server-kit      # or: git clone, then cd server-kit
    sudo python3 install.py                              # whole machine (units in /etc/systemd/system)
    python3 install.py --user                            # or just for your own account, no root

The menu shows each module with a one-line summary. Type numbers to toggle, `s` to pick individual skills, `p` for a preset, `d 3` for details, Enter to install. It asks for the few optional settings the chosen modules use (push relay, app-update repo, which services the Ops screen may restart, the Proxmox host) and every question can be skipped.

Prefer no questions? Name the modules:

    sudo python3 install.py --modules hub,skills,phone-mcp,slash --yes
    sudo python3 install.py --modules hub --skills deploy,travel,receipts --yes
    sudo python3 install.py --preset full --with-deps --download-models --yes

Add `--dry-run` to any command to see every file and service it would touch without changing anything.

When you started it from the app (Settings › Set up server pack), Hermes runs exactly these commands for you, and you approve each one in the chat.

## Modules

| Module | What you get | Needs |
|---|---|---|
| `hub` (always) | `hermes-hub` on port 8791: push, the System and Ops screens, file delivery, pairing, app updates. `hermes-notify` command. | Hermes API server and key |
| `skills` | The ten companion skills below, or any subset (`skill-<name>`). | hub |
| `phone-mcp` | An MCP server so Hermes can set alarms, send messages, call, navigate and more on your phone from any chat platform. | hub |
| `slash` | Slash commands, edit / undo / regenerate and filesystem rollback from the app, via `hermes serve` on loopback. | `hermes`, Python `websockets` |
| `codex` | Codex remote: continue Codex CLI sessions from the phone. Adds `codex-rc` and the `$rc` Codex skill. | Codex 0.161+, Python `websockets` |
| `claude-bridge` | Experimental. Claude Code from the phone: its sessions, tool cards, approvals, steer, images and skills. | `--with-deps`, a Claude login |
| `gateway-platform` | App chats run as Hermes gateway chats (approval cards instead of refusals, clarify, cron results on the phone). | API server key, a gateway restart |
| `proxmox` | Nodes, VMs and containers on the System screen. | SSH access to the Proxmox host (read-only key) |
| `voice-stt` | Speech-to-text with live words as you speak (Parakeet TDT v3, CPU or NVIDIA GPU). | `--with-deps`, a few hundred MB |
| `voice-tts` | Text-to-speech: natural English (Kokoro, 36 voices and mixes), Bulgarian (Piper), optional Azure voices. | `--with-deps --download-models` |
| `voice-proxy` | On the Hermes host: GPU box first, local engine as fallback, one address for the app. | none |

Presets: `minimal` (hub), `recommended` (hub, all skills, phone-mcp), `full` (everything except experimental).

### The skills

| Skill | What it does |
|---|---|
| `phone-agent` | Operate apps on the phone through screen control. |
| `phone-notify` | Notify the phone, and ask for approval with proposals before acting. |
| `homelab-ops` | Service status, logs, backups and updates through the hub. Never restarts without approval. |
| `deploy` | Pull, build and restart one named service after approval. |
| `home` | Home Assistant, the shopping list, Jellyfin or Emby. |
| `follow-ups` | Catches commitments from conversation and reminds you. |
| `inbox-digest` | Triage email and chat into needs-you, FYI and done. |
| `read-later` | Save links, weekly digest. |
| `receipts` | Receipt photos to an expense CSV, monthly summaries. |
| `travel` | Bookings into calendar events and leave-by reminders. |

They follow the Hermes skill authoring standard (60-character descriptions, tags, platforms, When to Use / Pitfalls / Verification sections, no machine-local paths). `python3 install.py check` (or `python3 check_skills.py`) verifies that, and the install refuses a skill that fails it. Skills you edit stay yours: an update saves the new version next to it as `SKILL.md.kit-new`.

## Install options

    --modules a,b,c      module ids        --skills a,b       individual skills (adds the hub)
    --preset NAME        minimal | recommended | full          --all   every non-experimental module
    --without a,b        leave these out   --interactive        always show the menu
    --yes                no confirmation   --dry-run            change nothing
    --user | --system    your account (default without root) or the whole machine (default as root)
    --hermes-home DIR    Hermes home (default $HERMES_HOME or ~/.hermes)
    --service-user NAME  account the voice and bridge services run as
    --set KEY=VALUE      hub setting, repeatable (APP_REPO, HERMES_RELAY_URL, ...)
    --allow-units a,b    systemd units the Ops screen may show and restart
    --with-deps          create virtualenvs and pip install what voice and the Claude bridge need
    --download-models    fetch the text-to-speech voices (about 450 MB)
    --stt-device gpu     speech-to-text on an NVIDIA GPU (default cpu)
    --pve-ssh user@host --pve-key PATH      Proxmox host for the proxmox module
    --prefix DIR         stage under DIR and start nothing (packaging, testing)
    --no-systemd         files only      --json   machine-readable output      --force   see below

Other commands: `status` (what is installed), `verify` (checks each installed module: files, services, ports), `check` (skill standard), `uninstall [--modules a,b] [--purge]`.

### What it will and will not do

- It writes only inside the kit's own places: `/opt/hermes-hub`, `/usr/local/bin/hermes-notify` and `codex-rc`, its systemd units, `~/.hermes/skills/companion`, `~/.hermes/secrets` for its own tokens, and the voice and bridge folders. User installs use `~/.local/...` and `systemctl --user`.
- A file you changed is backed up as `name.bak-kit-<time>` before an update, or kept (skills, with the new version beside it). Settings in `env` are never overwritten unless you pass `--set`.
- A service restarts only when its own files changed. Nothing is started that you did not choose; the Codex bridge starts when you first run `codex-rc`, voice servers once their environments exist.
- It never edits Hermes' `config.yaml` by hand. `phone-mcp` registers itself with `hermes mcp add`, or prints the exact lines if that is not possible.
- `uninstall` removes what it installed and still matches, and keeps your configuration, tokens and edited files unless you add `--purge`. It refuses to remove the hub while other modules need it.
- `--force` goes ahead past blocking checks and overwrites edited skills. Use it knowingly.
- Re-running any command is safe: unchanged files stay unchanged.

State is kept in `<hub dir>/kit-state.json` (versions and file hashes), which is how updates and uninstalls know what is yours.

## Connect the app

1. Server: URL `http://<your-server>:8889` (Hermes API server) and your `API_SERVER_KEY`. With Tailscale and the phone on the same tailnet user, the app can fetch these from `http://<your-server>:8791/pair` instead.
2. Hub URL: `http://<your-server>:8791` (same key).
3. Settings > Notifications: enter the push relay URL and key from the app's owner.

Test a notification (hub URL via `HERMES_HUB`, default `http://127.0.0.1:8791`):

    hermes-notify "Hello" "It works" info 3

## Hub settings

`<hub dir>/env` (created from `env.example`, mode 600):

- `HERMES_ENV` / `HERMES_HOME`: set for you; the hub reads `API_SERVER_KEY` from the `.env` there. The hub service runs as root in a system install (it restarts allowlisted services), as you in a user install.
- `HERMES_RELAY_URL` and `HERMES_RELAY_KEY`: given to you by the owner of the app. When set, every event is also POSTed to `$HERMES_RELAY_URL/notify` (Bearer key, 10 s timeout, errors ignored) with `{title, body, type, priority, session_id, job_id, url}`.
- `APP_REPO`, `NTFY_URL`, ports and more: see the comments in `env.example`.

## Slash commands, undo and checkpoints (`slash`)

The Hermes HTTP API has no slash commands. Like Hermes Desktop, the hub reaches them through the gateway's JSON-RPC WebSocket (`hermes serve`) on loopback: `/hermes/commands` (catalog), `POST /hermes/slash`, `POST /hermes/rewind` (edit / restore checkpoint / regenerate / undo) and `/hermes/rollback` (filesystem checkpoints). The module creates the private token (`~/.hermes/secrets/hermes-serve.token`) and the `hermes-serve` service. `hermes serve` stays on 127.0.0.1; only the hub (behind its key) talks to it.

## Codex remote (`codex`)

Continue Codex CLI sessions from the phone, like Claude Code's `/rc`. Codex 0.161+ runs every session in a shared app-server daemon; `codex_bridge.py` attaches to that daemon and speaks the same wire contract as Hermes, so the app lists your Codex sessions, follows a turn running in the terminal live, and can send, steer, stop and approve commands.

Run `codex-rc` once: it writes `~/.hermes/secrets/codex-bridge.env` (random token, port 8795, all interfaces like the Hermes API), starts the service, and sends the phone a notification that opens the session. Later, inside Codex, type `$rc` (or run `codex-rc` in any shell) to hand the current session to the phone. The hub's `/pair` hands the bridge URL and token to your own devices over Tailscale; otherwise pick Settings › Advanced › Server type › Codex and enter the URL and token.

## Companion gateway platform (`gateway-platform`)

A Hermes plugin (`hermes-plugin/companion`) that makes the app a gateway platform, like Telegram. The API server treats its callers as unattended, so it refuses risky commands and `execute_code` outright; a gateway chat has a person present, so those bring an approval card in the app instead. Clarify questions, slash commands and `/steer` work, and messages sent outside a run (cron `deliver=companion`, `send_message`, background-process notices) are pushed to the phone through the hub.

The plugin serves the same `/v1/runs` API the app already speaks on port 8797, authenticated with the API server key. Each run lands in the app's own Hermes session, so history and the session list do not change. The app sends main-profile turns there and falls back to the API server when the port is out of reach (for example off Tailscale). After installing, run the commands the module prints (enable the plugin, turn on streaming for the `companion` platform, set `COMPANION_HOME_CHANNEL=home`) and restart the gateway.

## Claude Code bridge (`claude-bridge`, experimental)

Claude Code from the phone through the Claude Agent SDK: the Claude Code sessions in `CLAUDE_BRIDGE_CWD`, tool cards, approvals, steer, stop, images and skills. It listens on 127.0.0.1:8792 only; put an authenticated HTTPS or Tailscale proxy in front for phone access and never expose the port directly. Set `CLAUDE_BRIDGE_CWD` in its env file, log in to Claude as the service user, then `systemctl enable --now hermes-claude-bridge`. See `../bridge/README.md`.

## Voice servers (`voice-stt`, `voice-tts`, `voice-proxy`)

Wyoming-protocol servers on ports 10300 (speech-to-text) and 10200 (text-to-speech). Run them on any box, a GPU helps; put `voice-proxy` on the Hermes host so the app needs no new address. Settings (voice names, Azure keys, endpoint timing) live in `<voice dir>/voice.env`. Details, GPU notes and voice names: [voice/README.md](voice/README.md).

    sudo python3 install.py --modules voice-stt,voice-tts --with-deps --download-models --stt-device gpu --yes

## Waking a sleeping voice machine

Set `wake.json` in the hub directory to `{"mac": "AA:BB:CC:DD:EE:FF", "host": "192.168.0.50", "port": 10300}` (or `HUB_WAKE_MAC`, `HUB_WAKE_HOST`, `HUB_WAKE_PORT`). `POST /ops/wake` then sends Wake-on-LAN, but only when `host:port` does not answer and at most once per 20 seconds. The app calls it when you open a chat or the voice sheet and when the wake word fires, so a machine that suspends when idle is up by the time you have finished speaking.

## Alerts without the noise

The hub remembers every alert it has sent (in `hub.db`), so a restart does not repeat them. An alert is pushed again only when it gets worse (a new or more severe line), or after a cooldown (`ALERT_COOLDOWN_HOURS`, default 6). Cron job alerts are compared with numbers and clock lines removed, so "High load: 4.2" followed by "High load: 7.9" is one alert, and temperature alerts hold until the CPU is well below the limit. Mute what you do not care about:

    curl -H "Authorization: Bearer $API_SERVER_KEY" -d '{"key":"guest:my-build-box","hours":8760}' http://127.0.0.1:8791/ops/alerts/mute
    curl -H "Authorization: Bearer $API_SERVER_KEY" http://127.0.0.1:8791/ops/alerts          # what is muted and what was sent

Keys: `guest:<name>`, `job:<id>`, `infra:<key>`, `unit:<name>`, `disk:media`; a trailing `*` mutes a family (`infra:*`). Pushed alert events carry `mute_key`. Mutes live in `alert-mutes.json` next to the hub.

## Ops allowlist

`<hub dir>/ops-allowlist.txt` lists the systemd units the app's Ops screen may show, read logs for and restart, one per line (`#` for comments). Seed it with `--allow-units a,b` or edit it later. Docker containers are discovered automatically for logs. Keep the list short.

## Proxmox and host status (`proxmox`)

The System screen shows the hub host's OS and whether it is bare metal, a VM or an LXC container. On a Proxmox host the hub also lists nodes, VMs and containers through `pvesh`. From inside a guest, give the installer the host and a read-only key and it writes `pve.json` for you:

    sudo python3 install.py --modules proxmox --pve-ssh root@192.168.1.2 --pve-key /root/.ssh/pve-readonly --yes

The hub then runs `pvesh get` on the host over SSH. Endpoints: `GET /ops/infra`, `/ops/infra/node?node=`, `/ops/infra/guest?node=&type=lxc|qemu&vmid=`.

## Chat media

Hermes attaches server files as `MEDIA:/absolute/path`. The app fetches them through `GET /media?path=`, which requires the API key and serves media and common document types (images, PDF, audio, video, text/CSV/Markdown, Office, zip; up to 25 MB) and refuses paths that look like secrets.

## Security notes

- The hub speaks plain HTTP. Keep it on your LAN or Tailscale; do not port-forward it to the internet.
- Every endpoint except `/health` and `/pair` requires `Authorization: Bearer <API_SERVER_KEY>`.
- `/pair` hands out the API key, so it only answers Tailscale addresses whose owner equals this host's Tailscale login. Without Tailscale it always refuses.
- `/profiles` exposes per-profile keys to the main key; the ops restart endpoint runs `systemctl restart` for allowlisted units only.
- `env` holds the relay key; it is created mode 600. Never commit it.
- The optional `/decisions` forwarder is off unless `DECISIONS_URL` and `DECISIONS_API_KEY` are set.
- Installer tokens (`hermes-serve.token`, the Codex and Claude bridge tokens) are generated locally, mode 600, and never printed.

## App updates

The hub serves the companion app's updates. `GET /app/latest` and `GET /app/apk` need the usual bearer key.

**GitHub releases (preferred).** Set `APP_REPO=owner/name` (`--set APP_REPO=owner/name`) and give the hub a token that can read the repo (`GITHUB_TOKEN`, or `gh auth login` as the hub user; a systemd service then needs `HOME` set so gh finds its config). Then publishing a GitHub release with an `.apk` asset is all that is needed: the hub reads the latest release (cached 10 min), downloads the APK once into `APP_DIR` (keeps the newest 2) in the background, and serves it. Until the download finishes `/app/latest` reports `"ready": false` and `/app/apk` returns 503. Tag `v7.6.0` becomes versionName `7.6.0`; the release body is the notes. versionCode comes from `aapt2 dump badging` (`AAPT2` env, or `/opt/android-sdk/build-tools/*/aapt2`), else from a `versionCode: N` line in the release body, else it is omitted and the app compares versionName. The token stays on the hub; the app never sees it.

**No GitHub (fallback).** With `APP_REPO` empty, or if GitHub is unreachable, the hub serves `APP_DIR/latest.json` (default `APP_DIR=$HUB_DIR/app`). Publish with `tools/publish-ota.sh <signed.apk> [notes-file]`.

The app checks every 6 h and from Settings > About.

## Developing the kit

    python3 -m unittest test_install test_hub     # installer, manifest, skills standard, hub (offline)
    python3 check_skills.py                        # skills against the Hermes authoring standard

`modules.json` is the single source of truth for modules, presets, files and checks; the installer, the app's setup screen and the tests all read it. Unit files in `systemd/` are templates (`@HUB_DIR@`, `@PYTHON@`, ...) that the installer fills in; do not copy them by hand.
