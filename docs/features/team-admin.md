# Team admin: roles, rules, activity and the audit log

For a team lead or manager whose people run agents through one Hypurr computer (or a shared
build machine). Everything is enforced on the host (`host/src/admin/`) in `api::dispatch`,
before a method runs, so every client and both transports (loopback API and the E2E channel)
get the same rules. Clients only show the data and call the methods below.

## Roles (kv `team`)

| Role | Can |
|---|---|
| `admin` | Everything: rules, roles, activity, audit log, work tools, connectors, skills, projects. |
| `member` | Run tasks and bots within the rules: start and finish tasks, send, approve (up to the admin level), go back to checkpoints. |
| `viewer` | Only look: chats, tasks, costs, rules. Can't send, start, approve or change anything. |

- A role belongs to a paired device (its key). **This computer is always an admin.**
- A device without its own role gets `defaultRole`. The default is `admin`, so one person
  with one phone keeps full control and nothing changes for existing users. Setting it to
  `member` turns the computer into a team: new phones can run tasks, and an admin promotes them.
- `hello` returns `you: {key, name, role}`. `team` lists everyone.
- Refusals are `403` with a plain sentence ("You're a member on this computer, so you can't
  change this. Ask an admin.") and leave a `blocked` audit record.
- Admin-only and viewer-allowed method lists: `ADMIN_ONLY` / `VIEWER_OK` in `admin/mod.rs`.

## Rules (kv `policies`, `setPolicies`)

| Field | Meaning | Enforced in |
|---|---|---|
| `allowedAgents` | Agent ids tasks and bots may use; empty = any installed agent | `startTask` (router only picks allowed agents; an explicit one must be allowed), `createBot`, `updateBot`, `taskSetup` |
| `dailyLimit` | USD per (UTC) day across all tasks; 0 = none | `startTask` and `send` to a task bot are refused once reached |
| `taskLimit` | USD per task; 0 = none | when a task's cost reaches it, the host stops its agent once, adds a notice ("Stopped: This task reached its spending limit ($1.00)…") and refuses further messages |
| `askFrom` | `low` / `medium` / `high` / `never`: requests at or above this always ask, even for bots set to approve automatically | the bot's permission handler; kept in step with the safety net's `alwaysAskHigh` |
| `adminApprovesFrom` | `off` / `low` / `medium` / `high`: requests at or above this need an admin | `respondPermission` from a member is refused; the card carries `data.needsAdmin` so clients can say so |

`setPolicies` also takes the safety net fields (`protectedBranches`, `blockProtected`,
`requireGit`), so one Rules screen edits protected branches too. `policies` returns
`{policies, safety, spentToday, agents}`. Costs come from the cost tracker
([tasks and the safety net](tasks-and-safety.md#cost-tracker)), so a limit is only as exact as
the agent's own reported cost (or the list-price estimate).

## Audit log (SQLite `audit`)

- Append-only: triggers refuse `UPDATE` and `DELETE` on the table. There is no API to edit or
  remove records.
- Each record `{seq, at, actor, actorName, role, action, target, detail, hash}` stores the
  SHA-256 of the previous record plus its own fields. `auditLog` re-checks the whole chain
  and returns `intact` / `brokenAt`, so a row edited or inserted outside Hypurr shows up.
- Recorded: task start / finish / rollback / checkpoint, approvals answered (with risk and
  answer), requests the safety net blocked and medium/high requests auto-approved (actor
  `hypurr`), spending-limit stops, bot create / update / delete / stop, rule, role and
  default-role changes, work tool changes (which tool only, never keys), projects, templates,
  devices, connectors, skills, and every request refused by a role or a rule (`blocked`).
- Chat messages themselves aren't copied into the log; they stay in the transcript.

## Activity view

`activity {days?}` (default 7) is built from the tasks table and the audit log:
`{people: [{key, name, role, tasks, spend, approvals, blocked, lastSeenAt}], tasks: [{title,
status, startedBy, projectName, backend, usage, pr, createdAt}], events, totals: {spend,
todaySpend, todayTasks, approvals, blocked}}`. Tasks record `startedBy` (set by the host from
the caller, never taken from the client).

## Methods

| Method | Who | Body | Returns |
|---|---|---|---|
| `team` | anyone | | `{you, defaultRole, people}` |
| `setRole` | admin | `key, role` (`null` = back to default) | `team` |
| `setTeam` | admin | `defaultRole` | `team` |
| `policies` | anyone | | `{policies, safety, spentToday, agents}` |
| `setPolicies` | admin | partial rules and safety fields | `policies` |
| `activity` | admin | `days?` | see above |
| `auditLog` | admin | `before?, limit?, since?` | `{entries, count, intact, brokenAt}` |

## Clients

| | Your role | Rules | People / roles | Activity | Audit log | "Needs an admin" on cards |
|---|---|---|---|---|---|---|
| Android | Settings › Team | edit (admins) / read (others) | edit | yes | yes | yes; members get "Waiting for an admin" with no buttons |
| iPhone / Mac | decoded (`Hello.you`) | not yet | not yet | not yet | not yet | yes (a note on the card; the host refuses a member's answer) |
| Linux (GTK) / Terminal UI | always admin (local) | not yet | not yet | not yet | not yet | not shown (local is an admin) |

Settings › Safety net toggles are read-only for non-admins, and Work tools are hidden from them.

## Later: single sign-on

Today each person pairs their own phone with the computer's QR code (or a signed-in account
approval), and roles attach to that device. SSO (SAML / OIDC with Google Workspace, Microsoft
Entra or Okta) needs the cloud account service to carry an organisation and its identity
provider, map users to roles, and issue device grants per user; the host would then take the
role from the grant instead of `team`. That's planned after launch and not built.

## Limits

- Roles are per device, not per person: two phones of one person are two entries.
- One computer is one team; there's no organisation spanning computers yet.
- The audit log grows without pruning (records are small; export and retention come later).
- The spending day is the UTC day.
- The hash chain detects edits made outside Hypurr; it can't stop someone with full access to
  the computer from replacing the whole database.

## Tests

- Unit: `cargo test --bin hypurr-host admin` (roles, method gate, policy validation, agent
  rules, append-only and chained log, no secrets in records).
- End to end: `host/tests/e2e.rs` `team_roles_policies_approvals_and_audit_log` (two paired
  phones over the E2E channel: default role, promotion, member refused, allowed agents, an
  approval only the admin can answer, viewer read-only, audit log and activity; writes
  `docs/reference/fixtures/admin-wire.json` with `HYPURR_WIRE_OUT`), and the end of
  `host/tests/tasks_e2e.rs` (task and daily spending limits stop and refuse, audit actions).
- Android: `AdminWireTest` (fixture), `AdminStoreTest`, screenshots `android-admin-*`, `android-needs-admin-*`.
- Swift: `kit/Tests/HypurrKitTests/AdminWireTests.swift`.
