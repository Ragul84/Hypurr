# Routines

Routines belong to an agent bot. **Set up a routine** opens a form for the name,
instruction, trigger and timeout; Save creates it immediately. Edit opens the same
form and preserves existing triggers by default. **Ask in chat** is a separate
option that inserts an explanatory draft; send it to ask the bot to configure the
routine. Every agent receives the built-in `routines` MCP server. The Mac conversation details
panel and the iPhone's **More → Routines** show the saved instruction, triggers,
pause/resume, edit, delete, test run and execution history. Creation/update notices
open the routine.

## Apple setup interface

The editor keeps task fields first, followed by trigger choices and their settings.
Trigger choices use adaptive columns, selected checkmarks and short descriptions.
Inputs use the shared palette with a visible filled surface. New recurring schedules
default to cron; the frequency controls send form values to the host, which returns a five-field
expression and an explicit IANA time zone. Run timeout is under an animated Run settings
expansion. A fixed footer keeps Create routine / Save changes and inline errors
visible while the form scrolls; it explains the local execution requirement.
The layout uses the same light/dark tokens, type family and custom controls as the
rest of Codync, with Reduce Motion support.

## Conversational setup and tools

Every agent receives the `routines` MCP server automatically; no marketplace
installation is needed. Its tools are `list_routines`, `save_routine`,
`set_routine_enabled`, `delete_routine`, `run_routine` and `routine_webhook`.
The same setup guidance is included in newly rendered agent context and MCP
initialization instructions.

For chat setup, the bot should resolve the task, trigger and timezone from the
conversation, asking only about missing or ambiguous details. It should check the
task's required tools, sign-ins and files, list existing routines to avoid
accidental duplication, then save and report the returned configuration and next
occurrence. A saved webhook whose external sender is not connected must be
reported as awaiting connection. Testing runs the actual instruction, so creating
a schedule alone should not trigger an unsolicited test execution.

A deterministic ACP fixture exercises the actual advertised MCP subprocess:
initialize, discover all six tools, reject an invalid schedule, create, read,
edit, pause/resume, fetch webhook credentials, delete, and execute a test run.
This verifies tool wiring and persisted behavior; it does not guarantee how every
provider model will interpret an ambiguous natural-language request.

## Triggers

A routine has an OR list of triggers:

- `interval`: `seconds` (1 second through 365 days).
- `once`: `at` (Unix milliseconds, in the future when created).
- `cron`: a five-field `expression` and IANA `timeZone`, including DST.
- `webhook`: every authenticated JSON delivery runs the routine.
- `event`: `source`, `event` (`*` matches all), and optional exact-match `filters`.
  `messageContains` matches a substring of `text`. Source names are `slack`,
  `github`, `origin`, `microsoftTeams`, `linear`, `sentry`, `pagerduty`, and `email`.

