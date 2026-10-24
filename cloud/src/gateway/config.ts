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
  /** Free allowance is a lifetime total per subject (true), not per UTC day. */
  freeLifetime: boolean;
  /** Free user messages (phone turns) per subject, ever (see freeLifetime). The product's free allowance. */
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
    /** User-facing one-liner shown under the model name. Never mention upstream providers. */
    description?: string;
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
// Free tiers use ":free" variants (zero cost; shared daily request cap on our key; some providers
// may log prompts, hence the "not for company code" label). Pro stays paid with strict privacy.
// All of these support tool calls, which Hypurr Agent needs.
export const DEFAULT_GATEWAY_CONFIG: GatewayConfig = {
  freeLifetime: true,
  // Hidden lifetime safety cap behind the 10 messages; past it a turn needs credits.
  freeDailyTokens: 300_000,
  freeDailyMessages: 10,
  maxOutputTokens: 8_192,
  maxPromptTokens: 120_000,
  trialKeysPerIpPerDay: 2,
  // Kill switch ≈ US$1/day: agent turns are input-heavy, so hypurr-free blends to about
  // $0.12 per 1M tokens (incl. markup) → ~8M tokens.
  globalFreeDailyTokens: 8_000_000,
  freeMonthlyTokens: 2_000_000,
  trialDailyTokens: 300_000,
  rateLimitPerMinute: 20,
  minBalanceCents: 1,
  defaultModel: "hypurr-free",
  models: [
    {
      id: "hypurr-free",
      name: "Hypurr Free",
      description: "For trying out, not for company code",
      tier: "free",
      upstreamModel: "cohere/north-mini-code:free",
      fallbacks: ["poolside/laguna-s-2.1:free", "google/gemma-4-31b-it:free"],
      adapter: "openrouter",
      assumeInputPerMTok: 0,
      assumeOutputPerMTok: 0,
    },
    {
      id: "hypurr-fast",
      name: "Hypurr Fast",
      description: "Quick replies. For trying out, not for company code",
      tier: "free",
      upstreamModel: "nvidia/nemotron-3.5-lightning:free",
      fallbacks: ["poolside/laguna-xs-2.1:free"],
      adapter: "openrouter",
      assumeInputPerMTok: 0,
      assumeOutputPerMTok: 0,
    },
    {
      id: "hypurr-pro",
      name: "Hypurr Pro",
      description: "Stronger model. Private: providers can't keep your prompts",
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
