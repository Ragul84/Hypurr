import Link from "next/link";
import { GITHUB } from "../links";

export const metadata = { title: "IT approval kit · Hypurr" };

const pages = [
  ["data-flow", "Data flow, on one page", "What runs where, what leaves the laptop, encryption, no admin rights, folder scope, audit."],
  ["security-faq", "Security FAQ", "The usual questionnaire, answered."],
  ["allowlist", "Allowlist template", "Binaries, domains and ports, taken from the code."],
  ["signed-installer", "Signed installer", "How builds are signed and how to verify them."],
  ["local-only", "Local-only mode", "Enforced: no Hypurr cloud, LAN pairing only, approved model endpoints only."],
  ["audit-export", "Audit export format", "CSV / JSON lines, hash-chained, and how to check it is intact."],
];

export default function ItPage() {
  return (
    <main className="flex-1 flex flex-col items-center px-6 py-16">
      <article className="max-w-2xl w-full space-y-6">
        <h1 className="text-3xl font-bold text-on-surface">IT approval kit</h1>
        <p className="text-on-surface-variant leading-relaxed">
          Code stays on the laptop. Phone and laptop talk end-to-end encrypted. No admin rights. In the app,
          You → Share with IT gives the same pages with that computer&apos;s live settings.
        </p>
        <ul className="space-y-4">
          {pages.map(([id, title, text]) => (
            <li key={id}>
              <a href={`${GITHUB}/blob/main/docs/it-kit/${id}.md`} className="text-on-surface font-semibold underline">{title}</a>
              <p className="text-on-surface-variant text-sm">{text}</p>
            </li>
          ))}
        </ul>
        <p className="text-sm text-on-surface-variant">
          The team manager console (people, roles, folder rules, ₹ limits, audit log) is in the Android app today.
          A web console needs Hypurr cloud sync and is not available yet.
        </p>
        <Link href="/pricing" className="text-sm text-on-surface-variant hover:text-on-surface transition-colors">Pricing &rarr;</Link>
      </article>
    </main>
  );
}
