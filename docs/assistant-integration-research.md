# Device integration: what Google Assistant / Gemini use, and what Hermes can use

Research date: 2026-10-19. Goal: find the integration surfaces Google's assistant relies on and decide which
ones Hermes Companion (a third-party app holding the ASSISTANT role) can use. No Google binary was
decompiled; the test phone was not on adb. The sources are the public Android API reference, the
AppFunctions and Computer Control developer guides, and reports on Gemini's screen automation.

## How Google's assistant reaches into the phone

| Surface | What it gives Google | Can Hermes use it? |
| --- | --- | --- |
| `VoiceInteractionService` / `VoiceInteractionSession` (ASSISTANT role) | Long-press home / side key, the overlay, `onHandleAssist` (AssistStructure = view tree text), `onHandleScreenshot` | **Yes, already used** (HermesVoiceSession). |
| `AssistContent` (sent with the assist data) | The foreground app's own context: current web URI (Chrome), schema.org JSON-LD, an intent and clip data | **Yes, added in this release:** app package, page URL and structured data go into the screen context. |
| Direct Actions (API 29): `VoiceInteractionSession.requestDirectActions` / `performDirectAction`, app side `Activity.onGetDirectActions` / `onPerformDirectAction` | Actions the foreground activity offers right now, with their parameters (for example "play", "send") | **Yes, role holder only:** this is the cleanest way to act in the app on screen without tapping. Few apps implement it, so it adds to screen control and does not replace it. Not built yet. |
| App Actions capabilities in `shortcuts.xml` (`actions.intent.*`) | Deep links straight to the right screen | **Yes, already used** (`AppActions`, `list_app_actions` / `app_action`). |
| **AppFunctions** (Android 16+, `android.permission.EXECUTE_APP_FUNCTIONS`, Jetpack `@AppFunction`) | Typed functions apps expose to agents, an "on-device MCP" (for example create a note or a task, or search a list) | **Probably via Shizuku.** The permission is not grantable to normal apps (Gemini is in a private preview). The developer guide lists functions with `adb shell cmd app_function list-app-functions`, so the **shell user** can reach the service. Our `AgentDisplayService` already runs as shell through Shizuku, so it can run the same `cmd app_function` list and execute calls. Check on the A54 (One UI 8 = Android 16) first. |
| **Computer Control** (`VirtualDeviceManager` ComputerControl, `ACCESS_COMPUTER_CONTROL`) | Gemini's "task automation": apps run in a secure **background virtual display** and the agent drives them, with a per-app consent dialog and a hand-back before payment | **No.** The OEM allowlists it for preloaded assistants. Our hidden display (Shizuku `AgentDisplayService`) is the same design built by hand. Copy its UX: a per-app first-use consent, a stop before payment or send, a live preview and "take over". |
| Hotword DSP (`AlwaysOnHotwordDetector`, `HotwordDetectionService`, `CAPTURE_AUDIO_HOTWORD`) | Wake word at near-zero battery cost, works with the screen off | **No.** These need privileged or preinstalled status. WakeWordService (microphone foreground service) stays. |
| Notification listener and reply actions | Read and answer messages | **Yes, already used** (NotificationReader, `reply`). |

## Recommended next steps (by value)

1. **AppFunctions through Shizuku.** Add a `list_app_functions` query and an `app_function` action. Both run `cmd app_function list-app-functions` / `execute-app-function` in the shell user service and feed the schema to Hermes. This gives typed calls into Samsung Notes, Calendar and Google apps when they ship functions, with no screen scraping. First run on the A54: `adb shell cmd app_function list-app-functions` (and `help`) to see the exact verbs and which apps expose functions.
2. **Direct Actions** in HermesVoiceSession. On `onHandleAssist(AssistState)`, call `requestDirectActions(state.getActivityId(), …)`, list the actions in the screen context, and add a device action `direct_action {id, args}` that calls `performDirectAction`. Without the role this API returns nothing, and we hold the role.
3. **Computer-Control-style safety on the hidden display.** Ask per-app consent on first automation. Make a hard stop before any payment, send or delete screen a rule of the phone agent, not only of the prompt.
4. **Inspect the Google app when the phone is back on adb:** `adb shell dumpsys package com.google.android.googlequicksearchbox | grep -A200 "requested permissions"` and `adb shell dumpsys role`. These show the privileged permissions and roles it uses, so we know what is reachable and what is OEM-only.

## Sources

- https://developer.android.com/ai/appfunctions
- https://developer.android.com/ai/computer-control
- https://developer.android.com/reference/android/companion/virtual/VirtualDeviceManager
- https://developer.android.com/reference/android/service/voice/VoiceInteractionSession
- https://9to5google.com/2026/03/12/gemini-android-app-automation-galaxy-s26-rollout/
- https://www.androidauthority.com/android-computer-control-feature-3603862/
