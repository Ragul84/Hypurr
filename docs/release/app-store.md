# iPhone App Store: preparation (not enrolled)

Status: **prepared only.** Nothing has been enrolled, signed, uploaded or submitted.

## Cost

Publishing on the App Store needs the **Apple Developer Program: US$99 per year**
(about ₹8,300 at `usdToInr` 84; Apple charges in local currency). There is no free tier
for App Store distribution. A free Apple ID can only sideload to your own iPhone for
7 days from Xcode. Hypurr's budget today is ₹0, so **do not enroll** until the owner
approves the fee. Enrollment as an organisation also needs a D-U-N-S number (free).

## What already exists in the repo

| Piece | Where |
|---|---|
| App target, bundle id `com.ragul84.Hypurr.ios` | `apps/project.yml` (XcodeGen) |
| Live Activity widget `…ios.LiveActivity`, notification extension `…ios.NotificationService` | `apps/project.yml` |
| Info.plist, entitlements, privacy manifest | `apps/ios/Resources/` (`PrivacyInfo.xcprivacy`) |
| Version | `MARKETING_VERSION` in `apps/project.yml` |
| Export options for App Store Connect | `apps/ios/ExportOptions.plist` (new; set `teamID`) |
| Listing text | [app-store/listing.md](app-store/listing.md) (new) |

## Build (after enrolling)

```bash
cd apps && xcodegen generate
# set DEVELOPMENT_TEAM in project.yml (it is "" today) or pass it on the command line
xcodebuild -project Hypurr.xcodeproj -scheme iOS -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/Hypurr.xcarchive \
  DEVELOPMENT_TEAM=<TEAM_ID> archive
xcodebuild -exportArchive -archivePath build/Hypurr.xcarchive \
  -exportOptionsPlist ios/ExportOptions.plist -exportPath build/ipa
xcrun altool --upload-app -f build/ipa/Hypurr.ipa -t ios --apiKey <KEY_ID> --apiIssuer <ISSUER>
```

Needs a Mac with Xcode 16+. The App Store Connect API key (`.p8`) is a secret: keep it
outside the repo (for example `~/.secrets/`), never in CI logs.

## Checklist

**Account (owner decision, costs money)**
- [ ] Approve the US$99/yr fee, then enroll at developer.apple.com (individual or organisation)
- [ ] Sign the Paid Apps agreement only if selling credits on iOS (see In-app purchases)
- [ ] Create an App Store Connect API key for uploads

**Identifiers and signing**
- [ ] Register App IDs: `com.ragul84.Hypurr.ios`, `.ios.LiveActivity`, `.ios.NotificationService`
- [ ] Capabilities: Push Notifications, Sign in with Apple, App Groups, Keychain sharing, Communication Notifications (match `iOS.entitlements`)
- [ ] APNs key (`.p8`) for the push relay (`relay/`), see [PUSH-SETUP.md](../PUSH-SETUP.md)
- [ ] Set `DEVELOPMENT_TEAM` in `apps/project.yml` and `teamID` in `ExportOptions.plist`

**App Store Connect record**
- [ ] New app: name "Hypurr", primary language English (India), SKU `hypurr-ios`
- [ ] Category: Developer Tools (secondary: Productivity)
- [ ] Price: Free
- [ ] Listing text, keywords, URLs from [listing.md](app-store/listing.md)
- [ ] Screenshots: 6.9" (1320×2868) and 6.5" (1284×2778) iPhone, at least 3 each
- [ ] App privacy ("nutrition label"): see listing.md; must match `PrivacyInfo.xcprivacy`
- [ ] Age rating questionnaire (expected 4+; no user-generated public content)
- [ ] Export compliance: uses standard encryption (TLS, end-to-end channel) → exempt under
      the mass-market exemption; set `ITSAppUsesNonExemptEncryption` = NO in Info.plist
      once confirmed

**Review notes (Guideline 2.1 and 4.2)**
- [ ] The app needs a computer running `hypurr-host`. Give reviewers a demo: a cloud VM with
      the host, a pairing code, and a short video. Without it review usually rejects for
      "app can't be tested".
- [ ] Sign in with Apple must be offered because other sign-in options exist (4.8): already wired.
- [ ] Account deletion inside the app (5.1.1(v)) must work before submission.

**In-app purchases (Guideline 3.1.1)**
- [ ] Hypurr credits are digital goods: on iOS they **must** use Apple In-App Purchase
      (RevenueCat supports it; add an iOS offering next to the Android one in
      `pricing/plans.json`). No links to buying elsewhere outside allowed regions.
- [ ] Until IAP is set up, ship the iOS app with credits hidden (free own-account and own keys only).

**TestFlight first**
- [ ] Internal TestFlight build, test pairing, push, Live Activity, remote screen, voice call
- [ ] Then submit for review
