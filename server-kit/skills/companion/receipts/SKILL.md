---
name: receipts
description: Log receipts to an expense CSV and summarise by month.
version: 1.1.0
author: Hermes Companion
license: MIT
platforms: [linux, macos]
metadata:
  hermes:
    tags: [Receipts, Expenses, Budget, Vision]
    related_skills: [phone-notify]
---

# Receipts

Turns a receipt (a photo from the phone, or pasted text) into one row in a running expense log.

## When to Use

- The user sends a receipt photo or pastes receipt text.
- "How much did I spend on groceries this month", "monthly summary".

## Prerequisites

- A model that can read images, for photos (`vision_analyze`).
- Photos from the phone land in `~/incoming/phone/` (see `phone-notify`).

## File format

`~/.hermes/expenses.csv`. Create it with this header if it does not exist:

```csv
date,vendor,total,currency,category,note
```

- `date`: `YYYY-MM-DD` from the receipt. Use today if it is illegible.
- `vendor`: the merchant as printed.
- `total`: the final total paid, numeric, no currency symbol.
- `currency`: ISO code (`EUR`, `USD`, `GBP`). Infer it from the symbol or context if not printed.
- `category`: `groceries`, `dining`, `transport`, `utilities`, `electronics`, `health`, `household`, `entertainment` or `other`.
- `note`: optional, anything worth flagging (a split bill, a large single item).

Quote any field that contains a comma.

## Procedure

### Log a receipt

1. Read the photo with `vision_analyze`, or parse the pasted text.
2. Extract vendor, date, total, currency and category. If something is illegible make a reasonable guess and say so. Do not block.
3. Append one row to `expenses.csv`, creating it with the header first if needed.
4. Confirm briefly what was logged (vendor, total, category). Do not ask for approval, this is not consequential.

### Monthly summary

1. Filter the rows to the requested month (default: the current one).
2. Sum `total` per `category` and overall. Keep currencies separate. Never mix them.
3. Present a short table: category, count, total. Name the top two or three categories.
4. Note anything unusual, such as one outsized expense or a category spike.

## Pitfalls

- A number you had to guess belongs in `note` ("total unclear, read as 18.40").
- The same receipt sent twice: check the last rows for the same vendor, date and total before appending.
- Tips and tax lines: log the final total paid, not the subtotal.

## Verification

- `read_file` shows exactly one new row, with the right column count.
- The monthly totals add up to the sum of the listed rows.
