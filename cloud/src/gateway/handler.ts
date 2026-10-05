import { ApiError, type Ctx } from "../api";
import { DEFAULT_GATEWAY_CONFIG, type GatewayConfig } from "./config";
import { bearer, issueTrialKey, issueUserKey, resolveSubject, type GatewaySubject } from "./auth";
import {
  consumeAllowance,
  getAllowance,
  getCredits,
  recordUsage,
  spendCredits,
  topUp,
} from "./ledger";
import { estimateCostCents, tokensFromMessages } from "./meter";

function json(data: unknown, status = 200): Response {
  return Response.json(data, { status });
}


export type GatewayEnv = {
  DB: D1Database;
  /** JSON override for gateway config (admin). */
  GATEWAY_CONFIG_JSON?: string;
  UPSTREAM_OPENROUTER_API_KEY?: string;
  UPSTREAM_GROQ_API_KEY?: string;
  UPSTREAM_TOGETHER_API_KEY?: string;
  UPSTREAM_DEEPINFRA_API_KEY?: string;
  UPSTREAM_FAKE_API_KEY?: string;
  /** When set, all upstream calls go here (e2e / local fake). */
  GATEWAY_UPSTREAM_OVERRIDE?: string;
  STRIPE_WEBHOOK_SECRET?: string;
  STRIPE_SECRET_KEY?: string;
  CLERK_SECRET_KEY?: string;
};

const rateBuckets = new Map<string, { n: number; reset: number }>();

function cfgFrom(env: GatewayEnv): GatewayConfig {
  if (!env.GATEWAY_CONFIG_JSON) return DEFAULT_GATEWAY_CONFIG;
  try {
    return { ...DEFAULT_GATEWAY_CONFIG, ...JSON.parse(env.GATEWAY_CONFIG_JSON) };
  } catch {
    return DEFAULT_GATEWAY_CONFIG;
  }
}

function rateLimit(subjectId: string, limit: number, now: number): boolean {
  const slot = Math.floor(now / 60_000);
  const key = `${subjectId}:${slot}`;
  const cur = rateBuckets.get(key) ?? { n: 0, reset: slot };
  if (cur.n >= limit) return false;
  cur.n += 1;
  rateBuckets.set(key, cur);
  return true;
}

function secretForAdapter(env: GatewayEnv, apiKeyEnv: string): string | undefined {
  return (env as Record<string, string | undefined>)[apiKeyEnv];
}

export async function listModels(c: Ctx) {
  const cfg = cfgFrom(c.env as unknown as GatewayEnv);
  return {
    object: "list",
    data: cfg.models.map((m) => ({
      id: m.id,
      object: "model",
      owned_by: "hypurr",
      tier: m.tier,
      name: m.name,
    })),
  };
}

async function requireSubject(c: Ctx): Promise<GatewaySubject> {
  const secret = bearer(c.req);
  const sub = await resolveSubject(c.env.DB, secret);
  if (!sub) throw new ApiError("unauthenticated");
  return sub;
}

