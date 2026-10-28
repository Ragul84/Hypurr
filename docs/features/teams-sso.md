# Teams: work-account sign-in, one credit pool, SCIM (gateway)

Status: **code and tests only, not deployed.** The live gateway (`hypurr.mindgains.ai`) does
not have these routes until the owner approves a deploy from `main`, and migration
`cloud/migrations/0004_teams.sql` has to be applied first (`wrangler d1 migrations apply`).

## What it does

- **A team** is created by a signed-in Hypurr user, who becomes its admin. One team per person.
- **Work-account sign-in (OIDC).** The admin connects Google Workspace or Microsoft Entra ID
  with the team's own OAuth client id and email domain. Anyone with a verified work email on
  that domain signs in and joins as a member; personal domains (gmail.com, outlook.com…) are refused.
  The gateway checks the ID token's signature (provider JWKS), issuer, audience, expiry, verified
  email and, for Google, the `hd` Workspace claim.
- **One credit pool.** Members never buy credits. Their gateway use is paid from the pool
  (`team:<id>` in `gateway_credits`). The admin fills the pool from their own purchased credits
  and can give each person a monthly limit, make someone admin, or pause them (pausing revokes
  their keys at once).
- **SCIM 2.0 (stub).** Enough for an identity provider to provision and deprovision people:
  create user, list/filter by `userName`, `PATCH active=false`, `DELETE`. No groups, bulk, or sorting.

## API

All JSON. Clerk session token = a normal signed-in Hypurr user.

| Method and path | Who | Body / result |
|---|---|---|
| `POST /v1/teams` | Clerk user | `{name}` → `{id, name, role:"admin"}` |
| `GET /v1/teams/mine` | Clerk user | team, role, own limit and spend; admins also get `members` and `poolCents` |
| `PUT /v1/teams/:id/sso` | admin | `{provider:"google"\|"microsoft", domain, clientId, tenant?}` |
| `POST /v1/teams/sso` | anyone | `{email, idToken}` → `{apiKey, keyPrefix, team, email}` (key shown once) |
| `POST /v1/teams/:id/pool` | admin | `{amountCents}` moves admin credits → pool |
| `PATCH /v1/teams/:id/members/:userId` | admin | `{role?, status?: "active"\|"suspended", monthlyLimitCents?: int\|null}` |
| `POST /v1/teams/:id/scim-token` | admin | → `{token, baseUrl}` (shown once; replaces the old one) |
| `GET /scim/v2/ServiceProviderConfig` | – | SCIM capabilities |
| `GET/POST /scim/v2/Users`, `PATCH/DELETE /scim/v2/Users/:id` | SCIM token | provision / deprovision |

`GET /v1/gateway/balance` now includes `team: {id, monthlyLimitCents, spentThisMonthCents}` for
members, and `credits.balanceCents` is what that person can still spend (pool capped by limit).

Errors members see in plain words: "Your team's credit pool is empty. Ask your team admin to add
credits.", "You've reached the monthly limit your team admin set. Ask them to raise it.",
"Your team admin paused your access."

## Setting up a provider (the company's IT does this, free)

**Google Workspace:** Google Cloud console → APIs & Services → Credentials → OAuth client ID
(Android, package `com.ragul84.hypurr`, plus a Web client for the ID token audience). Put the
Web client id in `clientId`; `domain` is the Workspace primary domain.

**Microsoft Entra ID:** App registrations → New registration (single tenant) → copy the
Application (client) id and Directory (tenant) id; add the redirect `hypurr://oauth`.

**SCIM (Entra / Okta):** Enterprise app → Provisioning → Tenant URL = `baseUrl`, Secret token =
the SCIM token. Map `userName` to the work email.

## Not done yet

- **App side.** The Android app has no "Sign in with work account" button or team admin
  screen yet, and the host doesn't read `/v1/gateway/balance`. Needs an OAuth client id per
  provider (owned by the customer's IT, or a Hypurr-owned multi-tenant one) before it can be
  tested end to end. The gateway half is ready for it.
- Moving credits back out of a pool, and per-member usage history (the ledger has it in `meta.member`).
- SCIM groups and `PUT` (full replace).
