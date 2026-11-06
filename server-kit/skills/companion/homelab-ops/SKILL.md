---
name: homelab-ops
description: Check homelab service status and logs through the hub.
version: 1.1.0
author: Hermes Companion
license: MIT
platforms: [linux]
prerequisites:
  commands: [curl]
metadata:
  hermes:
    tags: [Ops, Homelab, Systemd, Monitoring, Proxmox]
    related_skills: [deploy, phone-notify]
---

# Homelab Ops

Read-only visibility into what is running on this server, through the hub's own ops API. No need to shell out to `systemctl` or `journalctl` for routine checks.

## When to Use

- "Is X running", "why is X down", "show the logs for X".
- "How is the homelab", "any updates pending", "when was the last backup".
- A scheduled health check.

## Prerequisites

- The hub module installed and running (`curl http://127.0.0.1:8791/health`).
- `API_SERVER_KEY` in `~/.hermes/.env`. Read it at call time. Never inline the value in a command or print it.
- Services you want shown, restartable or loggable must be listed in `/opt/hermes-hub/ops-allowlist.txt`.

## Procedure

Base URL `http://127.0.0.1:8791/ops/` (local to this server only). Authorise with `Authorization: Bearer $API_SERVER_KEY`. Run these through the `terminal` tool.

```bash
# services the hub tracks
curl -sS -H "Authorization: Bearer $API_SERVER_KEY" http://127.0.0.1:8791/ops/services

# recent logs for one service
curl -sS -H "Authorization: Bearer $API_SERVER_KEY" "http://127.0.0.1:8791/ops/logs?name=<service>"
```

| Endpoint | What it gives |
|---|---|
| `GET /ops/summary` | One small JSON: host CPU, RAM, disk, temperature, guests running, bad services, worst storage, up to 4 problems. Start here. |
| `GET /ops/infra` | Hub host OS, Proxmox nodes and every VM or container, plus live host stats. |
| `GET /ops/infra/guest?node=&type=lxc\|qemu&vmid=` | One guest: OS, uptime, CPU, memory, disk, IPs. |
| `GET /ops/history?key=pve.cpu&range=24h` | Up to 120 points. Keys from `GET /ops/history/keys`. |
| `GET /ops/network` | Tailscale peers, gateway, internet and API latency. |
| `GET /ops/backups` | Last backup per guest and the backup jobs. |
| `GET /ops/updates` | Pending apt and security updates, reboot required. |

Fallback when the API is unavailable or you need more than it exposes:

```bash
systemctl status <service> --no-pager
journalctl -u <service> -n 50 --no-pager
```

## Hard rule: no restarts without approval

This skill is for **checking**, not acting. When a check shows something that needs a restart or a config change:

1. Say what you found: the specific error or symptom from the logs.
2. Propose the fix, do not apply it:

   ```proposal
   {"id":"restart-<service>","title":"Restart <service>","summary":"<what is wrong, one line>","action":"systemctl restart <service>"}
   ```

3. Act only after the user replies `Approved #restart-<service>` or clearly says to go ahead in this conversation.
4. Never restart, reload, stop or modify a service as a side effect of "just checking". Guest power and snapshot endpoints (`POST /ops/infra/guest/action`, `/snapshot`) follow the same rule.

## Logging changes

Every change you make after approval gets one entry in `~/hermes-changes.md`: timestamp, what changed, which file or service, and that the user approved it. Copy any file you edit to `<file>.bak-<date>` first.

## Pitfalls

- `{"ok": false, "error": "unauthorized"}` means the key in `.env` is not the Hermes API server key the hub validates against. Compare the two and tell the user. Do not work around it with `systemctl`.
- Commands on a Proxmox host must be wrapped in `timeout`: an unreachable network mount can hang `df` and `pvesm` forever.
- A service missing from the allowlist returns an error. Ask the user to add it rather than guessing.

## Verification

- `GET /ops/summary` returns JSON with no listed problems after a fix, or lists exactly the problems you reported.
- Your answer quotes the actual log line or state you saw, not a guess.