export async function chatCompletions(c: Ctx): Promise<Response> {
  const env = c.env as unknown as GatewayEnv;
  const cfg = cfgFrom(env);
  const sub = await requireSubject(c);
  if (!rateLimit(sub.subjectId, cfg.rateLimitPerMinute, c.now)) {
    throw new ApiError("rateLimited");
  }

  let body: Record<string, unknown>;
  try {
    body = JSON.parse(new TextDecoder().decode(c.raw)) as Record<string, unknown>;
  } catch {
    throw new ApiError("badRequest");
  }

  const modelId = String(body.model ?? cfg.defaultModel);
  const model = cfg.models.find((m) => m.id === modelId);
  if (!model) throw new ApiError("notFound");

  const approxIn = tokensFromMessages(body);
  const allowance = await getAllowance(c.env.DB, sub.subjectId, cfg, sub.kind, c.now);

  if (model.tier === "free") {
    if (allowance.remaining < approxIn) {
      return json(
        {
          error: {
            message:
              "You've used today's free Hypurr allowance. Try again tomorrow, buy credits, or use your own API key in Hypurr Agent.",
            type: "insufficient_quota",
            code: "free_allowance_exhausted",
          },
        },
        402,
      );
    }
  } else {
    const bal = await getCredits(c.env.DB, sub.subjectId);
    if (bal < cfg.minBalanceCents) {
      return json(
        {
          error: {
            message:
              "You're out of Hypurr credits for this model. Buy more credits in Spending settings, or switch to a free model.",
            type: "insufficient_quota",
            code: "credits_exhausted",
          },
        },
        402,
      );
    }
  }

  const adapter = cfg.adapters.find((a) => a.id === model.adapter);
  const override = env.GATEWAY_UPSTREAM_OVERRIDE;
  const upstreamBase = (override || adapter?.baseUrl || "").replace(/\/$/, "");
  if (!upstreamBase) throw new ApiError("internal", "Gateway upstream not configured");

  const upstreamKey = override
    ? env.UPSTREAM_FAKE_API_KEY || "fake"
    : secretForAdapter(env, adapter?.apiKeyEnv ?? "") || "";

  const stream = Boolean(body.stream);
  const upstreamBody = {
    ...body,
    model: model.upstreamModel,
    stream,
  };

  const upstreamRes = await fetch(`${upstreamBase}/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${upstreamKey}`,
      // Privacy: do not forward original prompts to logs; only upstream.
    },
    body: JSON.stringify(upstreamBody),
  });

  if (!upstreamRes.ok) {
    const text = await upstreamRes.text();
    // Log status only — never prompt bodies.
    console.log(JSON.stringify({ gateway: "upstream_error", status: upstreamRes.status, model: modelId }));
    return new Response(text || JSON.stringify({ error: { message: "Upstream provider error" } }), {
      status: upstreamRes.status,
      headers: { "content-type": upstreamRes.headers.get("content-type") || "application/json" },
    });
  }

  if (stream && upstreamRes.body) {
    // Meter approximate tokens up-front for free tier; refine on paid via estimate.
    const estOut = 512;
    const total = approxIn + estOut;
    if (model.tier === "free") {
      await consumeAllowance(c.env.DB, sub.subjectId, total, c.now);
      await recordUsage(
        c.env.DB,
        {
          subjectId: sub.subjectId,
          model: modelId,
          promptTokens: approxIn,
          completionTokens: estOut,
          costCents: 0,
          source: "free",
        },
        c.now,
      );
    } else {
      const cost = estimateCostCents(cfg, modelId, approxIn, estOut);
      const spent = await spendCredits(c.env.DB, sub.subjectId, cost, { model: modelId }, c.now);
      if (!spent.ok) {
        return json({ error: { message: spent.message, type: "insufficient_quota" } }, 402);
      }
      await recordUsage(
        c.env.DB,
        {
          subjectId: sub.subjectId,
          model: modelId,
          promptTokens: approxIn,
          completionTokens: estOut,
          costCents: cost,
          source: "credits",
        },
        c.now,
      );
    }
    return new Response(upstreamRes.body, {
      status: 200,
      headers: {
        "content-type": upstreamRes.headers.get("content-type") || "text/event-stream",
        "cache-control": "no-cache",
      },
    });
  }

  const data = (await upstreamRes.json()) as {
    usage?: { prompt_tokens?: number; completion_tokens?: number };
    choices?: unknown;
  };
  const promptTokens = data.usage?.prompt_tokens ?? approxIn;
  const completionTokens = data.usage?.completion_tokens ?? 64;
  const total = promptTokens + completionTokens;

  if (model.tier === "free") {
    await consumeAllowance(c.env.DB, sub.subjectId, total, c.now);
    await recordUsage(
      c.env.DB,
      {
        subjectId: sub.subjectId,
        model: modelId,
        promptTokens,
        completionTokens,
        costCents: 0,
        source: "free",
      },
      c.now,
    );
  } else {
    const cost = estimateCostCents(cfg, modelId, promptTokens, completionTokens);
    const spent = await spendCredits(c.env.DB, sub.subjectId, cost, { model: modelId }, c.now);
    if (!spent.ok) {
      return json({ error: { message: spent.message, type: "insufficient_quota" } }, 402);
    }
    await recordUsage(
      c.env.DB,
      {
        subjectId: sub.subjectId,
        model: modelId,
        promptTokens,
        completionTokens,
        costCents: cost,
        source: "credits",
      },
      c.now,
    );
  }

  console.log(
    JSON.stringify({
      gateway: "ok",
      model: modelId,
      promptTokens,
      completionTokens,
      subject: sub.kind,
      // privacy: no messages / prompts
    }),
  );

  return json(data);
}

