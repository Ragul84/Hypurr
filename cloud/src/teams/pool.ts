// Who pays for a gateway call: the person, or their team's shared credit pool.
// A member of a team always spends from the pool (subject 'team:<id>'), within the monthly
// limit the admin assigned them. Purchases still credit whoever bought them.

import type { GatewaySubject } from "../gateway/auth";
import { getCredits, spendCredits } from "../gateway/ledger";

export interface Payer {
  subjectId: string;
  team?: { id: string; userId: string; limitCents: number | null; spentCents: number; status: string };
}

export function monthBucket(now: number): number {
  const d = new Date(now);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1);
}

export async function payerFor(db: D1Database, sub: GatewaySubject, now = Date.now()): Promise<Payer> {
  if (!sub.userId) return { subjectId: sub.subjectId };
  const m = await db
    .prepare(`SELECT team_id, status, monthly_limit_cents, spent_cents, period_start FROM team_members WHERE user_id = ?`)
    .bind(sub.userId)
    .first<{ team_id: string; status: string; monthly_limit_cents: number | null; spent_cents: number; period_start: number }>();
  if (!m) return { subjectId: sub.subjectId };
  const month = monthBucket(now);
  const spent = m.period_start === month ? m.spent_cents : 0;
  return {
    subjectId: `team:${m.team_id}`,
    team: { id: m.team_id, userId: sub.userId, limitCents: m.monthly_limit_cents, spentCents: spent, status: m.status },
  };
}

/** What this payer can spend right now: the pool, capped by the person's monthly limit. */
export async function payerBalance(db: D1Database, p: Payer): Promise<number> {
  const bal = await getCredits(db, p.subjectId);
  if (!p.team) return bal;
  if (p.team.status !== "active") return 0;
  if (p.team.limitCents == null) return bal;
  return Math.max(0, Math.min(bal, p.team.limitCents - p.team.spentCents));
}

export const TEAM_LIMIT_MESSAGE = "You've reached the monthly limit your team admin set. Ask them to raise it.";

export async function payerSpend(
  db: D1Database,
  p: Payer,
  amountCents: number,
  meta: Record<string, unknown>,
  now = Date.now(),
): Promise<{ ok: true; balance: number } | { ok: false; balance: number; message: string }> {
  if (!p.team) return spendCredits(db, p.subjectId, amountCents, meta, now);
  if (p.team.status !== "active") return { ok: false, balance: 0, message: "Your team admin paused your access." };
  if (p.team.limitCents != null && p.team.spentCents + amountCents > p.team.limitCents && !meta.reconcile) {
    return { ok: false, balance: 0, message: TEAM_LIMIT_MESSAGE };
  }
  const r = await spendCredits(db, p.subjectId, amountCents, { ...meta, member: p.team.userId }, now);
  if (!r.ok) return { ...r, message: "Your team's credit pool is empty. Ask your team admin to add credits." };
  {
    const month = monthBucket(now);
    await db
      .prepare(
        `UPDATE team_members SET
           spent_cents = CASE WHEN period_start = ?1 THEN spent_cents + ?2 ELSE ?2 END,
           period_start = ?1
         WHERE team_id = ?3 AND user_id = ?4`,
      )
      .bind(month, amountCents, p.team.id, p.team.userId)
      .run();
    p.team.spentCents += amountCents;
  }
  return r;
}
