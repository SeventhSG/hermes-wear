---
name: read-later
description: Save links to read later and digest them weekly.
version: 1.1.0
author: Hermes Companion
license: MIT
platforms: [linux, macos]
metadata:
  hermes:
    tags: [Reading, Bookmarks, Links, Digest]
    related_skills: [phone-notify]
---

# Read Later

A flat file holds the user's saved links: `~/.hermes/read-later.md`. No app, no database.

## When to Use

- The user sends a URL and says "read later", "save this" or "bookmark this".
- "Digest my reading list", "what have I saved this week".

## Prerequisites

- `~/.hermes/` is writable. Fetch page titles with `web_extract`.

## File format

One pipe-delimited row per link. Create the file with this header if it does not exist:

```
date | title | url | summary | status
```

- `date`: `YYYY-MM-DD`, the day it was saved.
- `title`: short and human-readable.
- `url`: the link.
- `summary`: one line on what it is about and why it was saved.
- `status`: `unread` or `read`.

```
2026-09-27 | Why Postgres beats Mongo for this | https://example.com/pg | Argues JSONB covers most Mongo use cases with real transactions. | unread
```

## Procedure

### Add a link

1. Fetch the page title with `web_extract` if the user did not give one.
2. Write a one-line summary.
3. Append a row with today's date and status `unread`, creating the file with the header first if needed.

### Weekly digest

1. Read all `unread` rows, or the last 7 days if the user wants a time-boxed view. Ask if it is ambiguous.
2. Group by topic, inferred from title, summary and URL. Do not ask the user to tag anything.
3. Under each topic list: title (linked), a summary of at most three lines, and the URL.
4. Ask whether to mark everything included as `read`. On yes, rewrite the file with those rows' `status` set to `read`: same file, same format, no reordering, no dropped rows. Copy it to `read-later.md.bak-<date>` first.
5. When run unattended (cron), send the digest as `hermes-notify ... briefing`. Otherwise just reply with it.

## Pitfalls

- Saves are append-only. Rewrite the file only to mark items read, and back it up first.
- A paywalled or dead link still gets saved: say that you could not read it.
- With a long list, default the digest to `unread` and say how many older ones exist.

## Verification

- After a save, `read_file` shows the new row at the end with the header intact.
- After marking read, the row count is unchanged.
