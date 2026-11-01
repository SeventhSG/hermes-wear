---
name: travel
description: Turn bookings into phone calendar events and reminders.
version: 1.1.0
author: Hermes Companion
license: MIT
platforms: [linux, macos]
prerequisites:
  commands: [hermes-notify]
metadata:
  hermes:
    tags: [Travel, Calendar, Reminders, Itinerary]
    related_skills: [phone-notify, phone-agent]
---

# Travel

Converts a pasted or forwarded booking confirmation into things that show up on the user's phone: calendar events and a leave-by reminder.

## When to Use

- The user pastes or forwards a flight, train, hotel or car-rental confirmation.
- "Add my trip to the calendar", "remind me when to leave for the airport".

## Prerequisites

- The Companion app is connected, so `device` blocks reach the phone.
- `hermes-notify` for scheduled reminders further out.

## Procedure

### Read the confirmation

For each leg or booking, extract:

- What it is (flight, train, hotel check-in, car rental).
- Start and end date and time (hotel: check-in to check-out).
- Location, as specific as the text gives (airport, station, hotel address).
- Reference or confirmation number.
- Anything the user must act on (check-in opens at X, gate or platform to be announced).

### Calendar events

Emit one `device` block per leg or booking:

```device
{"action":"calendar_event","title":"Flight LH1234 SOF→FRA","start":"2026-10-03T06:20","end":"2026-10-03T08:45","location":"Sofia Airport (SOF)","description":"Confirmation ABC123. Terminal 2."}
```

- `start` and `end` are local time, `YYYY-MM-DDTHH:MM`, no timezone suffix. Use the timezone of the place itself. Do not convert to server time.
- `description` carries the confirmation number and anything useful at the gate or desk.
- A round trip is two blocks. A multi-night hotel stay is one block from check-in to check-out.

### Leave-by reminders

For anything with a fixed departure, work back from `start` with a sane buffer: domestic flight about 2 h, international about 3 h, train 20 to 30 min.

- Within the next 24 h: a `set_alarm` block. The phone's alarm takes only `hour` and `minute` (24 h clock, no date) and rings at the next occurrence, so use it only for today or tomorrow.

  ```device
  {"action":"set_alarm","hour":4,"minute":0,"label":"Leave for the airport"}
  ```

- Further out: schedule a task with the `cronjob` tool that runs `hermes-notify "Leave soon" "Flight LH1234 boards in 2h, leave for the airport now." alert 5` at the right time. Note the schedule in `~/hermes-changes.md`.

## Pitfalls

- Never guess a confirmation number or time that is not in the text. Ask instead.
- For a trip with several legs, show the whole itinerary leg by leg before or with the blocks, so the user can catch a misread date before it lands on their calendar.
- Daylight-saving changes and overnight flights: re-check the end date.

## Verification

- Each block has a start before its end, in the right year.
- The itinerary you read back matches the confirmation line by line.
