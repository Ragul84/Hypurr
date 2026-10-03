import { BellRinging, Brain, CalendarCheck, ChatsCircle, Desktop, GitBranch, LockKey, Microphone, UsersThree } from "@phosphor-icons/react/ssr";
import GlowCard, { IconCookie } from "./glow-card";
import { Avatar } from "./device";
import Reveal from "./reveal";

const small = [
  { icon: GitBranch, tone: "tertiary", title: "Threads on any message", body: "Branch off without cluttering the main chat; each thread is its own forked session." },
  { icon: Brain, tone: "primary", title: "Memory that sticks", body: "Per-bot memory and standing instructions carry across sessions and compactions." },
  { icon: CalendarCheck, tone: "secondary", title: "Routines", body: "Run a bot on a schedule or a webhook and get the result as a message." },
  { icon: Desktop, tone: "tertiary", title: "Remote screen", body: "Watch and control your computer from the phone over WebRTC — bots can use it too." },
  { icon: Microphone, tone: "secondary", title: "Voice calls", body: "Talk to a bot hands-free and hear its replies read aloud." },
  { icon: UsersThree, tone: "primary", title: "Bots helping bots", body: "“Ask Reviewer to check this.” Bots find each other and wait for a reply." },
] as const;

export default function Bento() {
  return (
    <section id="features" className="scroll-mt-24 px-4 py-24 sm:px-6 md:py-32">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <p className="font-medium text-secondary">Features</p>
          <h2 className="mt-3 max-w-[40rem] font-display text-4xl font-bold tracking-tight text-on-surface md:text-5xl">
            A whole team of bots, <span className="flow-text">on your own computer.</span>
          </h2>
        </Reveal>

        <div className="mt-14 grid grid-cols-1 gap-4 md:grid-cols-6">
          <Reveal className="md:col-span-4 md:row-span-2">
            <GlowCard className="p-8 md:p-10">
              <IconCookie>
                <ChatsCircle size={28} weight="fill" />
              </IconCookie>
              <h3 className="mt-6 font-display text-2xl font-bold text-on-surface">Put bots in a room</h3>
              <p className="mt-3 max-w-md leading-relaxed text-on-surface-variant">
                Start a group chat and every member answers in its own session. They read each other, disagree and hand
                work back. Mention one with @ to ask just that bot.
              </p>
              <div className="mt-8 space-y-3">
                {[
                  { n: "Reviewer", t: "Two issues: the week boundary and a missing null check. @Scout?" },
                  { n: "Scout", t: "On it — guard added, tests green." },
                  { n: "Pacer", t: "Merged into the weekly report 🚀" },
                ].map((m) => (
                  <div key={m.n} className="flex items-start gap-3">
                    <Avatar name={m.n} size={32} />
                    <div className="rounded-[1.25rem] rounded-tl-md bg-surface-container-high px-4 py-2.5 text-sm text-on-surface">
                      <span className="font-semibold">{m.n}</span>
                      <span className="text-on-surface-variant"> · </span>
                      {m.t}
                    </div>
                  </div>
                ))}
              </div>
            </GlowCard>
          </Reveal>

          <Reveal className="md:col-span-2" delay={0.05}>
            <GlowCard className="p-8">
              <IconCookie tone="secondary">
                <BellRinging size={28} weight="fill" />
              </IconCookie>
              <h3 className="mt-6 font-display text-xl font-bold text-on-surface">Only the pings that matter</h3>
              <p className="mt-3 leading-relaxed text-on-surface-variant">
                “Needs you” and “done”. Commands and edits arrive as cards: allow once, always allow or deny.
              </p>
            </GlowCard>
          </Reveal>

          <Reveal className="md:col-span-2" delay={0.1}>
            <GlowCard className="p-8">
              <IconCookie tone="tertiary">
                <LockKey size={28} weight="fill" />
              </IconCookie>
              <h3 className="mt-6 font-display text-xl font-bold text-on-surface">Your code stays home</h3>
              <p className="mt-3 leading-relaxed text-on-surface-variant">
                Agents run on your machine with your own logins. The phone reaches it end-to-end encrypted.
              </p>
            </GlowCard>
          </Reveal>

          {small.map(({ icon: Icon, tone, title, body }, i) => (
            <Reveal key={title} className="md:col-span-2" delay={0.04 * (i % 3)}>
              <GlowCard className="p-8">
                <IconCookie tone={tone}>
                  <Icon size={26} weight="fill" />
                </IconCookie>
                <h3 className="mt-6 font-display text-xl font-bold text-on-surface">{title}</h3>
                <p className="mt-3 leading-relaxed text-on-surface-variant">{body}</p>
              </GlowCard>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}
