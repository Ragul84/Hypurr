-- Launch hardening for the Hypurr gateway (hypurr.mindgains.ai):
-- per-subject daily request cap, per-IP trial-key cap, a global daily free-token budget,
-- and RevenueCat (Google Play Billing) purchase idempotency.

ALTER TABLE gateway_allowance ADD COLUMN messages_used INTEGER NOT NULL DEFAULT 0;

CREATE TABLE gateway_trial_ips (
  ip          TEXT NOT NULL,
  day         INTEGER NOT NULL,              -- unix ms of UTC day
  count       INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (ip, day)
);

CREATE TABLE gateway_global_budget (
  day         INTEGER PRIMARY KEY,           -- unix ms of UTC day
  free_tokens INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE gateway_payment_events (
  id           TEXT PRIMARY KEY,             -- provider event id (idempotency)
  provider     TEXT NOT NULL,                -- revenuecat | stripe | razorpay
  subject_id   TEXT,
  product_id   TEXT,
  amount_cents INTEGER NOT NULL DEFAULT 0,   -- credits granted (USD cents)
  created_at   INTEGER NOT NULL
);
