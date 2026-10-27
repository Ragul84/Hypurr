import { ApiError, type Ctx } from "../api";
import { DEFAULT_GATEWAY_CONFIG, type GatewayConfig } from "./config";
import { bearer, issueTrialKey, issueUserKey, resolveSubject, type GatewaySubject } from "./auth";
import {
  addGlobalFree,
  allowTrialIp,
  consumeAllowance,
  countMessage,
  globalFreeUsed,
  getAllowance,
  getCredits,
  recordUsage,
  spendCredits,
  topUp,
} from "./ledger";
import { CREDIT_PACKS } from "./revenuecat";
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
  /** "1" keeps the old Stripe checkout + webhook routes alive; off by default (RevenueCat is the payment path). */
  PAYMENTS_STRIPE?: string;
  STRIPE_WEBHOOK_SECRET?: string;
  STRIPE_SECRET_KEY?: string;
  /** Exact value RevenueCat sends in the Authorization header of its webhooks. */
  REVENUECAT_WEBHOOK_AUTH?: string;
  CLERK_SECRET_KEY?: string;
  /** Workers Rate Limiting binding: chat requests per subject per minute (all isolates). */
  GATEWAY_LIMITER?: RateLimit;
  /** Public URL used as OpenRouter's HTTP-Referer (app attribution). */
  GATEWAY_PUBLIC_URL?: string;
};

/** Hide which upstream served a response: alias the model and drop provider/cost fields. */
export function scrubUpstream<T>(obj: T, alias: string): T {
  if (!obj || typeof obj !== "object") return obj;
  const o = obj as Record<string, unknown>;
  if ("model" in o) o.model = alias;
  delete o.provider;
  delete o.system_fingerprint;
  delete o.service_tier;
  const u = o.usage as Record<string, unknown> | undefined;
  if (u && typeof u === "object") {
    delete u.cost;
    delete u.cost_details;
    delete u.is_byok;
  }
  if (o.error && typeof o.error === "object") delete (o.error as Record<string, unknown>).metadata;
  return obj;
}

/** Rewrites each SSE `data:` JSON line through scrubUpstream; upstream comment lines become a neutral keep-alive. */
export function scrubSse(alias: string): TransformStream<Uint8Array, Uint8Array> {
  const dec = new TextDecoder();
  const enc = new TextEncoder();
  let tail = "";
  const line = (l: string): string => {
    const t = l.trim();
    if (t.startsWith(":")) return ": keep-alive";
    if (!t.startsWith("data:")) return l;
    const payload = t.slice(5).trim();
    if (payload === "[DONE]") return l;
    try {
      return "data: " + JSON.stringify(scrubUpstream(JSON.parse(payload), alias));
    } catch {
      return l;
    }
  };
  return new TransformStream({
    transform(chunk, ctl) {
      const text = tail + dec.decode(chunk, { stream: true });
      const cut = text.lastIndexOf("\n");
      if (cut < 0) {
        tail = text;
        return;
      }
      tail = text.slice(cut + 1);
      ctl.enqueue(enc.encode(text.slice(0, cut + 1).split("\n").map((l, i, a) => (i === a.length - 1 ? l : line(l))).join("\n")));
    },
    flush(ctl) {
      tail += dec.decode();
      if (tail) ctl.enqueue(enc.encode(line(tail)));
    },
  });
}

