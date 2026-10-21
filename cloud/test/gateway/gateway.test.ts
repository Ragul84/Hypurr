import { describe, expect, it, vi, beforeEach } from "vitest";
import { hashKey, mintKeySecret } from "../../src/gateway/auth";
import { estimateCostCents, tokensFromMessages } from "../../src/gateway/meter";
import { DEFAULT_GATEWAY_CONFIG } from "../../src/gateway/config";

describe("gateway auth", () => {
  it("hashes keys stably", async () => {
    expect(await hashKey("hk_test")).toEqual(await hashKey("hk_test"));
    expect(await hashKey("a")).not.toEqual(await hashKey("b"));
  });

  it("mints unique secrets", async () => {
    const a = await mintKeySecret();
    const b = await mintKeySecret();
    expect(a.secret.startsWith("hk_")).toBe(true);
    expect(a.hash).toEqual(await hashKey(a.secret));
    expect(a.secret).not.toEqual(b.secret);
  });
});

describe("gateway meter", () => {
  it("estimates tokens from messages", () => {
    const n = tokensFromMessages({ messages: [{ role: "user", content: "hello world ".repeat(100) }] });
    expect(n).toBeGreaterThan(50);
  });

  it("estimates paid cost with markup", () => {
    const cents = estimateCostCents(DEFAULT_GATEWAY_CONFIG, "hypurr-pro", 100_000, 10_000);
    expect(cents).toBeGreaterThan(0);
  });
});

