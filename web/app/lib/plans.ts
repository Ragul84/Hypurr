// Reads the one pricing config, ../pricing/plans.json, at build time (server only).
// Turbopack cannot import files outside web/, so it is read from disk instead of imported.
import { readFileSync } from "node:fs";
import path from "node:path";

export type Plan = {
  id: string;
  name: string;
  priceInr: number | null;
  per: string;
  tasksPerDay: number | null;
  summary: string;
  features: string[];
};

export type Pricing = { currency: string; symbol: string; gstNote: string; plans: Plan[] };

export function loadPricing(): Pricing {
  const file = path.join(process.cwd(), "..", "pricing", "plans.json");
  const raw = JSON.parse(readFileSync(file, "utf8"));
  // Payment keys never reach the page.
  return { currency: raw.currency, symbol: raw.symbol, gstNote: raw.gstNote, plans: raw.plans };
}

/** ₹1,23,456: whole rupees, Indian digit grouping. */
export function rupees(n: number): string {
  return "₹" + Math.round(n).toLocaleString("en-IN");
}

export function price(p: Plan): string {
  if (p.priceInr === null) return "Price to be announced";
  if (p.priceInr <= 0) return "Free";
  return `${rupees(p.priceInr)} / ${p.per}`;
}