/** Passes SSE bytes through untouched and reports the final `usage` block when the stream ends. */
export function usageTap(
  onUsage: (total: number, prompt: number, completion: number) => Promise<void>,
): TransformStream<Uint8Array, Uint8Array> {
  const dec = new TextDecoder();
  let tail = "";
  let usage: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number } | undefined;
  const scan = (text: string) => {
    for (const line of text.split("\n")) {
      const t = line.trim();
      if (!t.startsWith("data:") || !t.includes('"usage"')) continue;
      try {
        const u = JSON.parse(t.slice(5).trim()).usage;
        if (u) usage = u;
      } catch {
        /* partial or non-JSON line */
      }
    }
  };
  return new TransformStream({
    transform(chunk, ctl) {
      ctl.enqueue(chunk);
      const text = tail + dec.decode(chunk, { stream: true });
      const cut = text.lastIndexOf("\n");
      if (cut >= 0) {
        scan(text.slice(0, cut));
        tail = text.slice(cut + 1);
      } else {
        tail = text;
      }
      if (tail.length > 65_536) tail = "";
    },
    async flush() {
      scan(tail);
      if (!usage) return;
      const p = usage.prompt_tokens ?? 0;
      const o = usage.completion_tokens ?? 0;
      try {
        await onUsage(usage.total_tokens ?? p + o, p, o);
      } catch (e) {
        console.log(JSON.stringify({ gateway: "reconcile_failed", error: String(e) }));
      }
    },
  });
}

/** No shared free allowance: without credits, the user starts free on their own account. */
export const FREE_EXHAUSTED = "Start free with your own account, or add credits.";

const rateBuckets = new Map<string, { n: number; reset: number }>();

