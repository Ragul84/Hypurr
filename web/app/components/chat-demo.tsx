"use client";

import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion, useInView, useReducedMotion } from "framer-motion";
import { Avatar, PhoneFrame } from "./device";
import Reveal from "./reveal";
import { springBouncy } from "./motion";

type Msg = { me?: boolean; who?: string; text: string };
const script: Msg[] = [
  { me: true, text: "Late-evening runs land in the wrong week. Can you fix it?" },
  { who: "Scout", text: "Found it: isoWeek used local time. I switched it to UTC and added weeklyTotals with a test." },
  { who: "Scout", text: "All 42 tests pass ✓  Want me to ask Reviewer for a look?" },
  { me: true, text: "Yes please 🙏" },
];

// A looping chat that plays when visible: typing dots, then each reply springs in.
export default function ChatDemo() {
  const ref = useRef<HTMLDivElement>(null);
  const inView = useInView(ref, { amount: 0.4 });
  const reduce = useReducedMotion();
  const [shown, setShown] = useState(reduce ? script.length : 0);
  const [typing, setTyping] = useState(false);

  useEffect(() => {
    if (reduce || !inView) return;
    let n = shown;
    const timers: ReturnType<typeof setTimeout>[] = [];
    const step = () => {
      if (n >= script.length) {
        timers.push(setTimeout(() => { n = 0; setShown(0); step(); }, 3200));
        return;
      }
      const next = script[n];
      if (!next.me) {
        setTyping(true);
        timers.push(setTimeout(() => { setTyping(false); n += 1; setShown(n); timers.push(setTimeout(step, 900)); }, 1300));
      } else {
        timers.push(setTimeout(() => { n += 1; setShown(n); step(); }, 800));
      }
    };
    step();
    return () => timers.forEach(clearTimeout);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inView, reduce]);

  return (
    <section className="px-4 py-24 sm:px-6 md:py-32">
      <div className="mx-auto grid max-w-6xl grid-cols-1 items-center gap-14 md:grid-cols-[1fr_1.1fr] md:gap-20">
        <Reveal className="order-2 mx-auto w-full max-w-[22rem] md:order-1">
          <div ref={ref}>
            <PhoneFrame>
              <div className="flex items-center gap-3 border-b border-outline-variant/40 px-5 pb-3">
                <Avatar name="Scout" size={34} />
                <div>
                  <p className="text-sm font-semibold text-on-surface">Scout</p>
                  <p className="text-[11px] text-tertiary">Claude Code · pace-app</p>
                </div>
              </div>
              <div className="flex h-[25rem] flex-col justify-end gap-2.5 px-4 py-4">
                <AnimatePresence initial={false}>
                  {script.slice(0, shown).map((m, i) => (
                    <motion.div
                      key={i}
                      layout
                      initial={{ opacity: 0, y: 20, scale: 0.85 }}
                      animate={{ opacity: 1, y: 0, scale: 1 }}
                      exit={{ opacity: 0, scale: 0.9 }}
                      transition={springBouncy}
                      style={{ originX: m.me ? 1 : 0 }}
                      className={`max-w-[82%] px-4 py-2.5 text-[13.5px] leading-snug ${
                        m.me
                          ? "self-end rounded-[1.4rem] rounded-br-md bg-primary text-on-primary"
                          : "self-start rounded-[1.4rem] rounded-bl-md bg-surface-container-high text-on-surface"
                      }`}
                    >
                      {m.text}
                    </motion.div>
                  ))}
                  {typing && (
                    <motion.div
                      key="typing"
                      layout
                      initial={{ opacity: 0, scale: 0.6 }}
                      animate={{ opacity: 1, scale: 1 }}
                      exit={{ opacity: 0, scale: 0.6 }}
                      transition={springBouncy}
                      className="typing flex gap-1 self-start rounded-full bg-surface-container-high px-4 py-3"
                    >
                      <span className="size-1.5 rounded-full bg-primary" />
                      <span className="size-1.5 rounded-full bg-secondary" />
                      <span className="size-1.5 rounded-full bg-tertiary" />
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
              <div className="m-3 mt-0 flex items-center gap-2 rounded-full bg-surface-container-high py-2 pr-2 pl-4 text-[13px] text-on-surface-variant">
                <span className="flex-1">Message Scout</span>
                <span className="flow-bg grid size-8 place-items-center rounded-full text-white">↑</span>
              </div>
            </PhoneFrame>
          </div>
        </Reveal>
        <Reveal className="order-1 md:order-2" delay={0.05}>
          <p className="font-medium text-secondary">One chat per bot</p>
          <h2 className="mt-3 font-display text-4xl font-bold tracking-tight text-on-surface md:text-5xl">
            The answer, <span className="flow-text">without the noise.</span>
          </h2>
          <p className="mt-6 max-w-[32rem] text-lg leading-relaxed text-on-surface-variant">
            Each bot keeps one ongoing chat. You see its final reply for every turn; tool calls, thoughts and plans
            wait in the full conversation when you want them.
          </p>
        </Reveal>
      </div>
    </section>
  );
}
