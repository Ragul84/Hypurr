import { BellRinging, LockKey } from "@phosphor-icons/react/ssr";
import Phone from "./phone";
import Reveal from "./reveal";

const agents = ["Claude Code", "Codex", "Cursor", "Gemini", "Copilot", "OpenCode", "Pi", "Grok Build"];

// Radius rule for the page: buttons and chips are pills, panels are rounded-3xl, code is rounded-xl.
export function Answers() {
  return (
    <section className="px-4 py-20 sm:px-6 md:py-28">
      <div className="mx-auto grid max-w-6xl grid-cols-1 items-center gap-12 md:grid-cols-[1fr_1.1fr] md:gap-20">
        <Reveal className="order-2 mx-auto w-full max-w-[17rem] md:order-1 md:max-w-[20rem]">
          <Phone src="/screens/answer.webp" alt="Scout's reply: it added weeklyTotals to src/pace.js with a test, and all tests pass" />
        </Reveal>
        <Reveal className="order-1 md:order-2" delay={0.05}>
          <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-4xl">
            The answer, without the noise.
          </h2>
          <p className="mt-5 max-w-[32rem] text-lg leading-relaxed text-neutral-400">
            Each bot keeps one ongoing chat. You see its final reply for every turn; tool calls, thoughts and plans
            wait in the full conversation when you want them.
          </p>
        </Reveal>
      </div>
    </section>
  );
}

export function Features() {
  return (
    <section className="px-4 py-20 sm:px-6 md:py-28">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <h2 className="max-w-[36rem] text-3xl font-semibold tracking-tight text-neutral-50 md:text-4xl">
            A team of bots on your own computer.
          </h2>
        </Reveal>

        <div className="mt-12 grid grid-cols-1 gap-4 md:grid-cols-3 md:grid-rows-[auto_auto]">
          <Reveal className="md:col-span-2 md:row-span-2">
            <div className="flex h-full flex-col overflow-hidden rounded-3xl bg-gradient-to-br from-orange-500/15 via-neutral-900 to-neutral-900 md:flex-row">
              <div className="p-8 md:w-1/2 md:p-10">
                <h3 className="text-xl font-semibold text-neutral-50">Put bots in a room</h3>
                <p className="mt-3 leading-relaxed text-neutral-400">
                  Start a group chat and every member answers in its own session. They read each other, disagree
                  and hand work back. Mention one with @ to ask just that bot.
                </p>
              </div>
              <div className="relative h-80 md:h-auto md:w-1/2">
                <div className="absolute inset-x-8 top-0 md:inset-x-6 md:top-10">
                  <Phone src="/screens/group.webp" alt="Ship room group chat: Reviewer flags two issues and asks Scout, who adds a guard" />
                </div>
              </div>
            </div>
          </Reveal>

          <Reveal delay={0.05}>
            <div className="h-full rounded-3xl bg-neutral-900 p-8">
              <BellRinging size={26} className="text-orange-400" />
              <h3 className="mt-5 text-xl font-semibold text-neutral-50">Only the pings that matter</h3>
              <p className="mt-3 leading-relaxed text-neutral-400">
                A notification when a bot needs you or finishes. Commands and edits arrive as cards: allow once,
                always allow or deny.
              </p>
            </div>
          </Reveal>

          <Reveal delay={0.1}>
            <div className="h-full rounded-3xl bg-orange-500/10 p-8">
              <LockKey size={26} className="text-orange-400" />
              <h3 className="mt-5 text-xl font-semibold text-neutral-50">Your code stays home</h3>
              <p className="mt-3 leading-relaxed text-neutral-400">
                Agents run on your machine with your own logins. The phone reaches it end-to-end encrypted.
              </p>
            </div>
          </Reveal>

          <Reveal className="md:col-span-3" delay={0.05}>
            <div className="flex flex-col gap-6 rounded-3xl bg-neutral-900 p-8 md:flex-row md:items-center md:justify-between md:p-10">
              <div className="max-w-[26rem]">
                <h3 className="text-xl font-semibold text-neutral-50">Every agent you already use</h3>
                <p className="mt-3 leading-relaxed text-neutral-400">
                  Installed agents are found automatically. Anything else in the ACP registry is fetched on first
                  use, and each bot picks its own.
                </p>
              </div>
              <ul className="flex max-w-[34rem] flex-wrap gap-2">
                {agents.map((a) => (
                  <li key={a} className="rounded-full bg-neutral-800 px-4 py-2 text-sm text-neutral-200">
                    {a}
                  </li>
                ))}
                <li className="rounded-full px-4 py-2 text-sm text-neutral-500">and about 40 more</li>
              </ul>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}
