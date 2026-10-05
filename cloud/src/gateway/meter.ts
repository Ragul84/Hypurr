import type { GatewayConfig } from "./config";

export function estimateCostCents(
  cfg: GatewayConfig,
  modelId: string,
  promptTokens: number,
  completionTokens: number,
): number {
  const model = cfg.models.find((m) => m.id === modelId);
  const adapter = cfg.adapters.find((a) => a.id === (model?.adapter ?? ""));
  const markup = adapter?.markup ?? 1.2;
  const inRate = model?.assumeInputPerMTok ?? 1;
  const outRate = model?.assumeOutputPerMTok ?? 1;
  const usd = (promptTokens / 1_000_000) * inRate + (completionTokens / 1_000_000) * outRate;
  return Math.max(1, Math.ceil(usd * markup * 100)); // at least 1 cent when paid
}

export function tokensFromMessages(body: unknown): number {
  try {
    const msgs = (body as { messages?: Array<{ content?: unknown }> })?.messages ?? [];
    let n = 0;
    for (const m of msgs) {
      const c = m.content;
      if (typeof c === "string") n += Math.ceil(c.length / 4);
      else if (Array.isArray(c)) n += Math.ceil(JSON.stringify(c).length / 4);
    }
    return Math.max(1, n);
  } catch {
    return 256;
  }
}
