# Proposal: an extension API for Hermes Companion

Status: draft for discussion. Author: SeventhSG.

## Why

Hermes Companion is a closed box for other apps today. There is no way for a separate app to use the
user's Hermes connection, so every integration (a watch, a car head unit, a launcher widget, an
automation app) has to be built into the main app or has to ask the user for the server URL and key a
second time.

Termux solved this with Termux:API and its plugin apps: small separate apps, installed from anywhere,
that the user explicitly allows. This proposal does the same for Hermes Companion, so the community can
build extensions without anyone patching the main app.

First user: **Hermes Wear**, a Wear OS app that needs to list chats, send a prompt, stream the reply and
answer approvals through the phone. It is the reference extension and its relay code already exists.

## Principles

1. **The key never leaves the app.** Extensions ask the app to do things. They never receive the server
   URL, API key, hub token or capture token.
2. **Off by default, user decides.** Nothing is reachable until the user turns extensions on and approves
   each one, capability by capability.
3. **Small, versioned surface.** One bound service, a few capabilities, an `apiVersion` so it can grow.
4. **Same safety rules as the app.** Extension calls go through the Permission centre kill switch, risk
   tiers and the local activity log, tagged with the extension's name.

## Discovery

An extension app declares a service:

```xml
<service android:name=".MyExtensionService" android:exported="true">
  <intent-filter>
    <action android:name="cloud.stormlabs.hermescompanion.action.EXTENSION" />
  </intent-filter>
  <meta-data android:name="hermes.extension.label" android:value="Hermes Wear" />
  <meta-data android:name="hermes.extension.description" android:value="Talk to Hermes from your watch" />
  <meta-data android:name="hermes.extension.capabilities" android:value="chat.read,chat.send,chat.approve,context.provide" />
  <meta-data android:name="hermes.extension.apiVersion" android:value="1" />
</service>
```

Companion finds these with `PackageManager.queryIntentServices` (plus a `<queries>` entry) and lists them
under **Settings > Extensions**. The user sees the name, description, requested capabilities, package
name and signing certificate hash, and enables each one. An extension is pinned to the certificate it was
approved with. If the certificate changes, it must be approved again.

## Transport

Companion exposes one bound service, `HermesHostService`, with an AIDL interface `IHermesHost`. The
extension binds to it. For events, the extension passes an `IHermesCallback` that the host calls back.
Every call checks the caller's UID against the approved package, certificate and granted capability.

## Capabilities

Each is granted separately. An extension only gets what it declared and the user approved.

| Capability | What it allows |
|---|---|
| `chat.read` | `currentSessionId()`, `listSessions(limit)`, `messages(sessionId, limit)` |
| `chat.send` | `createSession()`, `sendMessage(sessionId, text)` with streamed events, `stopRun(sessionId)` |
| `chat.approve` | answer an approval request: `approve(runId, requestId, generation, choice)` |
| `context.provide` | push short per-turn context lines (watch battery, car state, location of a place), added to the ephemeral system message and shown to the user in the Permission centre |
| `events.subscribe` | run finished, approval requested, new unread reply (so an extension can notify elsewhere) |
| `actions.register` | contribute device actions to the app's ` ```device ` protocol, executed by the extension, respecting the LOCAL and SENSITIVE tiers |

Never exposed: server URL, API key, hub or capture credentials, file access, hub or Homelab calls, the
accessibility service, screen context.

## Streaming callback

```
interface IHermesCallback {
    void onDelta(String text);
    void onDone(String finalText);
    void onError(String message);
    void onApproval(String runId, String requestId, int generation, String command);
    void onEvent(String type, String json);   // events.subscribe
}
```

## User controls

- **Settings > Extensions**: master switch (default off), list of installed extensions, per-capability
  toggles, revoke at any time.
- The existing **kill switch** pauses all extension calls too.
- The **activity log** records each call as "Extension: Hermes Wear, chat.send", with timing.
- **Rate limit** per extension (for example 30 sends per minute) to stop a runaway extension.
- Sensitive actions keep asking for device authentication or confirmation exactly as today.

## For extension authors

Ship a small library, `hermes-extension-api`, containing the AIDL files, a `HermesHost` helper that
handles binding and reconnecting, and a sample extension. Document the capabilities and the discovery
metadata in `docs/EXTENSIONS.md` in the main repo. Extensions are ordinary Android apps, installed from
anywhere (GitHub releases, F-Droid, Play), the same way Termux plugins are.

## Reference implementations

- **Hermes Wear Bridge** (https://github.com/SeventhSG/hermes-wear): a thin phone extension plus a Wear OS
  app. It uses `chat.read`, `chat.send`, `chat.approve` and `context.provide`. A working version of the
  relay logic already exists in that fork as `WearRelayService`, built on the app's `Backends` and
  `streamChat`, and can be adapted into the host side of this API.

## Offer

I can prepare the host side as a pull request: `HermesHostService`, the AIDL, the Settings screen, the
permission checks, tests, and the author docs, kept small and behind the off-by-default switch. I would
rather agree the shape here first, so nothing in the PR is a surprise.

## Open questions

- AIDL or Messenger for the transport? AIDL is cleaner for streaming, Messenger is simpler.
- Which capabilities belong in version 1? A minimal first version could be `chat.read`, `chat.send`
  and `chat.approve`, with the rest added later.
- Should extensions be allowed to run while the app is in the background? The app already keeps a
  foreground service during runs, so it can likely reuse that.
