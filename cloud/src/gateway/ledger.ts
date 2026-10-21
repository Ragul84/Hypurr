import type { GatewayConfig } from "./config";

export type Allowance = {
  messagesUsed: number;
  tokensUsed: number;
  tokensLimit: number;
  periodStart: number;
  remaining: number;
};

function randomId(bytes = 8): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return [...buf].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function dayBucket(now: number): number {
  const d = new Date(now);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
}

export async function getAllowance(
  db: D1Database,
  subjectId: string,
  cfg: GatewayConfig,
  kind: "user" | "device" | "trial",
  now = Date.now(),
): Promise<Allowance> {
  const limit = kind === "trial" ? cfg.trialDailyTokens : cfg.freeDailyTokens;
  // Lifetime allowance: one bucket forever (cfg.freeLifetime); otherwise per UTC day.
  const periodStart = cfg.freeLifetime ? 0 : dayBucket(now);
  const row = await db
    .prepare(`SELECT tokens_used, tokens_limit, period_start, messages_used FROM gateway_allowance WHERE subject_id = ?`)
    .bind(subjectId)
    .first<{ tokens_used: number; tokens_limit: number; period_start: number; messages_used?: number }>();
  if (!row || row.period_start !== periodStart) {
    await db
      .prepare(
        `INSERT INTO gateway_allowance (subject_id, period, period_start, tokens_used, tokens_limit, updated_at)
         VALUES (?, 'day', ?, 0, ?, ?)
         ON CONFLICT(subject_id) DO UPDATE SET
           period_start = excluded.period_start,
           tokens_used = 0,
           messages_used = 0,
           tokens_limit = excluded.tokens_limit,
           updated_at = excluded.updated_at`,
      )
      .bind(subjectId, periodStart, limit, now)
      .run();
    return { messagesUsed: 0, tokensUsed: 0, tokensLimit: limit, periodStart, remaining: limit };
  }
  return {
    messagesUsed: row.messages_used ?? 0,
    tokensUsed: row.tokens_used,
    tokensLimit: row.tokens_limit,
    periodStart: row.period_start,
    remaining: Math.max(0, row.tokens_limit - row.tokens_used),
  };
}

export async function consumeAllowance(db: D1Database, subjectId: string, tokens: number, now = Date.now()) {
  await db
    .prepare(`UPDATE gateway_allowance SET tokens_used = tokens_used + ?, updated_at = ? WHERE subject_id = ?`)
    .bind(tokens, now, subjectId)
    .run();
}

export async function getCredits(db: D1Database, subjectId: string): Promise<number> {
  const row = await db
    .prepare(`SELECT balance_cents FROM gateway_credits WHERE subject_id = ?`)
    .bind(subjectId)
    .first<{ balance_cents: number }>();
  return row?.balance_cents ?? 0;
}

export async function topUp(
  db: D1Database,
  subjectId: string,
  amountCents: number,
  meta: Record<string, unknown>,
  now = Date.now(),
) {
  const bal = await getCredits(db, subjectId);
  const next = bal + amountCents;
  await db
    .prepare(
      `INSERT INTO gateway_credits (subject_id, balance_cents, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(subject_id) DO UPDATE SET balance_cents = ?, updated_at = ?`,
    )
    .bind(subjectId, next, now, next, now)
    .run();
  const id = `led_${randomId()}`;
  await db
    .prepare(
      `INSERT INTO gateway_ledger (id, subject_id, kind, amount_cents, balance_after, meta, created_at)
       VALUES (?, ?, 'topup', ?, ?, ?, ?)`,
    )
    .bind(id, subjectId, amountCents, next, JSON.stringify(meta), now)
    .run();
  return next;
}

