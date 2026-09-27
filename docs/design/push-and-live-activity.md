# Notifications, Live Activities and Dynamic Island

Updated: 2026-09-27. This describes the implemented notification contract and its delivery limits. Deployment and real-device acceptance are separate from local tests.

## Experience

| Event | Notification | Live Activity / Dynamic Island |
| --- | --- | --- |
| A task is sent from this iPhone | No alert | Start one activity for this bot |
| Working | No alert for each tool call | Bot identity and working orb |
| Input or permission needed | Bot name, “Response needed”, request summary; “Review request” action | Needs you; keep the activity alive |
| Completed | Bot name, “Task complete”, final reply preview; “Open conversation” action | Done, then dismiss after 60 seconds |
| Failed | Bot name, “Task failed”, failure summary; “Open conversation” action | Error state, then dismiss after 60 seconds |
| No fresh update for 15 minutes | No synthetic failure alert | Update delayed; never infer completion |

The encrypted title is limited to 80 characters and the body to 400 characters. The body is an excerpt of the agent's final answer, not a newly generated summary. System notification previews, text truncation, Focus, sounds and delivery timing remain controlled by iOS. There is no invented completion percentage or per-tool notification stream.

Notification actions open the scoped conversation. Permission approval still requires the normal in-app permission card. An account mismatch does not navigate into another account. Notifications are grouped by computer plus bot. Muted and hidden bots do not alert. The existing host-wide suppression while an iOS event connection is open remains in place; independent foreground suppression for multiple phones is not implemented.

Use Settings → Notifications to request access or open the iOS notification settings, including when access is already enabled. Per-bot notification preferences remain in the bot editor. State → Live Activity controls automatic activity creation and shows system authorization. Turning it off ends this phone's activities.

## Responsibilities and privacy

```mermaid
flowchart LR
    H[Host: bot events and private summary] -->|sealed alert or status-only activity| W[relay/ APNs Worker]
    W --> A[Apple APNs]
    A --> N[iOS Notification Service Extension]
    N -->|decrypt locally| B[Notification banner]
    A --> K[ActivityKit]
    K --> L[Lock Screen and Dynamic Island]
```

