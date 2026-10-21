# Deploying the Hypurr gateway to hypurr.mindgains.ai

The Worker in this folder serves Hypurr Agent's free models (via OpenRouter), sells credits
(RevenueCat over Google Play Billing), and hosts the account relay. It runs on **your**
Cloudflare account; nothing here deploys automatically.

`./scripts/deploy.sh` does every step below and is safe to re-run. The manual commands follow
so you can see (or do) each one yourself.

## Before you start

| You need | Where | Notes |
|---|---|---|
| Cloudflare account with **mindgains.ai as an active zone** | dash.cloudflare.com → Websites | The custom domain only works if the zone's DNS is on Cloudflare. `hypurr.mindgains.ai` must not already have a DNS record. |
| Node 22+ | `node -v` | Wrangler 4 refuses older Node. |
| OpenRouter key **with credits** | openrouter.ai/settings/keys and /settings/credits | Free-tier cost is capped by `globalFreeDailyTokens` (≈ US$6/day at most). Set a credit limit on the key too. |
| RevenueCat webhook secret | any long random string, e.g. `openssl rand -hex 32` | Paste the same value into RevenueCat (below). |

## Manual steps (what the script does)

```bash
cd cloud
npm ci
npx wrangler login                       # browser sign-in, once per machine

# 1. D1 database; copy the printed database_id into wrangler.toml (REPLACE_WITH_YOUR_D1_DATABASE_ID)
npx wrangler d1 create hypurr

# 2. Schema (0001 accounts/relay, 0002 gateway, 0003 launch limits + payments)
npx wrangler d1 migrations apply hypurr --env "" --remote

# 3. Secrets (prompted; never put them in wrangler.toml)
npx wrangler secret put UPSTREAM_OPENROUTER_API_KEY --env ""
npx wrangler secret put REVENUECAT_WEBHOOK_AUTH --env ""

# 4. Deploy. wrangler.toml's route { pattern = "hypurr.mindgains.ai", custom_domain = true }
#    makes Cloudflare create the DNS record and certificate for you.
npx wrangler deploy --env ""

# 5. Check
curl https://hypurr.mindgains.ai/v1/health          # {"ok":true,...}
curl https://hypurr.mindgains.ai/v1/models          # hypurr-free, hypurr-fast, hypurr-pro
```

No KV namespace is needed: allowances, credits, caps and payment idempotency live in D1, and
per-minute limits use Workers Rate Limiting bindings (declared in wrangler.toml, nothing to create).

A test copy: `./scripts/deploy.sh --env dev` → `hypurr-dev.mindgains.ai`, database `hypurr-dev`.

## RevenueCat + Google Play (paid credits)

1. Play Console → the Hypurr app → Monetize → In-app products: create **consumable** products
   `hypurr_credits_99`, `hypurr_credits_249`, `hypurr_credits_499` at ₹99 / ₹249 / ₹499
   (the list lives in `pricing/plans.json` → `creditPacks`). Needs a Play developer account with a
   payments profile, and the app uploaded to at least internal testing.
2. RevenueCat → new project → add the Google Play app (package `com.ragul84.hypurr`, service-account
   JSON from Play) → import the three products → put them in an offering called `credits`.
3. RevenueCat → Integrations → Webhooks: URL `https://hypurr.mindgains.ai/v1/webhooks/revenuecat`,
   Authorization header = your `REVENUECAT_WEBHOOK_AUTH` value. Send a test event; it should return 200.
4. Copy RevenueCat's **public Android SDK key** (`goog_…`) and build the app with
   `-Phypurr.revenuecatKey=goog_…` (or `REVENUECAT_ANDROID_KEY` in CI). Without it the Buy buttons stay disabled.

Purchases credit the gateway subject the phone shows as `appUserId` (from `GET /v1/gateway/balance`).
Sandbox (test-card) purchases are ignored unless you set the var `REVENUECAT_ALLOW_SANDBOX=1`.
Refunds made through RevenueCat support (`CANCELLATION` / `CUSTOMER_SUPPORT`) take the credits back.

Stripe is off: `/v1/gateway/checkout` and `/v1/webhooks/stripe` return 404 unless the var
`PAYMENTS_STRIPE=1` is set. Razorpay (web) is a later step.

## Point the apps at it

The host already defaults to `https://hypurr.mindgains.ai`, but only offers free models after
`/v1/health` answers there (checked at start and every 10 minutes). So nothing changes for users
until the deploy is live. `HYPURR_GATEWAY_URL=https://…` overrides it (e.g. the dev copy), and
`HYPURR_GATEWAY_URL=off` turns the gateway off on that computer. Local-only mode never uses it.

## Limits (cloud/src/gateway/config.ts, overridable with the GATEWAY_CONFIG_JSON var)

| Setting | Default |
|---|---|
| Free tokens per user / trial key per UTC day | 200,000 |
| Free requests per user per day | 150 |
| Requests per minute per key | 20 |
| Max output tokens per request | 8,192 |
| Max prompt per request | 120,000 tokens |
| New trial keys per IP per day | 5 |
| All free usage per day (kill switch) | 40M tokens ≈ US$6 |
