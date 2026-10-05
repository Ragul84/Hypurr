# Feature 12: Hypurr Agent + gateway

Hypurr ships its own coding agent (**Hypurr Agent**) and an OpenAI-compatible **gateway** so
users never see “OpenCode” branding. Hypurr Agent is a MIT fork of OpenCode; attribution is
only in `LICENSE` / `NOTICE`.

## Hypurr Agent (`Ragul84/hypurr-agent`)

- Binary: `hypurr-agent` (ACP: `hypurr-agent acp`)
- Config / data: `~/.hypurr/agent/`
- Default provider: Hypurr gateway (`HYPURR_GATEWAY_URL`, `HYPURR_API_KEY`)
- BYOK: Anthropic, OpenAI, Google, OpenRouter, and other upstream plugins remain available
- Managed install: pinned GitHub release into `~/.hypurr/agents/hypurr-agent/<version>/`
- Optional: a user-installed real OpenCode CLI may still appear as agent id `opencode`, never as the default

## Gateway (Cloudflare Worker under `cloud/`)

| Route | Purpose |
|-------|---------|
| `POST /v1/chat/completions` | OpenAI-compatible chat (streaming supported) |
| `GET /v1/models` | Model list from gateway config |
| `GET /v1/gateway/balance` | Free allowance + credits |
| `POST /v1/gateway/trial-key` | Anonymous trial key bound to host install id |
| `POST /v1/gateway/checkout` | Stripe Checkout URL (placeholder in test) |
| `POST /v1/webhooks/stripe` | Top-up ledger on `checkout.session.completed` |

Auth: per-user API keys (Clerk account), device/trial keys with abuse limits.
Free allowance: configurable daily token budget on cheap open models.
Paid credits: D1 ledger + Stripe (test placeholders) + markup metering.
Privacy: request logs do **not** store prompts.

### Upstream adapters (config; keys as Worker secrets)

OpenRouter, Groq, Together, DeepInfra — OpenAI-compatible HTTP. Set e.g.
`UPSTREAM_OPENROUTER_API_KEY`. For local e2e set `GATEWAY_UPSTREAM_OVERRIDE` to a fake upstream.

## Placeholders / secrets the operator must provide

| Secret / var | Where |
|--------------|--------|
| `UPSTREAM_OPENROUTER_API_KEY` (and/or Groq/Together/DeepInfra) | Cloudflare Worker secrets |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | Worker secrets (test mode OK) |
| `CLERK_SECRET_KEY` / Clerk issuer (existing) | Worker |
| D1 database id | `wrangler.toml` |
| `HYPURR_GATEWAY_URL` / `HYPURR_API_KEY` | Hypurr Agent / host env |
| Release digests for Hypurr Agent binaries | `host/src/agent/builtin.rs` after first release |

## Pricing hypotheses (assumptions)

See `FREE_TIER_COST_ASSUMPTIONS` in `cloud/src/gateway/config.ts`:

- ~8k tokens/task on cheap open models ≈ **$0.002/task** upstream (assumption)
- Free daily budget default **200k tokens** ≈ **~25 tasks/day** (assumption)
- Paid markup default **1.2×** upstream list (assumption)

These are **not** guarantees; label them as assumptions in product materials.
