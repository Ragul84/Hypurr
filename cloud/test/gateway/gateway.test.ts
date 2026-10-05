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

    const { chatCompletions } = await import("../../src/gateway/handler");
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
      now: Date.UTC(2026, 9, 5, 12),
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
        env: { DB: db, STRIPE_WEBHOOK_SECRET: "whsec_test" } as any,
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
      env: { DB: db, STRIPE_WEBHOOK_SECRET: "whsec_test" } as any,
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