For example, Sunday at 14:20 in Taipei is `20 14 * * 0` with `Asia/Taipei`.
The host computes and displays the next occurrence. Calendar parsing uses
[Croner](https://docs.rs/croner/3.0.1/croner/), with chrono-tz for named zones.

## Execution

The host checks deadlines every second, even with no client connected. Definitions,
next deadlines and run records are saved in SQLite (`routines.v1`). Due triggers
are coalesced into one run per routine; only one routine per bot is dispatched at a
time. Bot actors serialize routine work with ordinary conversation work.

Each run gets its own conversation thread and agent session. Final results appear
in the main conversation and use normal encrypted push delivery. Returning `(pass)`
or no text completes silently. A test run can run a paused routine. Pausing or
deleting cancels pending runs; work already running can finish. Deleting a bot
cancels its pending work. Missed intervals coalesce rather than replaying a backlog.

Pending runs survive a restart. The actor claims a run only when it reaches the
front of its queue, using the latest saved instruction. Runs interrupted during
execution become `recovering` and load their existing agent session. If the
provider cannot load it, they become `interrupted` instead of replaying the task
in a new session. Session recovery cannot guarantee exactly-once external side
effects; inspect the transcript before retrying an interrupted action.

Each run has a timeout (default one hour, configurable from 1 to 86400 seconds).
A hung agent is terminated so subsequent work can proceed. Completed results are
persisted before publication, with stable transcript IDs to avoid duplicate
results after restart. Failed publication retries without rerunning the agent.
The latest 1000 terminal runs are retained globally, plus active/unpublished runs;
the panel returns the latest 200 runs per bot. Deduplication lasts while the
corresponding run record is retained.

Intervals retain their original cadence; missed deadlines coalesce into one
pending wake, including when a prior run is still active. Enabled time schedules
keep the computer from idle sleep. Explicit sleep, closing the lid and powering
off still suspend local execution. The host uses an exclusive data-directory lock
to prevent two daemons from firing the same schedules. Shutdown stops scheduling
before stopping actors.

## Webhook delivery

`routineWebhook` returns a per-routine URL and secret. The ordinary listing never
returns the secret. Send JSON to `/hooks/routines/<id>` using
`Authorization: Bearer <key>`. `X-Delivery-Id` deduplicates retries against retained
run records. Bodies are limited to 64 KB. The routine must be enabled. Event
routines additionally require a matching normalized payload, for example:

```json
{"source":"github","event":"pull_request.opened","repo":"owner/repo","text":"A PR opened"}
```

The displayed URL uses host loopback. An external sender needs a deliberately
configured forwarding service and must normalize its provider's event into this
format. This implementation does **not** provision SaaS subscriptions, use Grok
Bot's private cloud, register provider webhooks, or provision an inbound email
address. Do not describe an event routine as connected before verifying delivery.
The local endpoint is not automatically published through Codync's E2E relay.

## API

All normal methods use the existing authenticated host API / authorized E2E channel:

| Method | Body |
|---|---|
| `routines` | `botId` |
| `routineSchedule` | `triggers` + preferred `timeZone` to load, or `draft` to preview; returns hydrated form, validated triggers, summary, warning, next run |
| `saveRoutine` | `botId`, optional `id`, `name`, `instruction`, `schedule` form or `triggers`, optional `enabled`, `timeoutSeconds` |
| `setRoutineEnabled` | `botId`, `id`, `enabled` |
| `deleteRoutine` | `botId`, `id` |
| `runRoutine` | `botId`, `id` |
| `routineWebhook` | `botId`, `id` |

The local-only `routineCall` backs the MCP tools. Each MCP instance is scoped to
its bot. Phone and Mac panels refresh while visible; definitions do not currently
participate in offline client caches. Run transcript entries use normal rev/SSE.

## Reference and parity

Observed on the installed Grok Bot on 2026-09-26: a routine list in conversation
details, instruction and trigger details, pause/resume, delete, and chat-based
editing. Creation produces a linked transcript card. The shipped renderer also
contains test-run and webhook credential controls, interval/one-shot/cron schedule
formatting and multiple provider trigger types.

The local reconstruction at
`/Users/libokai/mycode/nimplex/sandbox/grok-bot-architecture-2026-09-15` contains a
partial automation fire consumer with event matching, durable wake delivery and
completion reporting. Most automation modules it imports are absent. Its renderer
and private cloud are not source-complete references.

Remaining differences from the reference: provider subscription provisioning,
cloud execution/delivery while the host is offline, inactivity auto-pause, and a Linux routine panel (the TUI edits routines with a typed schedule: cron, `every 2h`, a date, or `webhook`). The Apple UI
uses Codync's shared native controls. This is not a verified complete one-to-one
reconstruction of every Grok Bot routine behavior.

## Validation

`host/tests/routines_e2e.rs` starts isolated hosts and deterministic ACP agents,
checks real scheduled execution, webhook credentials, delivery deduplication,
pause/delete/edit while queued, crash recovery in the same session, unsupported
session recovery, hung-agent timeout, recurring work after failure, single-host
ownership, silent completion, and the advertised routine MCP server. Unit tests
cover timezone calculation, invalid schedules, event filters, persistence,
pause/resume and bot ownership. Swift tests cover the wire model and transcript links.

## Public webhook ingress without a fixed IP

Research checked on 2026-09-26:

- [OpenClaw Gmail webhook setup](https://docs.openclaw.ai/cli/webhooks)
  supports Tailscale Funnel for a public push endpoint. Serve is private to the
  tailnet; it is not a replacement for a public URL used by an arbitrary SaaS.
- [Hermes webhook adapter](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/webhooks.md)
  provides an HTTP listener, route secrets/signature validation, filtering and
  event-triggered cron jobs. Its documented setup still requires a reachable
  server URL; enabling the adapter alone does not solve NAT traversal.

For Codync, a fixed IP on the user's computer is unnecessary. Two possible
approaches are a configured outbound tunnel, or a Codync cloud ingress with a
persistent event queue and delivery over the host's outbound connection. The
latter fits offline delivery but is **proposed, not implemented** here.

The existing relay accepts authorized devices' encrypted frames. A third-party
HTTP sender cannot produce those frames or act as a paired device. Public ingress
therefore needs its own per-routine authorization, provider signature checking,
rate/body limits, delivery IDs, expiry, acknowledgements and revocation. Cloud
HTTPS termination sees the incoming provider payload; it must not be presented as
the same end-to-end encryption boundary as device messages. Local execution waits
until the host reconnects and is awake.

## Usability comparison (2026-09-27)

The installed Grok Bot was inspected through Computer Use. Its sidebar emphasizes
the routine name and a human-readable schedule (for example, Every Sunday at
2:20 PM). Details expose Instruction, When to run, Pause, Edit and Delete. The
conversation contains a linked creation notice; an existing edit draft refers to
the routine by name. No reference routine was run, paused or deleted during this
comparison.

Codync retains that compact sidebar and the chat-based setup path, while providing
a direct editor. Calendar schedules offer minute frequencies, hourly, daily, weekdays, weekly,
selected weekdays, monthly and custom cron. The clock uses separate hour/minute
controls with every minute available and quick 09:00 / 12:00 / 18:00 choices.
Selected weekdays support multiple days. The summary shows the clock time and
zone, with the generated expression available on expansion. Switching to Custom
starts from that expression. Monthly days 29–31 explain that shorter months are
skipped. Existing duration intervals keep their cadence until explicitly replaced. Editing hydrates the original schedule, timezone and interval exactly;
compound and provider-event triggers retain a lossless Keep existing triggers
fallback. Expired one-shot timestamps are preserved when editing other fields.

Saved definitions appear immediately. Paused schedules omit next-run deadlines.
List/details expose queued/running/recovery/failure states and empty run history.
Edit with bot includes the routine ID in a draft without sending it. Webhook
credentials have explicit copy controls, and the panel distinguishes testing the
task from verifying an external delivery. These changes do not configure public
ingress or claim a provider connection is working.

Verification: shared Swift tests passed (46 tests; an unrelated offline-mailbox
assertion failed on the first run, then passed both the targeted and full rerun).
macOS Debug and iOS Simulator builds passed. On the installed Mac build, an
isolated fixture bot was used to create a Sunday 09:00 Asia/Taipei routine, reopen
its populated editor, change it to weekdays, and pause it. API inspection
confirmed one updated routine with `0 9 * * 1-5` and `enabled: false`. Edit with
bot populated an unsent draft with the routine ID. The test bot and working
directory were removed. Screenshots are in `build/routine-usability-calendar.jpeg`
and `build/routine-usability-details.jpeg`.

## Cron scheduling contract

All newly configured recurring schedules in the Apple editor use `cron` and run
through the host's existing Croner scheduler. Minute presets divide an hour evenly
(1, 5, 10, 15, 20, 30); hourly schedules choose an exact minute. Cron generation, parsing, validation, timezone checks, descriptions and next-run
calculation all live in `host/src/routines/editor.rs` and `schedule.rs`. The Swift
draft is only a Codable form DTO. The editor debounces preview requests, discards
stale responses, shows host errors and disables Save until the current draft is
validated. Save sends the form to the host, which independently compiles and
validates it again. Both direct trigger API calls and Bot MCP calls use the same
host scheduler. Run timeout text is also parsed and validated by the host.
The built-in agent instructions also select cron for recurring clock schedules.

Existing elapsed-duration intervals remain readable/editable without silently
changing their execution times. An interval anchored to creation time cannot be
converted to a wall-clock cron without changing its cadence. One-time schedules
retain their absolute timestamp: five-field cron has no year or run-count guard.
Webhook/event triggers remain event-driven. These paths are intentionally not
misrepresented as cron. The host must still be running and awake.

Rust regression tests cover default cron creation, preset round trips, arbitrary
minute selection, timezone validation, custom expression preservation, existing
interval and expired one-shot preservation, and next occurrences across month
boundaries. An isolated HTTP integration test previews, saves and reloads the same
form and verifies direct saves reject invalid drafts. Swift tests cover wire DTO
decoding and millisecond date binding; there is no Swift cron implementation.


### macOS selection checks

The calendar editor uses shared choice menus. Nested menus become the active
accessibility surface above the editor; Escape dismisses the top overlay through
its own close binding. This keeps menu interaction separate from closing the
routine editor. Schedule rules remain on the host; these are presentation rules.


Verified on the installed Mac build through Computer Use on 2026-09-27:

- Opened a saved Monday/Wednesday/Friday schedule at 14:47 in Asia/Taipei.
- Switched to Custom and observed the host-generated `47 14 * * 1,3,5`.
- Entered minute 61; the server error appeared and Save was disabled.
- Selected minute 23 through the scrollable menu, saved, and reopened. Both the
  UI and API retained `23 14 * * 1,3,5` and the paused state.
- Selected monthly day 31; the warning and October 31 next occurrence appeared.
- Selected every 15 minutes and hourly at minute 23; both displayed host previews.
- Verified Escape closes the top menu and preserves the editor and form values.
- Verified the minute menu opens scrolled to the current selection. Its selection
  stays visible instead of starting at 00.

The temporary bot and paused routine were removed after checking. Screenshots:
`build/cron-verification/calendar-fields.png` and `minute-selection.png`.
Validation: 52 Swift tests, 12 Rust routine unit tests, the isolated HTTP editor
integration test, Rust Clippy with warnings denied, and signed macOS/iOS Debug
builds passed. iPhone received the updated build; its final relaunch was blocked
by the device lock. This GUI acceptance used macOS.
