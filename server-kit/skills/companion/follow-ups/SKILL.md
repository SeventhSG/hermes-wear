---
name: follow-ups
description: Track commitments and remind the user when they fall due.
version: 1.1.0
author: Hermes Companion
license: MIT
platforms: [linux, macos]
prerequisites:
  commands: [hermes-notify]
metadata:
  hermes:
    tags: [Reminders, Commitments, Productivity, Cron]
    related_skills: [phone-notify]
---

# Follow-ups

Catches things the user says they will do ("I'll send Jane the invoice tomorrow", "remind me to call the landlord Friday") so they do not get lost in chat history.

## When to Use

- The user states a commitment or asks to be reminded, in any conversation. Capture it silently.
- The user asks "what am I supposed to do", "what did I say I'd do" or "what's due".
- A daily scheduled run checks what is due.

## Prerequisites

- The `hermes-notify` command for nudges (installed with the hub module).
- A daily scheduled task (use the `cronjob` tool) if the user wants automatic nudges.

## File format

`~/.hermes/followups.md`, one row per commitment. Create it with this header if missing:

```
date_added | commitment | due | status
```

- `date_added`: `YYYY-MM-DD`, when it was said.
- `commitment`: short, in the user's own words ("Send Jane the invoice").
- `due`: `YYYY-MM-DD`. Resolve "tomorrow" or "Friday" to a real date. Use `-` when no time was given.
- `status`: `open`, `done` or `dropped`.

## Procedure

### Capture

1. Do not interrupt the conversation to confirm. Append a row with `write_file` or `patch`: today's date, the resolved due date or `-`, status `open`.
2. If the file does not exist, create it with the header first.
3. Capture both directions: things the user will do, and things they ask you to remind them of.
4. Only mention captured items when the user asks. Do not narrate every capture.

### Daily check

1. Read `followups.md` with `read_file` and keep the `open` rows.
2. Due today or overdue: nudge. Due tomorrow: an optional heads-up.
3. Send each with `hermes-notify "Follow-up due" "<commitment>" alert 3`, or bundle many into one `briefing` notification.
4. Never mark anything `done` yourself. Only the user closes an item. Mark `dropped` only when they say so.

### Mark done

When the user says they did it ("sent it", "done"), find the row and set `status` to `done`. Copy the file to `followups.md.bak-<date>` first, because this is an in-place edit.

## Pitfalls

- Relative dates drift: always store the resolved `YYYY-MM-DD`.
- Do not duplicate a commitment that is already `open`. Match on meaning, not wording.
- Do not send more than one nudge per item per day.

## Verification

- After a capture, `read_file` shows the new row in the right column order.
- After a daily check, every notification you sent corresponds to a row with `due` today or earlier.
