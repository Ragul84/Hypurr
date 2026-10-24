# Push notifications: setup

Your phone buzzes when an agent **needs you** (an approval), when a task **finished**, or when it
**failed**, even with the app closed. All code is in place; alerts with the app closed stay off until
you create the Firebase pieces below. Without them everything builds and runs, and the app still
shows the same alerts itself **while it has a live connection** to your computer (in-app fallback,
no Firebase needed).

## How it flows

```
phone (FCM token) ──POST /register──▶ push.mindgains.ai  ──▶ opaque ticket
phone ──registerDevice(ticket, push key)──▶ your computer (over the encrypted channel)
agent needs you / done / failed
your computer ──POST /push-batch──▶ push.mindgains.ai ──FCM HTTP v1──▶ phone
```

- What Google/FCM and the relay see: only `Hypurr needs you on raavan`, `Task finished on raavan`
  or `Task failed on raavan` plus opaque ids. Never the task, the agent's words or code.
- A short line sealed to the phone's own key rides along (code blocks are replaced by "(code)");
  only that phone can open it.
- The computer doesn't push while the app is connected; the app raises the alert itself then.
- The relay (`relay/`, Worker `hypurr-relay`) is already live at `https://push.mindgains.ai` with
  per-IP rate limits. Until the Firebase secrets are set, its FCM sends answer `503` and nothing breaks.

## What you need to create (about 10 minutes)

### 1. Firebase project
1. Open <https://console.firebase.google.com> → **Create a project** (any name, e.g. `hypurr`;
   Google Analytics not needed). You can use your existing Google account; it's free (Spark plan).
2. In the project: **Project settings → Cloud Messaging** → make sure **Firebase Cloud Messaging API (V1)**
   shows *Enabled* (enable it if asked).

### 2. Android app → `google-services.json`
1. Project overview → **Add app → Android**.
2. **Android package name:** `com.ragul84.hypurr` (exactly). Nickname and SHA-1 are optional.
3. **Download `google-services.json`** and send it to me (or put it at
   `apps/android/app/google-services.json`). It is git-ignored; the build switches push on only when
   this file exists, so CI keeps building without it. For CI release builds store it as a secret
   and write it to that path before building.

### 3. Service account key (for the relay)
1. **Project settings → Service accounts → Generate new private key** → a JSON file downloads.
   It's a password: don't post it in chat; put it on my computer (e.g. paste it into a secure box I send).
2. From that JSON the relay needs three Worker secrets (I can do this with the Cloudflare token):
   ```
   cd relay
   npx wrangler secret put FCM_PROJECT_ID     # "project_id"
   npx wrangler secret put FCM_CLIENT_EMAIL   # "client_email"
   npx wrangler secret put FCM_PRIVATE_KEY    # "private_key" (whole PEM)
   ```
   No redeploy needed after `secret put`.

### 4. Build
Rebuild the APK with the `google-services.json` in place. The app already points at
`https://push.mindgains.ai` (override: `-Phypurr.relayUrl=…`), and the computer uses the URL the
phone registers with (default `https://push.mindgains.ai`; `HYPURR_RELAY_URL=off` disables pushes).

## Check it works
1. Install the new APK, allow notifications, open the app once (it registers), then swipe it away.
2. Start a task that needs approval ("delete the build folder"). You get "Hypurr needs you on <PC>".
3. Let a task finish with the app closed: "Task finished on <PC>".

Settings → Notifications says "even when the app is closed" once Firebase is configured.
