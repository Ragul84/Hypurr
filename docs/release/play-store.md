# Google Play release

## Build
- Upload key: generated once, kept out of git. Locally: `set -a; . /path/hypurr-upload.env; set +a; ./gradlew bundleRelease`
  (env `HYPURR_UPLOAD_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`, or a git-ignored `apps/android/keystore.properties`
  with `storeFile/storePassword/keyAlias/keyPassword`). No key → unsigned release build.
- CI: `.github/workflows/android-release.yml` (manual run or `v*` tag). Repo secrets:
  `ANDROID_UPLOAD_KEYSTORE_BASE64` (`base64 -w0 hypurr-upload.jks`), `ANDROID_UPLOAD_STORE_PASSWORD`,
  `ANDROID_UPLOAD_KEY_ALIAS` (`hypurr-upload`), `ANDROID_UPLOAD_KEY_PASSWORD`, optional `REVENUECAT_ANDROID_KEY`.
- Enrol in **Play App Signing** (default for new apps): Google holds the app signing key, this is only the upload key
  and can be reset through Play support if lost.
- targetSdk 36 (required for new apps/updates since 31 Aug 2026). versionCode must increase on every upload.

## Listing assets — `metadata/android/en-US/`
title, short/full description, `images/icon.png` (512), `images/featureGraphic.png` (1024×500),
`images/phoneScreenshots/` (from the Roborazzi screenshot tests, padded to 9:16).
Privacy policy: https://hypurr-legal.mindgains.ai/privacy (source `web-legal/`, deploy `npx wrangler deploy` there).

## Data safety form (draft answers)
- Collects or shares user data? **Yes.**
- Encrypted in transit? **Yes.** Users can request deletion? **Yes** (email; link the privacy policy).
- Data types:
  - **App activity → Other user-generated content** (messages to Hypurr Agent with Hypurr Free/credits): collected,
    **shared** with AI model providers to provide the feature; not ephemeral on the providers' side; purpose: App functionality; optional.
  - **Device or other IDs** (hashed PC/phone ID, RevenueCat app user ID, FCM token): collected, not shared;
    purposes: App functionality, Fraud prevention; required.
  - **Financial info → Purchase history** (credit purchases via Google Play/RevenueCat): collected, not shared
    (RevenueCat is a service provider); purpose: App functionality.
  - Not collected: location, contacts, photos (camera only scans the QR, nothing stored), personal info, health, messages, files.
- Ads: **No ads.** Target audience: 18+. Content rating questionnaire: utility/productivity, no UGC sharing between users.
- Permissions to justify: CAMERA (QR pairing), POST_NOTIFICATIONS.
- Financial features declaration: none (in-app purchases only).

## Account steps (owner)
1. Play Console developer account: **US$25 one-time**, government ID + card under the legal name, Android-device verification in the Play Console app.
   Personal accounts made after 13 Nov 2023 must run a **closed test with ≥12 testers opted in for 14 continuous days**
   before applying for production (internal testing doesn't count). An organization account (needs a D-U-N-S number for Mindgains) skips that rule.
2. Create the app `com.ragul84.hypurr`, upload the first AAB by hand, fill store listing, data safety, content rating, app access
   (reviewer needs a paired PC: provide a demo video/instructions).
