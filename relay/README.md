# Hypurr relay

Cloudflare Worker that holds the APNs key (and, for Android, an FCM service account) and forwards pushes for hypurr-host.
The phone exchanges its APNs token, or its FCM token with `env: "fcm"`, for an AES-GCM **ticket** (`POST /register`);
hosts only ever see tickets (`POST /push`). A ticket is a bearer capability: anyone holding it can ask this Worker to push
to its device. Only the Worker can decrypt the raw APNs token. Do not log tickets.

## Deploy

```bash
npm install
npx wrangler secret put APNS_TEAM_ID       # Apple Developer team ID
npx wrangler secret put APNS_KEY_ID        # an APNs Auth Key (Keys → Apple Push Notifications service),
                                           # not an App Store Connect API key — both are AuthKey_*.p8
npx wrangler secret put APNS_SIGNING_KEY   # contents of the .p8 file
openssl rand -base64 32 | npx wrangler secret put TICKET_KEY
# Android (optional): a Firebase service account with the "Firebase Cloud Messaging API Admin" role
npx wrangler secret put FCM_PROJECT_ID     # project_id from the service account JSON
npx wrangler secret put FCM_CLIENT_EMAIL   # client_email
npx wrangler secret put FCM_PRIVATE_KEY    # private_key (the PEM, \n escapes are fine)
npm run deploy
```

Without the FCM secrets the Worker keeps serving APNs; pushes to an FCM ticket answer `503`.
FCM messages are data-only: the Android app opens the host's sealed text itself
([Android guide](../docs/guides/android.md#push-notifications-fcm)).

It deploys as `hypurr-relay`. The iOS app points at `SharedStore.relayURL` in `HypurrKit`.
Rotating `TICKET_KEY` invalidates every ticket; phones re-register on launch.

## Test

```bash
npm test        # ticket seal/open round trip, `mutableContent` → `mutable-content: 1`, FCM register/JWT/send
npm run typecheck
```

## Notifications and Live Activities

See the [notification design](../docs/design/push-and-live-activity.md) for event
copy, encrypted content, ActivityKit payloads, configuration and device acceptance.
The Worker handles alerts and updates/ends for activities started on the phone.
Dynamic Island shares the ActivityKit update stream. This Worker does not start
activities remotely or refresh Home Screen widgets.
