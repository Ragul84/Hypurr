-- Hypurr Agent gateway: API keys, free allowance, paid credits ledger, metering.
CREATE TABLE gateway_api_keys (
  id            TEXT PRIMARY KEY,               -- key_…
  user_id       TEXT REFERENCES accounts(user_id),
  host_id       TEXT,                           -- anonymous / device-token install id
  key_hash      TEXT NOT NULL UNIQUE,           -- sha256 hex of secret
  key_prefix    TEXT NOT NULL,                  -- first 8 chars for display
  label         TEXT NOT NULL DEFAULT 'default',
  kind          TEXT NOT NULL DEFAULT 'user' CHECK (kind IN ('user','device','trial')),
  created_at    INTEGER NOT NULL,
  revoked_at    INTEGER,
  last_used_at  INTEGER
);
CREATE INDEX gateway_api_keys_user ON gateway_api_keys(user_id);
CREATE INDEX gateway_api_keys_host ON gateway_api_keys(host_id);

CREATE TABLE gateway_allowance (
  subject_id    TEXT PRIMARY KEY,               -- user_id or trial:host_id
  period        TEXT NOT NULL DEFAULT 'day' CHECK (period IN ('day','month')),
  period_start  INTEGER NOT NULL,               -- unix ms of period bucket
  tokens_used   INTEGER NOT NULL DEFAULT 0,
  tokens_limit  INTEGER NOT NULL DEFAULT 200000, -- configurable free budget
  updated_at    INTEGER NOT NULL
);

CREATE TABLE gateway_credits (
  subject_id    TEXT PRIMARY KEY,
  balance_cents INTEGER NOT NULL DEFAULT 0,      -- USD cents
  updated_at    INTEGER NOT NULL
);

CREATE TABLE gateway_ledger (
  id            TEXT PRIMARY KEY,
  subject_id    TEXT NOT NULL,
  kind          TEXT NOT NULL CHECK (kind IN ('topup','spend','refund','adjust')),
  amount_cents  INTEGER NOT NULL,               -- signed: +topup / -spend
  balance_after INTEGER NOT NULL,
  meta          TEXT,                           -- json (no prompts)
  created_at    INTEGER NOT NULL
);
CREATE INDEX gateway_ledger_subject ON gateway_ledger(subject_id, created_at);

CREATE TABLE gateway_usage (
  id            TEXT PRIMARY KEY,
  subject_id    TEXT NOT NULL,
  model         TEXT NOT NULL,
  prompt_tokens INTEGER NOT NULL DEFAULT 0,
  completion_tokens INTEGER NOT NULL DEFAULT 0,
  cost_cents    INTEGER NOT NULL DEFAULT 0,
  source        TEXT NOT NULL DEFAULT 'free' CHECK (source IN ('free','credits')),
  created_at    INTEGER NOT NULL
);
CREATE INDEX gateway_usage_subject ON gateway_usage(subject_id, created_at);

CREATE TABLE gateway_stripe_events (
  id            TEXT PRIMARY KEY,               -- Stripe event id (idempotency)
  created_at    INTEGER NOT NULL
);
