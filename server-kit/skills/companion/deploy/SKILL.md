---
name: deploy
description: Deploy a git project and restart only its named service.
version: 1.1.0
author: Hermes Companion
license: MIT
platforms: [linux]
prerequisites:
  commands: [git, systemctl, hermes-notify]
metadata:
  hermes:
    tags: [Deploy, Git, Systemd, Ops]
    related_skills: [homelab-ops, phone-notify]
---

# Deploy

Ship a change to a project running on this server without breaking anything.

## When to Use

- The user says "deploy X", "pull and restart X", "ship the latest of X".
- A cron job or watcher reports that a git-tracked project is behind and the user asked to keep it current.

## Prerequisites

- `git` and `systemctl` on this host, and the `hermes-notify` command (installed with the hub module).
- The project is a git checkout and runs as a systemd service.

## Hard rules

- **Never restart or reload a service on your own initiative.** Pull, build and test freely; restart only after the user approves it (a plain "yes" or "go ahead", or `Approved #<id>` on a `proposal` block, see `phone-notify`).
- **Never touch a service other than the one named.** Do not restart a related service, a timer or the whole stack.
- Before you hand-edit a file as part of a deploy, copy it to `<file>.bak-<date>`. A plain `git pull` does not need this, git keeps the history.
- Log the deploy in `~/hermes-changes.md` (format in `homelab-ops`).

## Procedure

1. **Find the service and its directory** in the map below, or with the `terminal` tool: `systemctl cat <service>` shows `WorkingDirectory=` and `ExecStart=`.
2. **Pull.** In the project directory run `git status` first. If there are uncommitted local changes, stop and ask the user. Otherwise `git pull`.
3. **Build and test** what the project needs (`npm install && npm run build`, `pip install -r requirements.txt`, its test suite) before touching the running service.
4. **Ask for approval to restart**, unless the user already said "deploy and restart". For a background run use a `proposal` block:

   ```proposal
   {"id":"deploy-<service>","title":"Restart <service>","summary":"Pulled N commits, build and tests passed.","action":"systemctl restart <service>"}
   ```

5. **Restart only that service** once approved: `systemctl restart <service>`.
6. **Verify health** (see Verification).
7. **Report** with `hermes-notify "Deploy: <service>" "<summary>" done 2`, or `alert 5` on failure with what you saw and whether you rolled back.
8. **Append one line** to `~/hermes-changes.md`: timestamp, what changed, which service restarted, and that the user approved it.

## Service map

Keep your own map here (edit this table for your server).

| Service | WorkingDirectory | Entry point |
|---|---|---|
| `hermes-hub` | `/opt/hermes-hub` | `hub.py` |
| `<your-service>` | `<its directory>` | `<its command>` |

Do not trust the table for a service that is not listed, or after a long time: re-check with `systemctl cat <service>` in case the unit changed.

## Pitfalls

- A failed pull or build means **no restart**. Report the real error (`hermes-notify ... alert 4`) and leave the working tree as it is.
- `git reset --hard origin/<branch>` destroys local work. Only run it if the user says so in this conversation.
- A service can be `active` and still broken: always read the log, not only the state.

## Verification

- `systemctl is-active <service>` prints `active`.
- `journalctl -u <service> -n 30 --no-pager` shows a clean start with no traceback.
- If the service serves HTTP, `curl -sS -o /dev/null -w '%{http_code}\n' <local url>` returns 200.