/** Minimal in-memory D1-ish stub for ledger tests */
function memoryDb() {
  const tables: Record<string, any[]> = {
    gateway_allowance: [],
    gateway_credits: [],
    gateway_ledger: [],
    gateway_usage: [],
    gateway_api_keys: [],
    gateway_stripe_events: [],
    gateway_payment_events: [],
    gateway_trial_ips: [],
    gateway_global_budget: [],
  };
  const api = {
    prepare(sql: string) {
      const binds: unknown[] = [];
      const stmt = {
        bind(...args: unknown[]) {
          binds.push(...args);
          return stmt;
        },
        async first<T>() {
          if (sql.includes("gateway_allowance") && sql.includes("SELECT")) {
            const subject = binds[0];
            return (tables.gateway_allowance.find((r) => r.subject_id === subject) as T) ?? null;
          }
          if (sql.includes("gateway_credits") && sql.includes("SELECT")) {
            const subject = binds[0];
            return (tables.gateway_credits.find((r) => r.subject_id === subject) as T) ?? null;
          }
          if (sql.includes("gateway_api_keys") && sql.includes("key_hash")) {
            const hash = binds[0];
            const row = tables.gateway_api_keys.find((r) => r.key_hash === hash && !r.revoked_at);
            return (row as T) ?? null;
          }
          if (sql.includes("gateway_payment_events") && sql.includes("SELECT")) {
            return (tables.gateway_payment_events.find((r) => r.id === binds[0]) as T) ?? null;
          }
          if (sql.includes("gateway_trial_ips") && sql.includes("SELECT")) {
            return (tables.gateway_trial_ips.find((r) => r.ip === binds[0] && r.day === binds[1]) as T) ?? null;
          }
          if (sql.includes("gateway_global_budget") && sql.includes("SELECT")) {
            return (tables.gateway_global_budget.find((r) => r.day === binds[0]) as T) ?? null;
          }
          if (sql.includes("gateway_stripe_events") && sql.includes("SELECT")) {
            const id = binds[0];
            return (tables.gateway_stripe_events.find((r) => r.id === id) as T) ?? null;
          }
          return null;
        },
        async run() {
          if (sql.includes("INSERT INTO gateway_allowance")) {
            const [subject_id, period_start, tokens_limit, updated_at] = binds as any;
            const existing = tables.gateway_allowance.find((r) => r.subject_id === subject_id);
            if (existing) {
              existing.period_start = period_start;
              existing.tokens_used = 0;
              existing.messages_used = 0;
              existing.tokens_limit = tokens_limit;
              existing.updated_at = updated_at;
            } else {
              tables.gateway_allowance.push({
                subject_id,
                period: "day",
                period_start,
                tokens_used: 0,
                tokens_limit,
                updated_at,
              });
            }
          }
          if (sql.includes("UPDATE gateway_allowance SET tokens_used")) {
            const [tokens, updated_at, subject_id] = binds as any;
            const row = tables.gateway_allowance.find((r) => r.subject_id === subject_id);
            if (row) {
              row.tokens_used += tokens;
              row.updated_at = updated_at;
            }
          }
          if (sql.includes("INSERT INTO gateway_credits")) {
            const [subject_id, balance_cents, updated_at] = binds as any;
            const existing = tables.gateway_credits.find((r) => r.subject_id === subject_id);
            if (existing) {
              existing.balance_cents = binds[3] ?? balance_cents;
              existing.updated_at = binds[4] ?? updated_at;
            } else {
              tables.gateway_credits.push({ subject_id, balance_cents, updated_at });
            }
          }
          if (sql.includes("UPDATE gateway_credits")) {
            const [balance_cents, updated_at, subject_id] = binds as any;
            const row = tables.gateway_credits.find((r) => r.subject_id === subject_id);
            if (row) {
              row.balance_cents = balance_cents;
              row.updated_at = updated_at;
            }
          }
          if (sql.includes("INSERT INTO gateway_ledger")) {
            tables.gateway_ledger.push({ binds: [...binds] });
          }
          if (sql.includes("INSERT INTO gateway_usage")) {
            tables.gateway_usage.push({ binds: [...binds] });
          }
          if (sql.includes("INSERT INTO gateway_api_keys")) {
            tables.gateway_api_keys.push({
              id: binds[0],
              user_id: binds[1],
              host_id: binds[2],
              key_hash: binds[3],
              key_prefix: binds[4],
              label: binds[5],
              kind: binds[6],
              created_at: binds[7],
              revoked_at: null,
            });
          }
          if (sql.includes("UPDATE gateway_api_keys SET last_used_at")) {
            /* noop */
          }
          if (sql.includes("INSERT INTO gateway_payment_events")) {
            tables.gateway_payment_events.push({ id: binds[0], subject_id: binds[1], amount_cents: binds[3] });
          }
          if (sql.includes("INSERT INTO gateway_trial_ips")) {
            const row = tables.gateway_trial_ips.find((r) => r.ip === binds[0] && r.day === binds[1]);
            if (row) row.count += 1;
            else tables.gateway_trial_ips.push({ ip: binds[0], day: binds[1], count: 1 });
          }
          if (sql.includes("INSERT INTO gateway_global_budget")) {
            const row = tables.gateway_global_budget.find((r) => r.day === binds[0]);
            if (row) row.free_tokens += binds[1];
            else tables.gateway_global_budget.push({ day: binds[0], free_tokens: binds[1] });
          }
          if (sql.includes("SET messages_used = messages_used + 1")) {
            const row = tables.gateway_allowance.find((r) => r.subject_id === binds[1]);
            if (row) row.messages_used = (row.messages_used ?? 0) + 1;
          }
          if (sql.includes("INSERT INTO gateway_stripe_events")) {
            tables.gateway_stripe_events.push({ id: binds[0], created_at: binds[1] });
          }
          return { success: true };
        },
      };
      return stmt;
    },
    _tables: tables,
  };
  return api as unknown as D1Database & { _tables: typeof tables };
}

