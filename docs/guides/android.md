# Android app

`apps/android/` is the native Android client: Kotlin, Jetpack Compose and Material 3, styled with the
shared [design system](../design/hypurr-design-system.md) tokens (seed palette, colour flow, glass
panels, M3 Expressive springs, light/dark). Application id `com.ragul84.hypurr`, minSdk 26, targetSdk 35.
Android 12+ wallpaper colours are an opt-in toggle in Settings; the Hypurr palette is the default.

## What it does

| Area | Status |
| --- | --- |
| Pairing | QR scan (zxing) or a pasted / opened `hypurr://pair?v=3…` link; `pair` over the E2E channel, `platform: "android"` |
| Transports | Direct `ws://host:port/channel?v=1` (LAN, Tailscale) raced for 1.5 s, then the cloud relay `wss://…/v1/relay/device/{id}?v=1` with `Hypurr-Sig`, presence, ping |
| Crypto | Port of `RelayCrypto.swift` / `host/src/remote/crypto.rs` (BouncyCastle): handshake, frames, mailbox, push, SAS, request signatures, tested against `docs/reference/fixtures/remote-relay-vectors.json` |
| Bots | Roster (pinned, latest first), **Needs you** section, working / failed states, unread counts, live updates from `sub: events` |
| Chat | History, send (optimistic, replaced by the host's entry), offline sends queued in the relay mailbox, stop, approval cards (`respondPermission`), notices |
| Beginner tasks | **New task**: plain-language goal or template, Hypurr's plan (project + agent + reason, each changeable), start. Task chats show the branch and checkpoints; **Go back** to any checkpoint, **Save now**, **Finish task**. Add a screenshot, paste an image or error, or start **From an issue** (GitHub / Jira). Finishing offers learning mode, a pull request and posting the result; the chat then shows a "What changed and why" card with files, PR link and cost. The task strip shows what the task cost so far. See [tasks and the safety net](../features/tasks-and-safety.md) |
| Approvals | Explained cards: plain sentence, risk pill (low / medium / high), why, raw details on demand, "Blocked by the safety net", **Undo** after approving on a task |
| Team admin | Settings › Team: **Activity** (spend, tasks, approvals and blocks per person, recent tasks), **Rules** (spending limits, allowed agents, approval levels, protected branches; read-only for non-admins), **People** (role per phone, role for new phones), **Audit log** (intact check, newest first). Cards that need an admin say so; members see "Waiting for an admin". See [team admin](../features/team-admin.md) |
| Settings | Safety net (block protected branches, always ask for high risk, require git), my templates, when a task finishes (learning mode, pull request, post the result), work tools (GitHub, Jira, Slack, Teams: save, test, remove), spending (today, 7 days, all time, per task), theme (system / light / dark), dynamic colour, notifications, computer details, forget computer |
| Push | FCM client: token → `relay/` ticket (`env: "fcm"`) → `registerDevice` with the X25519 push key; the app opens the host's sealed title/body (§6.7) |
| Chat replies | Markdown: headings, bold / italic / strike, inline code and code blocks, lists and task lists, quotes, tables, links (tap to open). Long-press a message for reactions (👍 ❤️ 😂 🎉 👀 ✅), **Reply in thread** and **Copy text** |
| Files | **+** in the composer: Photos (system photo picker, up to 10), Files (any document), Paste an image. Chips show thumbnails before sending; files upload in chunks (`upload`), then `send` carries `attachments`. Images show inline (tap for full screen), other files as cards that open in another app. Files need the computer online (they are not queued in the mailbox); a failed send puts them back in the composer. See [file attachments](../features/file-attachments.md) |
| Groups and threads | Groups in the roster (stacked avatars), group chats with each bot's name and colour, @mentions go to the host as text. Thread summaries ("3 replies", unread) under messages; the thread view shows the root and its replies and sends with `threadId`. See [groups and threads](../features/groups-and-threads.md) |
| Create and edit bots | **+** in the roster: **New bot** (name, description, colour, agent from the computer's installed agents, folder: personal workspace or browse the computer's folders with `listDirs`, approvals ask / automatic) and **New group** (name, members). Tap a chat's header to edit; pin, delete (with confirm). Phones whose role can't act don't see **+** |
| Built-in agent | **Hypurr Agent**: Free badge, free gateway model picker, roster install card with consent (`installBuiltinAgent`). See [built-in agent](../features/builtin-agent.md) |
| Remote screen | The screen icon in the roster (when Remote screen is on in the computer's menu): WebRTC viewer (`screenPrepare` → non-trickle `screenOffer` → answer, receive-only video, `input` data channels), tap = click, double tap, long-press = right click, drag = scroll, keyboard panel with keys, the computer's clipboard. Reconnects and renews the session before it expires. **Not yet tested on a real device**: see [remote screen](../features/remote-screen.md) |

Not on Android yet: voice calls, account sign-in / SAS approval (pair with the QR instead), Live
Activity–style ongoing notifications. Editing a bot can't change its folder, connectors or skills
(the built-in agent's free model can be changed). Groups don't take files (a host rule). Remote
screen has no modifier bar, display switching or takeover toggle yet.

## Build and test

Needs JDK 17+ and the Android SDK (`platforms;android-35`, `build-tools;35.0.0`). Point Gradle at the SDK
with `ANDROID_HOME` or `apps/android/local.properties` (`sdk.dir=…`).

```bash
cd apps/android
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest             # crypto vectors, pairing, transport, store, push, screenshots
./gradlew recordRoborazziDebug          # rewrite screenshots/android-*.png (Roborazzi + Robolectric)
scripts/e2e-local-host.sh               # pair and chat with a throwaway local hypurr-host
```

The unit tests talk to an in-memory host that speaks the real protocol (`FakeHost`).
`scripts/e2e-local-host.sh` starts a real `hypurr-host serve` with its own `HOME`, creates a bot,
issues a pairing link and runs `LocalHostE2ETest` over OkHttp WebSockets (pair → `4100` → reconnect →
`hello`, `sync`, `registerDevice`, `send`, events, `history`). To try the app on an emulator, run the
script with `KEEP_HOST=1`, install the APK and open the printed link:
`adb shell am start -a android.intent.action.VIEW -d '<hypurr://pair…>'`.

CI (`.github/workflows/android.yml`) runs `assembleDebug` and the unit tests and uploads the debug APK
as the `hypurr-debug-apk` artifact. Nothing is published.

## Push notifications (FCM)

The repository ships a **placeholder** `app/google-services.json` (project `hypurr-placeholder`). With
it the app builds and runs, but it never asks Firebase for a token and Settings says push isn't
configured. To turn push on:

1. Create a Firebase project, add the Android app `com.ragul84.hypurr`, and replace
   `apps/android/app/google-services.json` with the downloaded file.
2. Give `relay/` an FCM sender: create a service account with the *Firebase Cloud Messaging API Admin*
   role and set its fields as Worker secrets (see [relay/README.md](../../relay/README.md)):
   `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`.
3. Deploy the relay and set `RELAY_URL` in `app/build.gradle.kts` (it matches `SharedStore.relayURL` on
   iOS, a placeholder today).

Flow: the app gets its FCM registration token, `POST {relay}/register {token, env: "fcm", kind: "alert"}`
returns an AES-GCM ticket, and the app calls `registerDevice {ticket, relay, name, pushKey, ctx: "local"}`
on the host. The host is unchanged: it posts the same `push-batch` it sends for iPhones; the relay sees
the `fcm` ticket and sends an FCM HTTP v1 **data** message (`title`/`body` generic, `sealed`, `botId`,
`computerId`, `threadId`, `category`). `HypurrMessagingService` opens `sealed` with the phone's push key
and shows the real title and body. Pushes are held while any phone app (iOS or Android) is connected.

## Keys and storage

The device's Ed25519 signing key and X25519 push key are random 32-byte keys wrapped with an AES-GCM key
in the Android Keystore and kept in private preferences that are excluded from backups. The paired
computer (`Computer`: id, pinned sign key, box key, direct URLs, cloud URL) and settings live in
`SharedPreferences`. Direct connections use cleartext `ws://` on purpose: every message on them is
end-to-end encrypted and authenticated by the channel.
