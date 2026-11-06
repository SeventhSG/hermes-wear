# Hermes Wear

Hermes on your wrist, for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com/).
Talk to your agent, read the streamed reply, and open older chats. That is all it does, on purpose.

This is a fork of [storm546/hermes-companion-app](https://github.com/storm546/hermes-companion-app) (MIT)
and keeps its history. There are two apps in this repo:

| App | Folder | What it is |
|---|---|---|
| **Hermes Wear** | `wear/` | The watch app: talk, chat, older chats. |
| **Hermes Bridge** | `app/` | The upstream phone app, version 7.77.0, plus a small relay that serves the watch. |

Both use the package id `com.seventhsg.hermeswear` and the same signing key. That is required: the Wear
Data Layer only links a watch app and phone app that share both. Hermes Bridge installs next to the
original Hermes app without replacing it, and keeps its own settings.

## How it works

By default the watch does not hold your server address or key. It asks the phone, and the phone talks to
Hermes with its own connection and profile. So:

- The watch shows the **same chats** as the phone app and continues the chat the phone has open.
- It uses the **server and profile you set up on the phone**, including a VPN the watch cannot join (ZeroTier).
- **Tool approvals** (Allow once / Deny) come to the watch.
- The agent is told about the watch on each turn: device model, battery and charging, local time and
  time zone. It is told to keep replies short. No location and no health data are sent.

If you do not want the phone in the loop, the watch can instead talk to a Hermes API server directly
(**Using phone** on the watch, then a server address and key). Direct mode keeps one chat on the watch,
has no chat list, no approvals, and the watch itself must be able to reach the server.

## Not included

- Heart rate, steps or other health data for the agent.
- Tiles, complications, watch notifications, spoken replies.
- Anything else the phone app does. Use the phone app for that.

## Requirements

- Wear OS 3 or newer (API 30), with a speech recognizer installed for dictation.
- Android phone, Android 8 or newer, paired to the watch with the Wear OS app.

## Install

Download both APKs from [Releases](../../releases).

```
adb install hermes-bridge-7.77.0.apk     # on the phone
adb connect <watch-ip>:<port>
adb -s <watch-ip>:<port> install hermes-wear-7.77.0.apk
```

Open Hermes Bridge on the phone and connect it to your server like the original app. Then open Hermes on
the watch and tap **Talk to Hermes**.

Direct mode can also be set from a PC:

```
adb shell am start -n com.seventhsg.hermeswear/.WatchActivity --es url http://192.168.1.10:8642 --es key YOUR_KEY
```

## Build

JDK 17 and the Android SDK (platform 35).

```
./gradlew :wear:assembleDebug :app:assembleDebug
```

For signed releases set `HERMES_WEAR_KEYSTORE`, `HERMES_WEAR_STORE_PASSWORD`, `HERMES_WEAR_KEY_ALIAS` and
`HERMES_WEAR_KEY_PASSWORD` (the phone app reads the same values under the `HERMES_COMPANION_` prefix), then
`./gradlew :wear:assembleRelease :app:assembleRelease`. Use one key for both. No key is kept in the repo.

## License

MIT. Original work copyright its upstream author; see [LICENSE](LICENSE).
