import Link from "next/link";

export default function Privacy() {
  return (
    <main className="flex-1 flex flex-col items-center px-6 py-16">
      <article className="max-w-2xl w-full space-y-6">
        <h1 className="text-3xl font-bold text-on-surface">Privacy Policy</h1>
        <p className="text-on-surface-variant text-sm">Last updated: September 25, 2026</p>

        <Section title="Overview">
          Hypurr lets you message the coding agents that run on your own computer. It is built so that your code, conversations and credentials stay on your devices.
        </Section>

        <Section title="What stays on your devices">
          <ul className="list-disc pl-5 space-y-2">
            <li><strong>Bots and conversations</strong> are stored by the Hypurr host on your computer (in <code>~/.hypurr</code>). Your phone keeps a cache so the app opens instantly.</li>
            <li><strong>Your phone talks directly to your computer</strong> over your local network or Tailscale. There is no Hypurr server in between and no Hypurr account.</li>
            <li><strong>Agents run with your own logins</strong> (Claude Code, Codex, Cursor and others). Hypurr never sees or stores their credentials.</li>
            <li><strong>Usage limits</strong> are read locally from the agents installed on your computer. Only percentages reach your phone.</li>
          </ul>
        </Section>

        <Section title="Push notifications">
          To notify you when a bot needs you or finishes, your computer sends a short alert (the bot&apos;s name and a one-line preview) through our push relay, a Cloudflare Worker that forwards it to Apple Push Notification service. The relay does not store notifications. Your device token is encrypted into a ticket that only the relay can read; your computer never sees the raw token.
        </Section>

        <Section title="Data we collect">
          None. Hypurr has no analytics, no tracking and no advertising identifiers.
        </Section>

        <Section title="Third-party services">
          <ul className="list-disc pl-5 space-y-2">
            <li><strong>Apple Push Notification service</strong>: delivers notifications and Live Activity updates.</li>
            <li><strong>Cloudflare Workers</strong>: runs the push relay (nothing stored).</li>
            <li><strong>The coding agents you choose</strong>: they run on your computer under their own terms and privacy policies.</li>
          </ul>
        </Section>

        <Section title="Data retention">
          Everything lives on your devices. Delete a bot to remove its conversation; uninstall the host and delete <code>~/.hypurr</code> to remove all of it. Deleting the iPhone app removes its cache.
        </Section>

        <Section title="Children's privacy">
          Hypurr is not directed at children under the age of 13.
        </Section>

        <Section title="Changes">
          We may update this policy. Changes will be posted on this page with a new date.
        </Section>

        <Section title="Contact">
          Questions? Open an issue at{" "}
          <a href="https://github.com/Ragul84/Hypurr/issues" className="text-on-surface underline">github.com/Ragul84/Hypurr/issues</a>.
        </Section>

        <div className="pt-4">
          <p className="text-on-surface-variant">
            <Link href="/terms" className="text-on-surface underline">Terms of Use</Link>
          </p>
        </div>

        <div className="pt-4">
          <Link href="/" className="text-sm text-on-surface-variant hover:text-on-surface transition-colors">
            &larr; Back to home
          </Link>
        </div>
      </article>
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="text-xl font-semibold text-on-surface mb-2">{title}</h2>
      <div className="text-on-surface-variant leading-relaxed">{children}</div>
    </section>
  );
}
