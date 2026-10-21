// RevenueCat webhooks (Google Play Billing) -> Hypurr credits.
// The Android app logs in to RevenueCat with the gateway subject id ("trial:<host>" / "user:<id>")
// as its app user id, so a purchase event names the subject to credit directly.
// RevenueCat signs nothing; it sends the Authorization header value set in its dashboard, which
// must equal the REVENUECAT_WEBHOOK_AUTH secret here. Docs: https://www.revenuecat.com/docs/integrations/webhooks

import { ApiError, type Ctx } from "../api";
import plans from "../../../pricing/plans.json";
import { claw, topUp } from "./ledger";

export type CreditPack = { id: string; productId: string; priceInr: number; creditsCents: number; label: string };

export const CREDIT_PACKS: CreditPack[] = (plans as { creditPacks: { packs: CreditPack[] } }).creditPacks.packs;

const GRANT = new Set(["INITIAL_PURCHASE", "NON_RENEWING_PURCHASE", "RENEWAL"]);
const SUBJECT = /^(user|trial|device):[A-Za-z0-9_.:-]{1,160}$/;

function sameSecret(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

type RcEvent = {
  id?: string;
  type?: string;
  app_user_id?: string;
  original_app_user_id?: string;
  aliases?: string[];
  product_id?: string;
  environment?: string;
  cancel_reason?: string;
  transaction_id?: string;
};

function subjectOf(e: RcEvent): string | undefined {
  return [e.app_user_id, e.original_app_user_id, ...(e.aliases ?? [])].find(
    (s): s is string => typeof s === "string" && SUBJECT.test(s),
  );
}

export async function revenuecatWebhook(c: Ctx): Promise<Response> {
  const env = c.env as unknown as { REVENUECAT_WEBHOOK_AUTH?: string; REVENUECAT_ALLOW_SANDBOX?: string };
  // Refuse everything until the secret is configured: an open webhook would mint credits.
  const want = env.REVENUECAT_WEBHOOK_AUTH;
  const got = c.req.headers.get("authorization") ?? "";
  if (!want || !(sameSecret(got, want) || sameSecret(got, `Bearer ${want}`))) throw new ApiError("unauthenticated");

  let body: { event?: RcEvent };
  try {
    body = JSON.parse(new TextDecoder().decode(c.raw));
  } catch {
    throw new ApiError("badRequest");
  }
  const e = body.event;
  if (!e?.id || !e.type) throw new ApiError("badRequest");
  const ok = (extra: Record<string, unknown> = {}) => Response.json({ ok: true, ...extra });

  if (e.type === "TEST") return ok({ test: true });
  if (e.environment === "SANDBOX" && env.REVENUECAT_ALLOW_SANDBOX !== "1") return ok({ ignored: "sandbox" });

  const pack = CREDIT_PACKS.find((p) => p.productId === e.product_id);
  const subject = subjectOf(e);
  const refund = e.type === "CANCELLATION" && e.cancel_reason === "CUSTOMER_SUPPORT";
  if (!pack || !subject || !(GRANT.has(e.type) || refund)) return ok({ ignored: e.type });

  // Idempotent on the event id: RevenueCat retries until it gets a 200.
  const seen = await c.env.DB.prepare(`SELECT id FROM gateway_payment_events WHERE id = ?`).bind(`rc:${e.id}`).first();
  if (seen) return ok({ duplicate: true });
  await c.env.DB.prepare(
    `INSERT INTO gateway_payment_events (id, provider, subject_id, product_id, amount_cents, created_at)
     VALUES (?, 'revenuecat', ?, ?, ?, ?)`,
  )
    .bind(`rc:${e.id}`, subject, pack.productId, refund ? -pack.creditsCents : pack.creditsCents, c.now)
    .run();

  const meta = { revenuecatEvent: e.id, product: pack.productId, tx: e.transaction_id ?? null };
  if (refund) {
    const taken = await claw(c.env.DB, subject, pack.creditsCents, meta, c.now);
    return ok({ refunded: taken });
  }
  const balance = await topUp(c.env.DB, subject, pack.creditsCents, meta, c.now);
  return ok({ granted: pack.creditsCents, balance });
}
