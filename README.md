# Hermes Wear

A Wear OS app for talking to a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com/).
Tap, speak, read the streamed reply on your wrist.

This started as a fork of [storm546/hermes-companion-app](https://github.com/storm546/hermes-companion-app)
(MIT) and keeps its history. The Android phone app lives there, not here. Hermes Wear is a separate,
watch-only app that talks to your Hermes server itself, so it does not need the phone app and does not
relay through it. You can run both against the same server.

## What it does

- **Ask Hermes**: opens the watch's voice dictation and sends what you said.
- Streams the reply as it arrives, with a **Stop** button while it runs.
- Follow-up questions keep context until you tap **New chat**.
- Server address and key are stored privately on the watch.

## What it does not do (yet)

- Tool approvals (Allow / Deny). It uses the plain chat endpoint, which has no approval events.
- Tiles, complications, notifications, text-to-speech.
- Anything the phone app does beyond chat.

## Requirements

- Wear OS 3 or newer (API 30), with a speech recognizer installed for dictation.
- A Hermes API server the **watch itself can reach**. A watch cannot join ZeroTier or most VPNs, so use a
  LAN address (watch on home Wi-Fi) or an HTTPS address you have deliberately exposed. Anything reachable
  from the internet should sit behind your key and TLS.

## Install

Download the APK from [Releases](../../releases), then sideload it:

```
adb connect <watch-ip>:<port>
adb install hermes-wear-7.77.0.apk
```

Enter the server on the watch (**Server**), or set it from your PC:

```
adb shell am start -n com.seventhsg.hermeswear/.WatchActivity --es url http://192.168.1.10:8642 --es key YOUR_KEY
```

The key is sent as `Authorization: Bearer ...` to `POST /v1/chat/completions` with `stream: true`.
Plain `http://` is allowed because self-hosted servers often have no certificate.

## Build

JDK 17 and the Android SDK (platform 35) are required.

```
./gradlew :wear:assembleDebug
```

For a signed release set `HERMES_WEAR_KEYSTORE`, `HERMES_WEAR_STORE_PASSWORD`, `HERMES_WEAR_KEY_ALIAS`
and `HERMES_WEAR_KEY_PASSWORD`, then run `./gradlew :wear:assembleRelease`. No key is kept in the repo.

## License

MIT. Original work copyright its upstream author; see [LICENSE](LICENSE).
