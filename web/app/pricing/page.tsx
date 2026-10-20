import Link from "next/link";
import { loadPricing, price } from "../lib/plans";

export const metadata = { title: "Pricing · Hypurr" };

export default function PricingPage() {
  const pricing = loadPricing();
  return (
    <main className="flex-1 flex flex-col items-center px-6 py-16">
      <article className="max-w-4xl w-full space-y-10">
        <header className="space-y-2">
          <h1 className="text-3xl font-bold text-on-surface">Pricing</h1>
          <p className="text-on-surface-variant">Prices in Indian rupees ({pricing.currency}).</p>
        </header>
        <div className="grid gap-4 md:grid-cols-3">
          {pricing.plans.map((p) => (
            <section key={p.id} className="rounded-3xl border border-outline-variant p-6 space-y-4">
              <h2 className="text-xl font-semibold text-on-surface">{p.name}</h2>
              <p className={p.priceInr === null ? "text-on-surface-variant" : "text-2xl font-semibold text-on-surface"}>{price(p)}</p>
              <p className="text-on-surface-variant text-sm">{p.summary}</p>
              <ul className="space-y-2 text-sm text-on-surface-variant">
                {p.features.map((f) => (
                  <li key={f}>✓ {f}</li>
                ))}
              </ul>
            </section>
          ))}
        </div>
        <p className="text-sm text-on-surface-variant">{pricing.gstNote}</p>
        <p className="text-sm text-on-surface-variant">
          Rolling Hypurr out at work? See the <Link href="/it" className="text-on-surface underline">IT approval kit</Link>.
        </p>
        <Link href="/" className="text-sm text-on-surface-variant hover:text-on-surface transition-colors">&larr; Back to home</Link>
      </article>
    </main>
  );
}