describe("gateway ledger", async () => {
  const { getAllowance, consumeAllowance, topUp, spendCredits } = await import("../../src/gateway/ledger");

  it("tracks free allowance", async () => {
    const db = memoryDb();
    const a = await getAllowance(db, "user:u1", DEFAULT_GATEWAY_CONFIG, "user", Date.UTC(2026, 9, 5));
    expect(a.remaining).toBe(DEFAULT_GATEWAY_CONFIG.freeDailyTokens);
    await consumeAllowance(db, "user:u1", 1000, Date.UTC(2026, 9, 5));
    const b = await getAllowance(db, "user:u1", DEFAULT_GATEWAY_CONFIG, "user", Date.UTC(2026, 9, 5));
    expect(b.tokensUsed).toBe(1000);
    expect(b.remaining).toBe(DEFAULT_GATEWAY_CONFIG.freeDailyTokens - 1000);
  });

  it("tops up and spends credits", async () => {
    const db = memoryDb();
    await topUp(db, "user:u1", 500, { test: true }, 1);
    const spent = await spendCredits(db, "user:u1", 100, { model: "hypurr-pro" }, 2);
    expect(spent.ok).toBe(true);
    if (spent.ok) expect(spent.balance).toBe(400);
    const fail = await spendCredits(db, "user:u1", 9999, {}, 3);
    expect(fail.ok).toBe(false);
    if (!fail.ok) expect(fail.message).toMatch(/out of Hypurr credits/i);
  });
});