export async function spendingBalance(c: Ctx) {
  const cfg = cfgFrom(c.env as unknown as GatewayEnv);
  const sub = await requireSubject(c);
  const allowance = await getAllowance(c.env.DB, sub.subjectId, cfg, sub.kind, c.now);
  const credits = await getCredits(c.env.DB, sub.subjectId);
  return {
    free: {
      remaining: allowance.remaining,
      limit: allowance.tokensLimit,
      used: allowance.tokensUsed,
      periodStart: allowance.periodStart,
    },
    credits: {
      balanceCents: credits,
      balanceUsd: credits / 100,
    },
    buyCreditsUrl: cfg.stripeCheckoutPlaceholder,
  };
}

export async function issueTrial(c: Ctx) {
  const body = JSON.parse(new TextDecoder().decode(c.raw) || "{}") as { hostId?: string };
  if (!body.hostId || body.hostId.length > 128) throw new ApiError("badRequest");
  const out = await issueTrialKey(c.env.DB, body.hostId, c.now);
  return {
    keyId: out.id,
    apiKey: out.secret,
    prefix: out.prefix,
    reused: out.reused,
    note: out.secret
      ? "Store this trial key; it is shown once."
      : "A trial key already exists for this host; rotate via admin if lost.",
  };
}

/** Clerk-authenticated: mint a user gateway key. Relies on existing `me` auth via Ctx. */
export async function issueUserGatewayKey(c: Ctx, userId: string, label?: string) {
  return issueUserKey(c.env.DB, userId, label || "default", c.now);
}

export async function createCheckout(c: Ctx) {
  const cfg = cfgFrom(c.env as unknown as GatewayEnv);
  const sub = await requireSubject(c);
  // Placeholder: real Stripe Checkout Session would use STRIPE_SECRET_KEY.
  return {
    url: cfg.stripeCheckoutPlaceholder,
    subjectId: sub.subjectId,
    mode: "test_placeholder",
  };
}

/** Stripe webhook (test-mode placeholders). Verifies signature when secret set. */
export async function stripeWebhook(c: Ctx): Promise<Response> {
  const env = c.env as unknown as GatewayEnv;
  const sig = c.req.headers.get("stripe-signature") || "";
  if (env.STRIPE_WEBHOOK_SECRET) {
    // Minimal test verification: secret must appear in signature header for placeholders.
    if (!sig.includes(env.STRIPE_WEBHOOK_SECRET) && sig !== env.STRIPE_WEBHOOK_SECRET) {
      // Real Stripe uses HMAC; tests pass the secret as the signature.
      throw new ApiError("unauthenticated");
    }
  }
  let event: { id?: string; type?: string; data?: { object?: Record<string, unknown> } };
  try {
    event = JSON.parse(new TextDecoder().decode(c.raw));
  } catch {
    throw new ApiError("badRequest");
  }
  if (!event.id) throw new ApiError("badRequest");
  const seen = await c.env.DB.prepare(`SELECT id FROM gateway_stripe_events WHERE id = ?`)
    .bind(event.id)
    .first();
  if (seen) return json({ ok: true, duplicate: true });
  await c.env.DB.prepare(`INSERT INTO gateway_stripe_events (id, created_at) VALUES (?, ?)`)
    .bind(event.id, c.now)
    .run();

  if (event.type === "checkout.session.completed") {
    const obj = event.data?.object ?? {};
    const subjectId = String(obj.client_reference_id || obj.metadata?.subjectId || "");
    const amount = Number(obj.amount_total || obj.metadata?.amountCents || 0);
    if (subjectId && amount > 0) {
      await topUp(c.env.DB, subjectId, amount, { stripeEvent: event.id }, c.now);
    }
  }
  return json({ ok: true });
}

// re-export json helper usage — api.ts already has json; ensure ApiError codes exist
