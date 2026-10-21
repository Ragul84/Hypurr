/** Gateway admin / runtime config. Secrets come from Worker env — never hardcode keys. */

export type UpstreamAdapter = {
  /** Logical name, e.g. openrouter | groq | together | deepinfra | fake */
  id: string;
  /** OpenAI-compatible base URL including /v1 */
  baseUrl: string;
  /** Env binding name that holds the upstream API key */
  apiKeyEnv: string;
  /** Markup multiplier on upstream USD cost (1.0 = pass-through). */
  markup: number;
};

export type GatewayConfig = {
  freeDailyTokens: number;
  /** Free user messages (phone turns) per subject per UTC day. The product's free allowance. */
  freeDailyMessages: number;
  /** Abuse limits: max_tokens is clamped to this on every request. */
  maxOutputTokens: number;
  /** Abuse limits: a single request's prompt can't exceed this many (approx) tokens. */
  maxPromptTokens: number;
  /** Abuse limits: new trial keys per client IP per UTC day. */
  trialKeysPerIpPerDay: number;
  /** Kill switch: free tokens served per UTC day across everyone (caps the OpenRouter bill). */
  globalFreeDailyTokens: number;
  freeMonthlyTokens: number;
  trialDailyTokens: number;
  /** Max requests per minute per subject */
  rateLimitPerMinute: number;
  /** USD cents refused below this for paid models */
  minBalanceCents: number;
  defaultModel: string;
  models: Array<{
    id: string;
    name: string;
    /** free | paid */
    tier: "free" | "paid";
    upstreamModel: string;
    /** OpenRouter fallbacks tried in order when the primary is down or rate-limited. */
    fallbacks?: string[];
    adapter: string;
    /** Assumed USD per 1M input tokens (estimate for metering when upstream omits usage) */
    assumeInputPerMTok: number;
    assumeOutputPerMTok: number;
  }>;
  adapters: UpstreamAdapter[];
  /** Stripe Checkout placeholder URL template; {session} replaced when real */
  stripeCheckoutPlaceholder: string;
};

// Upstream model ids verified against https://openrouter.ai/api/v1/models on 2026-10-21.
// Paid-per-token (not ":free") variants on purpose: ":free" routes are heavily rate-limited and
// may log prompts. All of these support tool calls, which Hypurr Agent needs.
export const DEFAULT_GATEWAY_CONFIG: GatewayConfig = {
  // Hidden safety cap behind the 10 messages; past it a turn needs credits.
  freeDailyTokens: 50_000,
  freeDailyMessages: 10,
  maxOutputTokens: 8_192,
  maxPromptTokens: 120_000,
  trialKeysPerIpPerDay: 5,
  // Kill switch ≈ US$1/day: agent turns are input-heavy, so hypurr-free blends to about
  // $0.12 per 1M tokens (incl. markup) → ~8M tokens.
  globalFreeDailyTokens: 8_000_000,
  freeMonthlyTokens: 2_000_000,
  trialDailyTokens: 50_000,
  rateLimitPerMinute: 20,
  minBalanceCents: 1,
  defaultModel: "hypurr-free",
  models: [
    {
      id: "hypurr-free",
      name: "Hypurr Free",
      tier: "free",
      // ~$0.09 in / $0.18 out per 1M tokens, 1M context.
      upstreamModel: "deepseek/deepseek-v4-flash",
      fallbacks: ["z-ai/glm-5.3-flash", "qwen/qwen3.7-flash"],
      adapter: "openrouter",
      assumeInputPerMTok: 0.091,
      assumeOutputPerMTok: 0.182,
    },
    {
      id: "hypurr-fast",
      name: "Hypurr Fast",
      tier: "free",
      // Small coding model; ~$0.07 in / $0.28 out per 1M tokens.
      upstreamModel: "qwen/qwen3-coder-30b-a3b-instruct",
      fallbacks: ["openai/gpt-oss-20b"],
      adapter: "openrouter",
      assumeInputPerMTok: 0.07,
      assumeOutputPerMTok: 0.28,
    },
    {
      id: "hypurr-pro",
      name: "Hypurr Pro",
      tier: "paid",
      upstreamModel: "qwen/qwen3-coder",
      fallbacks: ["deepseek/deepseek-v3.2"],
      adapter: "openrouter",
      assumeInputPerMTok: 0.3,
      assumeOutputPerMTok: 1.0,
    },
  ],
  adapters: [
    {
      id: "openrouter",
      baseUrl: "https://openrouter.ai/api/v1",
      apiKeyEnv: "UPSTREAM_OPENROUTER_API_KEY",
      markup: 1.2,
    },
    {
      id: "fake",
      baseUrl: "http://127.0.0.1:9/v1",
      apiKeyEnv: "UPSTREAM_FAKE_API_KEY",
      markup: 1.0,
    },
  ],
  stripeCheckoutPlaceholder: "https://checkout.stripe.com/c/pay/cs_test_placeholder",
};

/** Assumptions labelled for product docs (not guarantees). */
export const FREE_TIER_COST_ASSUMPTIONS = {
  label: "assumptions",
  perTaskTokensEstimate: 8_000,
  freeModelAssumeUsdPerTask: 0.002,
  dailyBudgetTokens: DEFAULT_GATEWAY_CONFIG.freeDailyTokens,
  approxTasksPerDay: Math.floor(DEFAULT_GATEWAY_CONFIG.freeDailyTokens / 8_000),
  note: "Estimates for cheap open models via OpenRouter; real cost depends on upstream pricing and markup.",
};