export async function spendCredits(
  db: D1Database,
  subjectId: string,
  amountCents: number,
  meta: Record<string, unknown>,
  now = Date.now(),
): Promise<{ ok: true; balance: number } | { ok: false; balance: number; message: string }> {
  const bal = await getCredits(db, subjectId);
  if (bal < amountCents) {
    return {
      ok: false,
      balance: bal,
      message:
        "You're out of Hypurr credits for this model. Buy more credits in Spending settings, or switch to a free model.",
    };
  }
  const next = bal - amountCents;
  await db
    .prepare(`UPDATE gateway_credits SET balance_cents = ?, updated_at = ? WHERE subject_id = ?`)
    .bind(next, now, subjectId)
    .run();
  const id = `led_${randomId()}`;
  await db
    .prepare(
      `INSERT INTO gateway_ledger (id, subject_id, kind, amount_cents, balance_after, meta, created_at)
       VALUES (?, ?, 'spend', ?, ?, ?, ?)`,
    )
    .bind(id, subjectId, -amountCents, next, JSON.stringify(meta), now)
    .run();
  return { ok: true, balance: next };
}

export async function recordUsage(
  db: D1Database,
  input: {
    subjectId: string;
    model: string;
    promptTokens: number;
    completionTokens: number;
    costCents: number;
    source: "free" | "credits";
  },
  now = Date.now(),
) {
  const id = `use_${randomId()}`;
  await db
    .prepare(
      `INSERT INTO gateway_usage (id, subject_id, model, prompt_tokens, completion_tokens, cost_cents, source, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
      id,
      input.subjectId,
      input.model,
      input.promptTokens,
      input.completionTokens,
      input.costCents,
      input.source,
      now,
    )
    .run();
}

/** One more request against today's per-subject request cap. */
export async function countMessage(db: D1Database, subjectId: string, now = Date.now()) {
  await db
    .prepare(`UPDATE gateway_allowance SET messages_used = messages_used + 1, updated_at = ? WHERE subject_id = ?`)
    .bind(now, subjectId)
    .run();
}

/** Free tokens served today across all users (kill switch for the OpenRouter bill). */
export async function globalFreeUsed(db: D1Database, now = Date.now()): Promise<number> {
  const row = await db
    .prepare(`SELECT free_tokens FROM gateway_global_budget WHERE day = ?`)
    .bind(dayBucket(now))
    .first<{ free_tokens: number }>();
  return row?.free_tokens ?? 0;
}

export async function addGlobalFree(db: D1Database, tokens: number, now = Date.now()) {
  await db
    .prepare(
      `INSERT INTO gateway_global_budget (day, free_tokens) VALUES (?, ?)
       ON CONFLICT(day) DO UPDATE SET free_tokens = free_tokens + excluded.free_tokens`,
    )
    .bind(dayBucket(now), tokens)
    .run();
}

/** Counts a trial key issued to `ip` today; false once the IP is over `limit`. */
export async function allowTrialIp(db: D1Database, ip: string, limit: number, now = Date.now()): Promise<boolean> {
  const day = dayBucket(now);
  const row = await db
    .prepare(`SELECT count FROM gateway_trial_ips WHERE ip = ? AND day = ?`)
    .bind(ip, day)
    .first<{ count: number }>();
  if ((row?.count ?? 0) >= limit) return false;
  await db
    .prepare(
      `INSERT INTO gateway_trial_ips (ip, day, count) VALUES (?, ?, 1)
       ON CONFLICT(ip, day) DO UPDATE SET count = count + 1`,
    )
    .bind(ip, day)
    .run();
  return true;
}

/** Takes back up to `amountCents` (a refund); never below zero. Returns what was taken. */
export async function claw(
  db: D1Database,
  subjectId: string,
  amountCents: number,
  meta: Record<string, unknown>,
  now = Date.now(),
): Promise<number> {
  const bal = await getCredits(db, subjectId);
  const take = Math.min(bal, amountCents);
  if (take <= 0) return 0;
  const next = bal - take;
  await db
    .prepare(`UPDATE gateway_credits SET balance_cents = ?, updated_at = ? WHERE subject_id = ?`)
    .bind(next, now, subjectId)
    .run();
  await db
    .prepare(
      `INSERT INTO gateway_ledger (id, subject_id, kind, amount_cents, balance_after, meta, created_at)
       VALUES (?, ?, 'refund', ?, ?, ?, ?)`,
    )
    .bind(`led_${randomId()}`, subjectId, -take, next, JSON.stringify(meta), now)
    .run();
  return take;
}
