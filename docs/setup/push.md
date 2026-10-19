# Push notifications: what you need to provide

Hypurr sends a phone notification when an agent **needs you** (an approval card) and when a task is
**done** or **failed**. The code is wired end to end; it stays off until you supply the three things
below. Without them the app builds and runs normally, and Settings says push isn't set up.

How it flows: the phone gets an FCM token → `POST {relay}/register {token, env:"fcm"}` returns an
opaque ticket → the phone gives the ticket to your computer (`registerDevice`) → when a bot asks for
approval or finishes, the host (`host/src/remote/push.rs`) posts a sealed alert to the relay → the
relay (`relay/src/fcm.ts`) sends an FCM data message → `HypurrMessagingService` opens the sealed text
with the phone's own key and shows it. Firebase and the relay only ever see a generic line, never your
task text. Nothing is sent while the app is open.

## 1. A Firebase project

1. <https://console.firebase.google.com> → **Add project** (Analytics not needed).
2. **Add app → Android**, package name `com.ragul84.hypurr`.
3. Project settings → Cloud Messaging: make sure **Firebase Cloud Messaging API (V1)** is enabled.

## 2. `google-services.json` (the app side)

Download it from the Android app's settings in Firebase and put it at:

```
apps/android/app/google-services.json
```

It's git-ignored on purpose (each deployment has its own). The build turns the google-services
plugin on only when this file exists, so CI and other contributors still build without it. For CI
release builds, store it as a secret and write it to that path before `./gradlew assembleRelease`.

## 3. A service account key (the relay side)

1. Firebase → Project settings → **Service accounts** → **Generate new private key**. You get a JSON
   file. Treat it like a password; don't commit it.
2. In `relay/`, set three Worker secrets from that JSON:

```
cd relay
npx wrangler secret put FCM_PROJECT_ID     # "project_id"
npx wrangler secret put FCM_CLIENT_EMAIL   # "client_email"
npx wrangler secret put FCM_PRIVATE_KEY    # "private_key" (the whole PEM)
npx wrangler deploy
```

## 4. Point the app and computer at your relay

- App: build with `-Phypurr.relayUrl=https://<your-relay>.workers.dev` (or edit the default in
  `apps/android/app/build.gradle.kts`).
- Computer: the phone sends the relay URL when it registers; to override, set
  `HYPURR_RELAY_URL` for `hypurr-host`.

## Check it works

Pair the phone, allow notifications, close the app, then ask a bot to do something that needs
approval (e.g. "delete the build folder"). You should get "<bot> needs you". Finish a task with the
app closed and you should get "Finished."

Nothing here is a real key: the repo holds no Firebase project, `google-services.json`, or service
account. You must create them.
