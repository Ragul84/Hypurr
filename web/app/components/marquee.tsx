const agents = ["Claude Code", "Codex", "Cursor", "Gemini", "Copilot", "OpenCode", "Pi", "Grok Build", "Goose", "Kiro", "Qwen Code", "Junie", "Cline", "Amp", "Kimi", "Mistral Vibe"];

// Infinite agent ribbon; the list is doubled so the loop is seamless.
export default function Marquee() {
  return (
    <section aria-label="Supported agents" className="relative py-10">
      <div className="overflow-hidden [mask-image:linear-gradient(90deg,transparent,black_12%,black_88%,transparent)]">
        <ul className="marquee flex w-max gap-3">
          {[...agents, ...agents].map((a, i) => (
            <li key={i} aria-hidden={i >= agents.length} className="glass rounded-full px-5 py-2.5 text-sm font-medium whitespace-nowrap text-on-surface">
              {a}
            </li>
          ))}
        </ul>
      </div>
      <p className="mt-4 text-center text-sm text-on-surface-variant">…and every other agent in the ACP registry.</p>
    </section>
  );
}
