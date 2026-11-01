---
name: rc
description: >
  Remote control: continue this Codex session from the phone (Hermes Companion). Use when the user
  types $rc, "rc", "remote control", "continue on my phone" or "hand this to my phone".
---

Run `codex-rc` with no arguments (it picks this session from `$CODEX_THREAD_ID`) and show the user its
output verbatim. Do nothing else. It talks to local services (the bridge and
hermes-hub), so ask for escalated permissions if the sandbox blocks network access. The phone gets a notification that opens this
session; from there the user can watch, send, steer, stop and approve while you keep working here.
