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
    adapter: string;
    /** Assumed USD per 1M input tokens (estimate for metering when upstream omits usage) */
    assumeInputPerMTok: number;
    assumeOutputPerMTok: number;
  }>;
  adapters: UpstreamAdapter[];
  /** Stripe Checkout placeholder URL template; {session} replaced when real */
  stripeCheckoutPlaceholder: string;
};

export const DEFAULT_GATEWAY_CONFIG: GatewayConfig = {
  freeDailyTokens: 200_000,
  freeMonthlyTokens: 2_000_000,
  trialDailyTokens: 50_000,
  rateLimitPerMinute: 30,
  minBalanceCents: 1,
  defaultModel: "hypurr-free",
  models: [
    {
      id: "hypurr-free",
      name: "Hypurr Free",
      tier: "free",
      upstreamModel: "openai/gpt-4o-mini",
      adapter: "openrouter",
      assumeInputPerMTok: 0.15,
      assumeOutputPerMTok: 0.6,
    },
    {
      id: "hypurr-fast",
      name: "Hypurr Fast",
      tier: "free",
      upstreamModel: "llama-3.1-8b-instant",
      adapter: "groq",
      assumeInputPerMTok: 0.05,
      assumeOutputPerMTok: 0.08,
    },
    {
      id: "hypurr-pro",
      name: "Hypurr Pro",
      tier: "paid",
      upstreamModel: "anthropic/claude-sonnet-4",
      adapter: "openrouter",
      assumeInputPerMTok: 3.0,
      assumeOutputPerMTok: 15.0,
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
      id: "groq",
      baseUrl: "https://api.groq.com/openai/v1",
      apiKeyEnv: "UPSTREAM_GROQ_API_KEY",
      markup: 1.2,
    },
    {
      id: "together",
      baseUrl: "https://api.together.xyz/v1",
      apiKeyEnv: "UPSTREAM_TOGETHER_API_KEY",
      markup: 1.2,
    },
    {
      id: "deepinfra",
      baseUrl: "https://api.deepinfra.com/v1/openai",
      apiKeyEnv: "UPSTREAM_DEEPINFRA_API_KEY",
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
  note: "Estimates for cheap open models via OpenRouter/Groq; real cost depends on upstream pricing and markup.",
};