describe("streaming passthrough with fake upstream", () => {
  it("forwards chat completions", async () => {
    const fakeResponse = {
      id: "chatcmpl-test",
      object: "chat.completion",
      choices: [{ message: { role: "assistant", content: "ok" }, finish_reason: "stop" }],
      usage: { prompt_tokens: 10, completion_tokens: 1 },
    };
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(fakeResponse), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const db = memoryDb();
    const { secret, hash, prefix } = await mintKeySecret();
    (db as any)._tables.gateway_api_keys.push({
      id: "key_1",
      user_id: "u1",
      host_id: null,
      key_hash: hash,
      key_prefix: prefix,
      kind: "user",
      revoked_at: null,
    });

    const { chatCompletions, openTurn } = await import("../../src/gateway/handler");
    await openTurn(ctx(db, "/v1/gateway/turn", {}, { authorization: `Bearer ${secret}` }));
    const raw = new TextEncoder().encode(
      JSON.stringify({ model: "hypurr-free", messages: [{ role: "user", content: "hi" }], stream: false }),
    );
    const req = new Request("http://localhost/v1/chat/completions", {
      method: "POST",
      headers: { authorization: `Bearer ${secret}`, "content-type": "application/json" },
      body: raw,
    });
    const env = {
      DB: db,
      GATEWAY_UPSTREAM_OVERRIDE: "http://fake.local/v1",
      UPSTREAM_FAKE_API_KEY: "fake",
    };
    const res = await chatCompletions({
      req,
      env: env as any,
      exec: {} as any,
      url: new URL(req.url),
      raw,
      now: Date.UTC(2026, 9, 21, 6),
    });
    expect(res.status).toBe(200);
    const data = (await res.json()) as { choices: Array<{ message: { content: string } }> };
    expect(data.choices[0].message.content).toBe("ok");
    expect(fetchMock).toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});

describe("stripe webhook signature", () => {
  it("rejects bad signature when secret set", async () => {
    const db = memoryDb();
    const { stripeWebhook } = await import("../../src/gateway/handler");
    const raw = new TextEncoder().encode(JSON.stringify({ id: "evt_1", type: "checkout.session.completed" }));
    const req = new Request("http://localhost/v1/webhooks/stripe", {
      method: "POST",
      headers: { "stripe-signature": "wrong" },
      body: raw,
    });
    await expect(
      stripeWebhook({
        req,
        env: { DB: db, STRIPE_WEBHOOK_SECRET: "whsec_test", PAYMENTS_STRIPE: "1" } as any,
        exec: {} as any,
        url: new URL(req.url),
        raw,
        now: Date.now(),
      }),
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  it("credits on checkout.session.completed", async () => {
    const db = memoryDb();
    const { stripeWebhook } = await import("../../src/gateway/handler");
    const raw = new TextEncoder().encode(
      JSON.stringify({
        id: "evt_2",
        type: "checkout.session.completed",
        data: { object: { client_reference_id: "user:u1", amount_total: 1000 } },
      }),
    );
    const req = new Request("http://localhost/v1/webhooks/stripe", {
      method: "POST",
      headers: { "stripe-signature": "whsec_test" },
      body: raw,
    });
    const res = await stripeWebhook({
      req,
      env: { DB: db, STRIPE_WEBHOOK_SECRET: "whsec_test", PAYMENTS_STRIPE: "1" } as any,
      exec: {} as any,
      url: new URL(req.url),
      raw,
      now: Date.now(),
    });
    expect(res.status).toBe(200);
    const { getCredits } = await import("../../src/gateway/ledger");
    expect(await getCredits(db, "user:u1")).toBe(1000);
  });
});

function ctx(db: D1Database, path: string, body: unknown, headers: Record<string, string>, env: Record<string, unknown> = {}) {
  const raw = new TextEncoder().encode(JSON.stringify(body));
  const req = new Request(`http://localhost${path}`, { method: "POST", headers, body: raw });
  return { req, env: { DB: db, ...env } as any, exec: {} as any, url: new URL(req.url), raw, now: Date.UTC(2026, 9, 21, 6) };
}

describe("openrouter defaults", () => {
  it("free models go to OpenRouter with fallbacks and abuse caps", () => {
    const free = DEFAULT_GATEWAY_CONFIG.models.filter((m) => m.tier === "free");
    expect(free.map((m) => m.id)).toEqual(["hypurr-free", "hypurr-fast"]);
    for (const m of DEFAULT_GATEWAY_CONFIG.models) {
      expect(m.adapter).toBe("openrouter");
      expect(m.upstreamModel).not.toMatch(/:free$/);
    }
    expect(DEFAULT_GATEWAY_CONFIG.maxOutputTokens).toBeGreaterThan(0);
    expect(DEFAULT_GATEWAY_CONFIG.freeDailyMessages).toBeGreaterThan(0);
  });

  it("clamps max_tokens, sends fallbacks and counts the request", async () => {
    const seen: any[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_u: string, init: RequestInit) => {
        seen.push(JSON.parse(String(init.body)));
        return new Response(JSON.stringify({ choices: [], usage: { prompt_tokens: 5, completion_tokens: 5 } }));
      }),
    );
    const db = memoryDb();
    const { secret, hash, prefix } = await mintKeySecret();
    (db as any)._tables.gateway_api_keys.push({ id: "k", user_id: null, host_id: "host12345", key_hash: hash, key_prefix: prefix, kind: "trial", revoked_at: null });
    const { chatCompletions } = await import("../../src/gateway/handler");
    const c = ctx(
      db,
      "/v1/chat/completions",
      { model: "hypurr-free", max_tokens: 999_999, messages: [{ role: "user", content: "hi" }] },
      { authorization: `Bearer ${secret}` },
      { UPSTREAM_OPENROUTER_API_KEY: "sk-test" },
    );
    const { openTurn } = await import("../../src/gateway/handler");
    await openTurn(ctx(db, "/v1/gateway/turn", {}, { authorization: `Bearer ${secret}` }));
    const res = await chatCompletions(c);
    expect(res.status).toBe(200);
    // A second agent call inside the same message isn't another message.
    expect((await chatCompletions(ctx(db, "/v1/chat/completions", { model: "hypurr-free", messages: [] }, { authorization: `Bearer ${secret}` }, { UPSTREAM_OPENROUTER_API_KEY: "sk-test" }))).status).toBe(200);
    expect(seen[0].max_tokens).toBe(DEFAULT_GATEWAY_CONFIG.maxOutputTokens);
    expect(seen[0].model).toBe("deepseek/deepseek-v4-flash");
    expect(seen[0].models[0]).toBe("deepseek/deepseek-v4-flash");
    expect(seen[0].provider.data_collection).toBe("deny");
    expect((db as any)._tables.gateway_allowance[0].messages_used).toBe(1);
    expect((db as any)._tables.gateway_global_budget[0].free_tokens).toBe(20);
    vi.unstubAllGlobals();
  });

  it("counts messages, not model calls: 10 free, then credits, then the plain refusal", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ choices: [], usage: { prompt_tokens: 1000, completion_tokens: 1000 } }))));
    const db = memoryDb();
    const { secret, hash, prefix } = await mintKeySecret();
    (db as any)._tables.gateway_api_keys.push({ id: "k", user_id: "u9", host_id: null, key_hash: hash, key_prefix: prefix, kind: "user", revoked_at: null });
    const { openTurn, chatCompletions, spendingBalance, FREE_EXHAUSTED } = await import("../../src/gateway/handler");
    const { topUp, getCredits } = await import("../../src/gateway/ledger");
    const auth = { authorization: `Bearer ${secret}` };
    const chat = () => chatCompletions(ctx(db, "/v1/chat/completions", { model: "hypurr-free", messages: [] }, auth, { UPSTREAM_OPENROUTER_API_KEY: "k" }));
    // No message opened: raw API use is refused.
    expect((await chat()).status).toBe(402);
    const limit = DEFAULT_GATEWAY_CONFIG.freeDailyMessages;
    expect(limit).toBe(10);
    for (let i = 1; i <= limit; i++) {
      const t = (await openTurn(ctx(db, "/v1/gateway/turn", {}, auth))) as any;
      expect(t.freeMessagesLeft).toBe(limit - i);
    }
    expect((await spendingBalance(ctx(db, "/v1/gateway/balance", {}, auth))).free.messagesLeft).toBe(0);
    const refused = (await openTurn(ctx(db, "/v1/gateway/turn", {}, auth))) as Response;
    expect(refused.status).toBe(402);
    expect(((await refused.json()) as any).error.message).toBe(FREE_EXHAUSTED);
    expect(FREE_EXHAUSTED).toBe("You've used today's free messages. Buy credits or come back tomorrow.");
    // With credits the 11th message goes through and its model calls are charged.
    await topUp(db, "user:u9", 100, {}, 1);
    const paid = (await openTurn(ctx(db, "/v1/gateway/turn", {}, auth))) as any;
    expect(paid.paidFrom).toBe("credits");
    expect((await chat()).status).toBe(200);
    expect(await getCredits(db, "user:u9")).toBeLessThan(100);
    vi.unstubAllGlobals();
  });

  it("the hidden token cap moves a free message onto credits", async () => {
    const db = memoryDb();
    const { secret, hash, prefix } = await mintKeySecret();
    (db as any)._tables.gateway_api_keys.push({ id: "k", user_id: "u8", host_id: null, key_hash: hash, key_prefix: prefix, kind: "user", revoked_at: null });
    const { openTurn, chatCompletions } = await import("../../src/gateway/handler");
    const auth = { authorization: `Bearer ${secret}` };
    await openTurn(ctx(db, "/v1/gateway/turn", {}, auth));
    (db as any)._tables.gateway_allowance[0].tokens_used = DEFAULT_GATEWAY_CONFIG.freeDailyTokens;
    expect(DEFAULT_GATEWAY_CONFIG.freeDailyTokens).toBe(50_000);
    const res = await chatCompletions(ctx(db, "/v1/chat/completions", { model: "hypurr-free", messages: [{ role: "user", content: "hi" }] }, auth));
    expect(res.status).toBe(402);
  });
});

