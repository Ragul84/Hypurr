import { AppleLogo, LinuxLogo, Terminal } from "@phosphor-icons/react/ssr";
import { Avatar } from "./device";
import GlowCard, { IconCookie } from "./glow-card";
import Reveal from "./reveal";

const roster = ["Ship room", "Pacer", "Reviewer", "Scout", "Relay"];

export default function Platforms() {
  return (
    <section className="px-4 py-24 sm:px-6 md:py-32">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <p className="font-medium text-secondary">Everywhere you work</p>
          <h2 className="mt-3 max-w-[40rem] font-display text-4xl font-bold tracking-tight text-on-surface md:text-5xl">
            Native on every screen. <span className="flow-text">One host behind them.</span>
          </h2>
          <p className="mt-6 max-w-[38rem] text-lg leading-relaxed text-on-surface-variant">
            One small Rust host runs your bots on macOS or Linux. SwiftUI on iPhone and Mac, GTK 4 on Linux and a
            terminal UI over SSH all show the same bots and chats. No web views, no Electron.
          </p>
        </Reveal>

        {/* Mac window mock */}
        <Reveal className="mt-14">
          <div className="glass-strong overflow-hidden rounded-[2rem]">
            <div className="flex items-center gap-2 px-5 py-3.5">
              <span className="size-3 rounded-full bg-[#ff5f57]" />
              <span className="size-3 rounded-full bg-[#febc2e]" />
              <span className="size-3 rounded-full bg-[#28c840]" />
              <span className="ml-4 text-sm font-medium text-on-surface-variant">Hypurr — Ship room</span>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-[14rem_1fr]">
              <aside className="hidden space-y-1 p-3 sm:block">
                {roster.map((r, i) => (
                  <div key={r} className={`flex items-center gap-2.5 rounded-2xl px-2.5 py-2 text-sm ${i === 0 ? "bg-primary-container text-on-primary-container" : "text-on-surface"}`}>
                    <Avatar name={r} size={26} />
                    <span className="font-medium">{r}</span>
                    {r === "Pacer" && <span className="purr ml-auto size-2 rounded-full bg-secondary" />}
                  </div>
                ))}
              </aside>
              <div className="space-y-3 bg-surface/40 p-5 sm:p-7">
                {[
                  { n: "Pacer", t: "The weekly totals look off for Sunday runs." },
                  { n: "Reviewer", t: "isoWeek uses local time; late-evening runs roll into the next week." },
                  { n: "Scout", t: "Fixed in src/pace.js, with a regression test. 42/42 passing." },
                ].map((m) => (
                  <div key={m.n} className="flex items-start gap-3">
                    <Avatar name={m.n} size={30} />
                    <div>
                      <p className="text-xs font-semibold text-on-surface">{m.n}</p>
                      <p className="mt-0.5 rounded-[1.1rem] rounded-tl-md bg-surface-container-high px-4 py-2 text-sm text-on-surface">{m.t}</p>
                    </div>
                  </div>
                ))}
                <div className="flex items-center gap-2 rounded-full bg-surface-container-high py-2 pr-2 pl-4 text-sm text-on-surface-variant">
                  <span className="flex-1">Message Ship room — @ to mention</span>
                  <span className="flow-bg grid size-8 place-items-center rounded-full text-white">↑</span>
                </div>
              </div>
            </div>
          </div>
        </Reveal>

        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-3">
          <Reveal>
            <GlowCard className="p-8">
              <IconCookie>
                <AppleLogo size={26} weight="fill" />
              </IconCookie>
              <h3 className="mt-6 font-display text-xl font-bold text-on-surface">iPhone & Mac</h3>
              <p className="mt-3 leading-relaxed text-on-surface-variant">
                A menu bar app with a native chat window; on iPhone, widgets, Live Activities and the Dynamic Island.
              </p>
            </GlowCard>
          </Reveal>
          <Reveal delay={0.05}>
            <GlowCard className="p-8">
              <IconCookie tone="tertiary">
                <LinuxLogo size={26} weight="fill" />
              </IconCookie>
              <h3 className="mt-6 font-display text-xl font-bold text-on-surface">Linux</h3>
              <p className="mt-3 leading-relaxed text-on-surface-variant">
                A GTK 4 / libadwaita app for the desktop, or just the host on a headless server or cloud VM.
              </p>
            </GlowCard>
          </Reveal>
          <Reveal delay={0.1}>
            <GlowCard className="p-0">
              <div className="p-8 pb-4">
                <IconCookie tone="secondary">
                  <Terminal size={26} weight="fill" />
                </IconCookie>
                <h3 className="mt-6 font-display text-xl font-bold text-on-surface">Terminal</h3>
              </div>
              <pre className="mx-4 mb-4 overflow-hidden rounded-2xl bg-[#0d0820] p-4 font-mono text-[11.5px] leading-relaxed text-[#e9e4ff]">
                <span className="flow-text font-bold">▌ hypurr</span> <span className="text-[#9b8fc7]">· 5 bots · connected</span>{"\n"}
                <span className="text-[#f472b6]">●</span> Pacer     <span className="text-[#f472b6]">needs you</span>{"\n"}
                <span className="text-[#22d3ee]">⠹</span> Reviewer  <span className="text-[#9b8fc7]">running tests…</span>{"\n"}
                <span className="text-[#a78bfa]">✓</span> Scout     <span className="text-[#9b8fc7]">done</span>
              </pre>
            </GlowCard>
          </Reveal>
        </div>
      </div>
    </section>
  );
}