- `host/` owns event meaning, device tickets, task state and notification encryption.
- `relay/` holds the APNs signing key, opens opaque tickets and selects the APNs environment/topic. It has no notification decryption key.
- `cloud/` handles accounts and the encrypted connection. It is a separate service from the APNs Worker; no new Durable Object or D1 table is needed for this change.
- The notification extension decrypts the title, optional subtitle and body using the account context's shared Keychain key. This is local work and does not require a network fetch. [Apple notification content modification](https://developer.apple.com/documentation/usernotifications/modifying-content-in-newly-delivered-notifications)
- ActivityKit renders both Lock Screen and Dynamic Island. A separate Dynamic Island Worker or event API is unnecessary. APNs updates use a dedicated activity token, not the normal notification token. [Apple ActivityKit push updates](https://developer.apple.com/documentation/ActivityKit/starting-and-updating-live-activities-with-activitykit-push-notifications)

Remote activity content contains only `status`, empty `activity`, and `startedAt`. Foreground app updates may show the current step from the encrypted connection; a remote update clears that free text. The notification service extension is not an ActivityKit decryption stage. The UI therefore does not promise private task summaries in background Live Activities. Bot identity and its deep link are set locally when starting the activity.

## Registration and lifecycle

1. iOS registers its APNs token with `POST /register` using `kind: alert` and its build environment.
2. The Worker returns an AES-GCM ticket. The phone sends it with its X25519 push public key and account context to `registerDevice` on the host.
3. Registration atomically replaces previous alert tickets for that device identity. Randomized tickets must not accumulate, retain obsolete keys or generate duplicate notifications.
4. A foreground task submission starts an ActivityKit activity with `pushType: .token`. The phone registers each activity token using `kind: liveactivity`, then calls `registerActivity` for the bot.
5. Activity registration replaces the previous ticket for this device and bot. The app retries registration up to three times and reattaches token observers after reconnect/relaunch. Cancellation stops retries.
6. The host sends current state after registration, including terminal state if the task already finished. An idle bot with an unanswered main-chat user message waits for its actor to start rather than reporting immediate completion.
7. Status transitions send updates; idle/error sends an end event preserving that status and removes the activity tickets. The group view follows its member runtime using the existing group state logic.
8. App-local updates set a 15-minute stale date. Remote updates now do the same. A long task with no status transition may become stale; this is an honest “Update delayed”, not an assertion that its process stopped.

An existing activity can receive background APNs updates. Automatically starting activities for tasks initiated elsewhere is not enabled: that requires a separate push-to-start token flow and product preferences. Home Screen widgets keep their existing WidgetKit refresh scheduling and do not become real-time feeds through this change.

## Worker contract

Alert kinds are `done`, `needsInput`, and `failed`. The public APNs alert always has title `Codync` and a useful fallback sentence. The encrypted payload carries the private fields. `mutable-content: 1` requests extension processing. The public routing fields are `botId`, `computerId`, and `ctx`; `thread-id` is `<computerId>:<botId>`.

Live Activity requests carry:

```json
{
  "ticket": "<opaque activity ticket>",
  "liveActivity": {
    "event": "update",
    "timestamp": 1790500000,
    "staleDate": 1790500900,
    "contentState": {
      "status": "needsInput",
      "activity": "",
      "startedAt": 812192700
    }
  }
}
```

`timestamp`, `stale-date`, and `dismissal-date` use Unix seconds. `startedAt` uses Swift Date's 2001 reference epoch. The Worker preserves the host event timestamp rather than replacing it with delivery time. Older hosts that omit it use Worker receipt time.

- Topic: `com.pokai.Codync.ios.push-type.liveactivity`; push type: `liveactivity`.
- Working updates use APNs priority 5; input requests and end events use 10.
- Updates set `stale-date`; ends set `dismissal-date` to event time plus 60 seconds. Expiration follows that deadline. Ordinary alerts expire after one hour.
- Invalid environment, kind, activity event, state or dates return 400. Remote activity free text is rejected. Alert custom data cannot overwrite `aps`; alert payloads larger than 4096 bytes return 413.
- Unregistered/bad device tokens return 410. Provider signing-token errors remain delivery errors and do not invalidate a device ticket.

Host delivery is currently best effort with a 10-second request timeout. There is no durable delivery queue or delivery receipt from iOS; a successful APNs response does not prove display. Second-resolution timestamps also do not provide a unique run identifier. Durable retries and activity identities per task would need a follow-up protocol change; this implementation does not claim exactly-once or guaranteed ordered delivery.

## Why only “Done” appeared

The previous public fallback was literally `Codync / Done`. Any missing `sealed`, `ctx`, inaccessible shared key, failed authentication or extension packaging problem kept that fallback. Source inspection confirms that path; identifying which branch ran on a specific installed phone needs device logs.

Registration previously accumulated random tickets, including tickets with obsolete/missing push keys. Re-registering now removes those old records for the same device. The fallback itself now says “Your task is complete. Open Codync to read the result.” Failure and input requests have distinct fallback sentences.

For a phone that still shows fallback text:

1. Open the updated app and connect to the updated host so token/key registration completes.
2. Verify the installed app embeds `CodyncNotificationService.appex`, and the service principal class is the generated module's `NotificationService`.
3. Verify signed app and extension share the same expanded `CodyncKeychainGroup` and Keychain entitlement. Source plist equality alone does not verify a signed installation.
4. Inspect device logs for categories `Push`, `PushDecrypt`, and `NotificationService`. They distinguish missing keys, Keychain failures, failed content authentication/decoding and fallback delivery without printing keys or message contents.
5. Test after the first unlock following a reboot. Keys intentionally use `AfterFirstUnlockThisDeviceOnly`; pre-unlock fallback is expected.

## Configuration and release checks

The APNs Worker uses the existing secrets `APNS_TEAM_ID`, `APNS_KEY_ID`, `APNS_SIGNING_KEY`, and `TICKET_KEY`; see the [relay deployment guide](../../relay/README.md). Keep `TICKET_KEY` stable across deployments. Rotating it invalidates every existing ticket. Tickets are bearer capabilities accepted by the Worker and must not be logged.

The app bundle is `com.pokai.Codync.ios`. App and notification extension share `group.com.pokai.Codync` and the expanded shared Keychain access group. `project.yml` embeds both extension targets and enables `NSSupportsLiveActivities`; no new entitlement is required by these changes. Confirm sandbox versus production against the signed provisioning profile, especially for distribution builds.

Deployment order: Worker → host → iOS. The optional encrypted subtitle remains compatible with clients that decode only title/body. New clients accept older encrypted payloads without subtitles. The new `failed` category requires the updated app for its custom action. Follow the repository's stop-old-process/relaunch rules when installing builds.

### Acceptance matrix

| Check | Expected result |
| --- | --- |
| Re-register twice with a new key | One alert ticket for that device, latest key retained |
| Rotate an activity token | One ticket for that device and bot; other bots/devices unaffected |
| Task succeeds in background | Decrypted bot title, status subtitle, result excerpt; activity ends |
| Task fails in background | Failure alert and error activity; no success checkmark |
| Permission requested | Review action opens scoped conversation; activity stays live |
| Stop receiving state updates | Stale UI hides the old foreground step |
| Reopen app with an active activity | Token observer resumes and registration retries on transient failure |
| Wrong key / signed extension missing access | Readable generic fallback and a diagnostic log |
| Provider credentials expire | Device ticket is retained for credential repair |
| Worker receives private activity text / oversized alert | Rejected before APNs |

Local tests cover payload generation, error classification, request validation, ticket replacement, Swift payload decoding and crypto vectors. A signed physical-device run with the deployed Worker is still required for extension execution, locked-device Keychain access, action navigation, Focus behavior, token rotation and background ActivityKit delivery.

### Local verification — 2026-09-27

- Host: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, and all 147 unit/integration tests passed.
- Swift: `swift test --package-path kit` passed, 51 tests including encrypted push vectors and the optional notification subtitle.
- Relay: `npm test` and `npm run typecheck` passed, including malformed requests and activity payload checks.
- iOS: Debug simulator build passed with both extensions embedded. The built notification extension has the expected service entry point. Signing and device Keychain access were not verified by this unsigned build.
- Design document relative links and `git diff --check` passed. No Worker deployment or phone installation was performed.