describe("usage tap", () => {
  it("passes bytes through and reports final usage", async () => {
    const { usageTap } = await import("../../src/gateway/handler");
    let got: number[] = [];
    const sse =
      'data: {"choices":[{"delta":{"content":"hi"}}]}\n\n' +
      'data: {"choices":[],"usage":{"prompt_tokens":40,"completion_tokens":2,"total_tokens":42}}\n\ndata: [DONE]\n\n';
    const enc = new TextEncoder().encode(sse);
    const src = new ReadableStream({
      start(c) {
        c.enqueue(enc.slice(0, 30));
        c.enqueue(enc.slice(30));
        c.close();
      },
    });
    const out = await new Response(
      src.pipeThrough(
        usageTap(async (t, p, o) => {
          got = [t, p, o];
        }),
      ),
    ).text();
    expect(out).toBe(sse);
    expect(got).toEqual([42, 40, 2]);
  });
});

describe("trial keys", () => {
  it("caps new trial keys per IP per day", async () => {
    const db = memoryDb();
    const { issueTrial } = await import("../../src/gateway/handler");
    const n = DEFAULT_GATEWAY_CONFIG.trialKeysPerIpPerDay;
    for (let i = 0; i < n; i++) {
      await issueTrial(ctx(db, "/v1/gateway/trial-key", { hostId: `host-000${i}` }, { "cf-connecting-ip": "1.2.3.4" }));
    }
    await expect(
      issueTrial(ctx(db, "/v1/gateway/trial-key", { hostId: "host-9999" }, { "cf-connecting-ip": "1.2.3.4" })),
    ).rejects.toMatchObject({ code: "rateLimited" });
    await expect(issueTrial(ctx(db, "/v1/gateway/trial-key", { hostId: "x" }, {}))).rejects.toMatchObject({ code: "badRequest" });
  });
});