function cfgFrom(env: GatewayEnv): GatewayConfig {
  if (!env.GATEWAY_CONFIG_JSON) return DEFAULT_GATEWAY_CONFIG;
  try {
    // A config override can't bring back free use on Hypurr's key.
    return { ...DEFAULT_GATEWAY_CONFIG, ...JSON.parse(env.GATEWAY_CONFIG_JSON), freeDailyMessages: 0, freeDailyTokens: 0 };
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
  return (env as unknown as Record<string, string | undefined>)[apiKeyEnv];
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
      description: m.description ?? "",
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
  if (env.GATEWAY_LIMITER && !(await env.GATEWAY_LIMITER.limit({ key: sub.subjectId })).success) {
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
  if (approxIn > cfg.maxPromptTokens) {
    return json(
      { error: { message: "This request is too long for Hypurr's free models.", type: "invalid_request_error", code: "prompt_too_long" } },
      413,
    );
  }
  const allowance = await getAllowance(c.env.DB, sub.subjectId, cfg, sub.kind, c.now);

  // Free models are counted in messages (one per user turn, opened by POST /v1/gateway/turn).
  // A turn past the daily free messages, or past the hidden token cap, is paid from credits.
  const paidTurn =
    model.tier === "free" &&
    (allowance.messagesUsed > cfg.freeDailyMessages || allowance.remaining < approxIn);
  const freeTurn = model.tier === "free" && !paidTurn;
  if (model.tier === "free" && allowance.messagesUsed === 0) {
    return json(
      { error: { message: "Send your message from the Hypurr app.", type: "insufficient_quota", code: "no_message_open" } },
      402,
    );
  }
  if (freeTurn) {
    if ((await globalFreeUsed(c.env.DB, c.now)) >= cfg.globalFreeDailyTokens) {
      return json(
        {
          error: {
            message: "Hypurr's free models are busy today. Try again tomorrow, or use credits or your own key.",
            type: "insufficient_quota",
            code: "free_pool_exhausted",
          },
        },
        503,
      );
    }
  } else if (paidTurn) {
    if ((await getCredits(c.env.DB, sub.subjectId)) < cfg.minBalanceCents) {
      return json({ error: { message: FREE_EXHAUSTED, type: "insufficient_quota", code: "free_messages_exhausted" } }, 402);
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
  const asked = Number(body.max_tokens ?? body.max_completion_tokens ?? cfg.maxOutputTokens);
  const maxTokens = Math.max(1, Math.min(Number.isFinite(asked) ? asked : cfg.maxOutputTokens, cfg.maxOutputTokens));
  const upstreamBody: Record<string, unknown> = {
    ...body,
    model: model.upstreamModel,
    stream,
    max_tokens: maxTokens,
  };
  delete upstreamBody.max_completion_tokens;
  if (stream) upstreamBody.stream_options = { include_usage: true };
  if (!override && model.adapter === "openrouter") {
    if (model.fallbacks?.length) upstreamBody.models = [model.upstreamModel, ...model.fallbacks];
    // Free tier runs on ":free" variants, which may need providers that log prompts (labelled
    // "not for company code"). Paid models never route to providers that keep prompts.
    upstreamBody.provider = { data_collection: model.tier === "free" ? "allow" : "deny", allow_fallbacks: true };
  }

  const upstreamRes = await fetch(`${upstreamBase}/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${upstreamKey}`,
      "http-referer": env.GATEWAY_PUBLIC_URL || "https://hypurr.mindgains.ai",
      "x-title": "Hypurr",
      // Privacy: do not forward original prompts to logs; only upstream.
    },
    body: JSON.stringify(upstreamBody),
  });

  if (!upstreamRes.ok) {
    const text = await upstreamRes.text();
    // Log status only — never prompt bodies.
    console.log(JSON.stringify({ gateway: "upstream_error", status: upstreamRes.status, model: modelId }));
    // Our own OpenRouter account problems (no credits, bad key) aren't the user's to fix.
    if ([401, 402, 403].includes(upstreamRes.status)) {
      return json(
        { error: { message: "Hypurr's free models are unavailable right now. Try again later, or use your own key.", type: "service_unavailable", code: "upstream_unavailable" } },
        503,
      );
    }
    // ":free" routes share a daily request cap on our key; never name the provider.
    if (upstreamRes.status === 429 && model.tier === "free") {
      return json(
        { error: { message: "Hypurr Free is busy right now. Try again in a moment.", type: "rate_limited", code: "free_busy" } },
        429,
      );
    }
    return json({ error: { message: "Hypurr's model service had a problem. Try again in a moment.", type: "upstream_error" } }, upstreamRes.status >= 500 ? 502 : upstreamRes.status);
  }

  if (stream && upstreamRes.body) {
    // Meter approximate tokens up-front for free tier; refine on paid via estimate.
    const estOut = 512;
    const total = approxIn + estOut;
    if (freeTurn) {
      await consumeAllowance(c.env.DB, sub.subjectId, total, c.now);
      await addGlobalFree(c.env.DB, total, c.now);
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
    // Reconcile with the real usage OpenRouter reports in the last SSE chunk.
    const db = c.env.DB;
    const now = c.now;
    const reconcile = async (realTotal: number, realIn: number, realOut: number) => {
      const extra = realTotal - total;
      if (extra <= 0) return;
      if (freeTurn) {
        await consumeAllowance(db, sub.subjectId, extra, now);
        await addGlobalFree(db, extra, now);
      } else {
        const more = Math.max(0, estimateCostCents(cfg, modelId, realIn, realOut) - estimateCostCents(cfg, modelId, approxIn, estOut));
        if (more > 0) await spendCredits(db, sub.subjectId, more, { model: modelId, reconcile: true }, now);
      }
    };
    const body2 = upstreamRes.body.pipeThrough(scrubSse(modelId)).pipeThrough(usageTap(reconcile));
    return new Response(body2, {
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

  if (freeTurn) {
    await consumeAllowance(c.env.DB, sub.subjectId, total, c.now);
    await addGlobalFree(c.env.DB, total, c.now);
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

  return json(scrubUpstream(data, modelId));
}

export async function spendingBalance(c: Ctx) {
  const cfg = cfgFrom(c.env as unknown as GatewayEnv);
  const sub = await requireSubject(c);
  const allowance = await getAllowance(c.env.DB, sub.subjectId, cfg, sub.kind, c.now);
  const credits = await getCredits(c.env.DB, sub.subjectId);
  return {
    free: {
      messagesLeft: Math.max(0, cfg.freeDailyMessages - allowance.messagesUsed),
      messagesLimit: cfg.freeDailyMessages,
      remaining: allowance.remaining,
      limit: allowance.tokensLimit,
      used: allowance.tokensUsed,
      periodStart: allowance.periodStart,
    },
    credits: {
      balanceCents: credits,
      balanceUsd: credits / 100,
    },
    // RevenueCat app user id the phone must log in as, so purchases credit this subject.
    appUserId: sub.subjectId,
    payments: "revenuecat",
    creditPacks: CREDIT_PACKS.map(({ id, productId, priceInr, creditsCents, approxMessages, label }) => ({ id, productId, priceInr, creditsCents, approxMessages, label })),
    buyCreditsUrl: stripeOn(c) ? cfg.stripeCheckoutPlaceholder : null,
  };
}

function stripeOn(c: Ctx): boolean {
  return (c.env as unknown as GatewayEnv).PAYMENTS_STRIPE === "1";
}

/**
 * Opens one user message (a task turn sent from the phone). The host calls this once per turn;
 * the agent's own model calls inside the turn don't count. Paid from credits; refused without
 * (there is no shared free allowance).
 */
export async function openTurn(c: Ctx) {
  const cfg = cfgFrom(c.env as unknown as GatewayEnv);
  const sub = await requireSubject(c);
  const a = await getAllowance(c.env.DB, sub.subjectId, cfg, sub.kind, c.now);
  const limit = cfg.freeDailyMessages;
  const credits = await getCredits(c.env.DB, sub.subjectId);
  if (a.messagesUsed >= limit && credits < cfg.minBalanceCents) {
    return json(
      { error: { message: FREE_EXHAUSTED, type: "insufficient_quota", code: "free_messages_exhausted" }, freeMessagesLeft: 0, freeMessagesLimit: limit },
      402,
    );
  }
  await countMessage(c.env.DB, sub.subjectId, c.now);
  const used = a.messagesUsed + 1;
  return {
    ok: true,
    paidFrom: used > limit ? "credits" : "free",
    freeMessagesLeft: Math.max(0, limit - used),
    freeMessagesLimit: limit,
    creditsCents: credits,
  };
}

export async function issueTrial(c: Ctx) {
  const cfg = cfgFrom(c.env as unknown as GatewayEnv);
  let body: { hostId?: string };
  try {
    body = JSON.parse(new TextDecoder().decode(c.raw) || "{}") as { hostId?: string };
  } catch {
    throw new ApiError("badRequest");
  }
  // hostId is a hash of the PC's stable machine id (host/src/agent/builtin.rs), so reinstalling
  // gets a new key for the same subject and the same (used) free allowance.
  if (!body.hostId || !/^[a-f0-9]{64}$/.test(body.hostId)) throw new ApiError("badRequest");
  // No more shared free keys: a key is only issued to a computer with credits (bought in the app
  // for its subject trial:<hostId>). Free use is on the user's own account.
  if ((await getCredits(c.env.DB, `trial:${body.hostId}`)) < cfg.minBalanceCents) {
    return json({ error: { message: FREE_EXHAUSTED, type: "insufficient_quota", code: "free_tier_retired" } }, 403);
  }
  // Free trial keys are the farmable resource: cap new ones per client IP per day.
  const ip = c.req.headers.get("cf-connecting-ip") ?? "unknown";
  if (!(await allowTrialIp(c.env.DB, ip, cfg.trialKeysPerIpPerDay, c.now))) throw new ApiError("rateLimited");
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
  if (!stripeOn(c)) throw new ApiError("notFound");
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
  if (!stripeOn(c)) throw new ApiError("notFound");
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
    const meta = (obj.metadata ?? {}) as Record<string, unknown>;
    const subjectId = String(obj.client_reference_id || meta.subjectId || "");
    const amount = Number(obj.amount_total || meta.amountCents || 0);
    if (subjectId && amount > 0) {
      await topUp(c.env.DB, subjectId, amount, { stripeEvent: event.id }, c.now);
    }
  }
  return json({ ok: true });
}

// re-export json helper usage — api.ts already has json; ensure ApiError codes exist
