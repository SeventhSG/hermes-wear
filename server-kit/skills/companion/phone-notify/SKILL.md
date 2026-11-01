---
name: phone-notify
description: Notify the phone and ask for approval before acting.
version: 1.1.0
author: Hermes Companion
license: MIT
platforms: [linux, macos]
prerequisites:
  commands: [hermes-notify]
metadata:
  hermes:
    tags: [Notifications, Push, Approval, Companion]
    related_skills: [homelab-ops, deploy, follow-ups]
---

# Phone Notify

Reach the user's phone when a background task finishes or finds something, and ask for approval before doing anything consequential.

## When to Use

- A background task, cron job or watcher finishes or notices something while the user is away.
- You prepared an action (a reply, a purchase, a config change) that needs the user's OK first.
- The user says they sent a file from their phone.

## Prerequisites

- The `hermes-notify` command and a running hub (both from the hub module). `curl http://127.0.0.1:8791/health` answers.

## Procedure

### Send a notification

Run through the `terminal` tool:

```bash
hermes-notify "<title>" "<body>" [type] [priority] [session_id]
```

- `type`: `alert`, `job`, `briefing`, `proposal`, `done` or `info` (default `info`).
- `priority`: 1 to 5 (default 3). Use 5 for urgent things. `alert`, `proposal` and priority 4 or higher ring as urgent on the phone.
- `session_id` (optional): the conversation to open when the notification is tapped. Pass the current session id when it is about this conversation, so the phone opens it with a Reply button.
- `title` and `body` are plain text, not Markdown.

```bash
hermes-notify "Deploy finished" "The nightly build shipped without errors." done 2
hermes-notify "Disk almost full" "/ is at 92% on this server." alert 5
```

### Ask for approval with a proposal

End your reply with one fenced `proposal` block per action:

```proposal
{"id":"<short-id>","title":"...","summary":"...","action":"<what you will do when approved>"}
```

- `id`: short and memorable (`reply-1`, `buy-cable`).
- `title` and `summary` are shown in the app.
- `action` says exactly what will happen, so nothing is ambiguous later.

When the user replies **`Approved #<id>`**, carry out the action. On **`Rejected #<id>`**, drop it. To alert them that something is waiting:

```bash
hermes-notify "Approval needed: reply-1" "Draft reply to Jane ready for review." proposal 4
```

### Files from the phone

Files the user uploads from the app land in `~/incoming/phone/`. Look there when they say they sent something.

## Pitfalls

- One notification per event. Do not spam: bundle many items into one `briefing`.
- Never put secrets in a title or body: they are stored in the hub and shown on the lock screen.
- A proposal is not approval. Do not act before the matching `Approved #<id>`.

## Verification

- `hermes-notify` prints a JSON response and exits 0.
- For a proposal, the id in your block is the id you wait for.