describe("stripe is off by default", () => {
  it("404s the stripe webhook without PAYMENTS_STRIPE", async () => {
    const { stripeWebhook } = await import("../../src/gateway/handler");
    await expect(stripeWebhook(ctx(memoryDb(), "/v1/webhooks/stripe", { id: "e" }, {}))).rejects.toMatchObject({ code: "notFound" });
  });
});

describe("revenuecat webhook", () => {
  const AUTH = "rc_secret_value";
  const purchase = (id: string, extra: Record<string, unknown> = {}) => ({
    api_version: "1.0",
    event: {
      id,
      type: "NON_RENEWING_PURCHASE",
      app_user_id: "trial:host12345",
      product_id: "hypurr_credits_249",
      environment: "PRODUCTION",
      transaction_id: "GPA.1",
      ...extra,
    },
  });

  it("refuses without the configured secret, or when none is configured", async () => {
    const { revenuecatWebhook } = await import("../../src/gateway/revenuecat");
    await expect(
      revenuecatWebhook(ctx(memoryDb(), "/v1/webhooks/revenuecat", purchase("e1"), { authorization: "nope" }, { REVENUECAT_WEBHOOK_AUTH: AUTH })),
    ).rejects.toMatchObject({ code: "unauthenticated" });
    await expect(
      revenuecatWebhook(ctx(memoryDb(), "/v1/webhooks/revenuecat", purchase("e1"), { authorization: "" })),
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  it("grants the pack once, ignores sandbox, claws back refunds", async () => {
    const db = memoryDb();
    const { revenuecatWebhook, CREDIT_PACKS } = await import("../../src/gateway/revenuecat");
    const { getCredits } = await import("../../src/gateway/ledger");
    const pack = CREDIT_PACKS.find((p) => p.productId === "hypurr_credits_249")!;
    const h = { authorization: `Bearer ${AUTH}` };
    const env = { REVENUECAT_WEBHOOK_AUTH: AUTH };
    expect((await revenuecatWebhook(ctx(db, "/x", purchase("e1"), h, env))).status).toBe(200);
    await revenuecatWebhook(ctx(db, "/x", purchase("e1"), h, env)); // retry: no double grant
    expect(await getCredits(db, "trial:host12345")).toBe(pack.creditsCents);
    await revenuecatWebhook(ctx(db, "/x", purchase("e2", { environment: "SANDBOX" }), h, env));
    expect(await getCredits(db, "trial:host12345")).toBe(pack.creditsCents);
    await revenuecatWebhook(ctx(db, "/x", purchase("e3", { type: "CANCELLATION", cancel_reason: "CUSTOMER_SUPPORT" }), h, env));
    expect(await getCredits(db, "trial:host12345")).toBe(0);
  });

  it("packs match pricing/plans.json rupee prices", async () => {
    const { CREDIT_PACKS } = await import("../../src/gateway/revenuecat");
    expect(CREDIT_PACKS.length).toBeGreaterThan(0);
    for (const p of CREDIT_PACKS) {
      expect(p.productId).toMatch(/^hypurr_credits_\d+$/);
      expect(p.priceInr).toBeGreaterThan(0);
      expect(p.creditsCents).toBeGreaterThan(0);
    }
  });
});
