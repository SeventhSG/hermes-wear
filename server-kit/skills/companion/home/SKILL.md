---
name: home
description: Control Home Assistant, shopping list and a media server.
version: 1.1.0
author: Hermes Companion
license: MIT
platforms: [linux, macos]
prerequisites:
  commands: [curl]
required_environment_variables:
  - name: HA_URL
    prompt: Home Assistant URL (for example http://homeassistant.local:8123)
    help: Only needed for raw REST calls; the homeassistant toolset has its own setup.
    required_for: raw Home Assistant calls
  - name: MEDIA_URL
    prompt: Media server URL (Jellyfin or Emby)
    help: For example http://media.local:8096
    required_for: media server control
  - name: MEDIA_API_KEY
    prompt: Media server API key
    help: Create a dedicated key in the media server's dashboard.
    required_for: media server control
metadata:
  hermes:
    tags: [Smart Home, Home Assistant, Shopping List, Media, Jellyfin]
    related_skills: [phone-notify]
---

# Home

Run scenes and entities in Home Assistant, manage the shopping list, and control a Jellyfin or Emby media server.

## When to Use

- "Turn on the living room lights", "run the goodnight scene", "add milk to the shopping list".
- "Play X on the TV", "what's playing", "pause the music".

## Prerequisites

Check what is actually enabled before relying on it.

| Integration | Needs |
|---|---|
| Home Assistant | The `homeassistant` toolset enabled in Hermes. `HA_URL` and the token in `~/.hermes/config.yaml` (`homeassistant.token`) are only needed for raw REST calls. |
| Printing | Not available by default. Needs CUPS or another print path. |
| Media server | `MEDIA_URL` and `MEDIA_API_KEY` in `~/.hermes/.env`. Restart the gateway after editing `.env`. |

Read secrets from their file at call time. Never paste a token or key into a command line, a reply or a log.

## Procedure

### Home Assistant

For ordinary requests use the `homeassistant` toolset directly, with no hand-written HTTP. For anything it does not cover, call the REST API through the `terminal` tool:

```bash
# entity state
curl -sS -H "Authorization: Bearer $HA_TOKEN" "$HA_URL/api/states/<entity_id>"

# run a scene
curl -sS -X POST -H "Authorization: Bearer $HA_TOKEN" -H "Content-Type: application/json" \
  -d '{"entity_id":"scene.goodnight"}' "$HA_URL/api/services/scene/turn_on"
```

### Shopping list

Uses Home Assistant's built-in shopping list integration:

```bash
# add an item
curl -sS -X POST -H "Authorization: Bearer $HA_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"milk"}' "$HA_URL/api/services/shopping_list/add_item"

# read the list
curl -sS -H "Authorization: Bearer $HA_TOKEN" "$HA_URL/api/shopping_list"
```

A 404 means the integration is not enabled in Home Assistant (`shopping_list:` in its `configuration.yaml`).

### Media server

Every request carries the header `X-Emby-Token: $MEDIA_API_KEY`.

```bash
# search movies, series, episodes and tracks
curl -sS -H "X-Emby-Token: $MEDIA_API_KEY" \
  "$MEDIA_URL/Items?searchTerm=<query>&Recursive=true&IncludeItemTypes=Movie,Series,Episode,Audio"

# active sessions (you need a session Id to control playback)
curl -sS -H "X-Emby-Token: $MEDIA_API_KEY" "$MEDIA_URL/Sessions"

# play an item on a session
curl -sS -X POST -H "X-Emby-Token: $MEDIA_API_KEY" \
  "$MEDIA_URL/Sessions/<sessionId>/Playing?playCommand=PlayNow&itemIds=<itemId>"

# pause, next track
curl -sS -X POST -H "X-Emby-Token: $MEDIA_API_KEY" "$MEDIA_URL/Sessions/<sessionId>/Playing/Pause"
curl -sS -X POST -H "X-Emby-Token: $MEDIA_API_KEY" "$MEDIA_URL/Sessions/<sessionId>/Playing/NextTrack"
```

Typical flow: find the target device's `Id` in `/Sessions`, find the item's `Id` with a search, then `POST /Sessions/{id}/Playing`. For "recently added" you need a `userId` from `/Users`.

## Pitfalls

- If `MEDIA_API_KEY` is empty after editing `.env`, the gateway needs a restart. Tell the user. Do not look for the key elsewhere.
- Do not print anything. If the user asks and there is no CUPS setup, say so instead of trying workarounds.
- A scene or service call that changes the house (locks, alarms, heating) needs the user's explicit request in this conversation.

## Verification

- After a Home Assistant action, read the entity state back and report what it is now.
- After starting playback, `/Sessions` shows `NowPlayingItem` for that session.
