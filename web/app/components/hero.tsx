"use client";

import { motion, useReducedMotion, useScroll, useTransform } from "framer-motion";
import { ArrowRight, GithubLogo, ShieldCheck, Sparkle } from "@phosphor-icons/react";
import { Avatar, PhoneFrame } from "./device";
import { spring, springBouncy, springGentle } from "./motion";
import { GITHUB } from "../links";

const bots = [
  { name: "Pacer", line: "Needs approval: Edit src/pace.js", time: "1:18", needs: true },
  { name: "Ship room", line: "Scout: I added the guard 🎉", time: "1:13", badge: 3 },
  { name: "Relay", line: "Added weeklyTotals to group runs", time: "1:12" },
  { name: "Reviewer", line: "No blockers. The ISO week math…", time: "1:07", badge: 1 },
  { name: "Scout", line: "All 42 tests pass ✓", time: "1:04" },
];

const words = ["Message", "your", "coding", "agents", "like"];

export default function Hero() {
  const reduce = useReducedMotion();
  const { scrollY } = useScroll();
  const y1 = useTransform(scrollY, [0, 600], [0, -60]);
  const y2 = useTransform(scrollY, [0, 600], [0, 40]);
  const rise = (delay: number) =>
    reduce ? {} : { initial: { opacity: 0, y: 28, filter: "blur(8px)" }, animate: { opacity: 1, y: 0, filter: "blur(0px)" }, transition: { ...spring, delay } };

  return (
    <section className="relative px-4 pt-10 pb-24 sm:px-6 md:pt-20 md:pb-32">
      <div className="mx-auto grid max-w-6xl grid-cols-1 items-center gap-16 md:grid-cols-[1.15fr_1fr] md:gap-8">
        <div>
          <motion.a
            href="#features"
            {...rise(0)}
            className="flow-ring mb-7 inline-flex items-center gap-2 rounded-full bg-surface-container px-4 py-1.5 text-sm font-medium text-on-surface"
          >
            <Sparkle size={16} weight="fill" className="text-secondary" />
            Open source · MIT · any coding agent
            <ArrowRight size={14} weight="bold" className="text-on-surface-variant" />
          </motion.a>

          <h1 className="font-display text-[2.75rem] font-bold leading-[0.98] tracking-[-0.035em] text-on-surface sm:text-6xl lg:text-[5.25rem]">
            {words.map((w, i) => (
              <motion.span key={w} className="mr-[0.22em] inline-block" {...rise(0.05 + i * 0.05)}>
                {w}
              </motion.span>
            ))}
            <motion.span className="flow-text inline-block pb-2" {...rise(0.32)}>
              teammates.
            </motion.span>
          </h1>

          <motion.p {...rise(0.4)} className="mt-7 max-w-[34rem] text-lg leading-relaxed text-on-surface-variant sm:text-xl">
            Hypurr runs Claude Code, Codex, Cursor, Gemini and 40+ agents as named bots on your computer. Delegate,
            approve and follow along from your iPhone, Mac, Linux desktop or a terminal over SSH.
          </motion.p>

          <motion.div {...rise(0.48)} className="mt-10 flex flex-col gap-3 sm:flex-row">
            <motion.a
              href="#install"
              whileHover={reduce ? undefined : { scale: 1.04, y: -2 }}
              whileTap={{ scale: 0.96 }}
              transition={springBouncy}
              className="flow-bg group inline-flex items-center justify-center gap-2 rounded-full px-7 py-3.5 font-semibold text-white shadow-[0_12px_40px_-10px_var(--flow-2)]"
            >
              Get Hypurr
              <ArrowRight size={18} weight="bold" className="transition-transform group-hover:translate-x-1" />
            </motion.a>
            <motion.a
              href={GITHUB}
              target="_blank"
              rel="noopener noreferrer"
              whileHover={reduce ? undefined : { scale: 1.04, y: -2 }}
              whileTap={{ scale: 0.96 }}
              transition={springBouncy}
              className="glass inline-flex items-center justify-center gap-2 rounded-full px-7 py-3.5 font-semibold text-on-surface"
            >
              <GithubLogo size={18} weight="fill" />
              Star on GitHub
            </motion.a>
          </motion.div>

          <motion.dl {...rise(0.56)} className="mt-12 grid max-w-md grid-cols-3 gap-4">
            {[
              ["40+", "agents"],
              ["4", "platforms"],
              ["E2E", "encrypted"],
            ].map(([n, l]) => (
              <div key={l}>
                <dt className="font-display text-3xl font-bold text-on-surface">{n}</dt>
                <dd className="text-sm text-on-surface-variant">{l}</dd>
              </div>
            ))}
          </motion.dl>
        </div>

        <div className="relative mx-auto w-full max-w-[24rem] md:max-w-[26rem]">
          <div className="morph flow-bg absolute -inset-6 -z-10 opacity-40 blur-2xl" aria-hidden />
          <motion.div style={reduce ? undefined : { y: y1 }}>
            <motion.div
              initial={reduce ? false : { opacity: 0, y: 60, rotate: -4 }}
              animate={{ opacity: 1, y: 0, rotate: -2 }}
              transition={{ ...springGentle, delay: 0.15 }}
            >
              <PhoneFrame>
                <div className="px-5 pb-6">
                  <div className="flex items-center justify-between py-2">
                    <h2 className="font-display text-3xl font-bold text-on-surface">Bots</h2>
                    <span className="flex items-center gap-1.5 rounded-full bg-tertiary-container px-3 py-1 text-xs font-medium text-on-tertiary-container">
                      <span className="size-1.5 rounded-full bg-tertiary" /> Connected
                    </span>
                  </div>
                  <ul className="mt-2 space-y-1.5">
                    {bots.map((b, i) => (
                      <motion.li
                        key={b.name}
                        initial={reduce ? false : { opacity: 0, x: 30 }}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ ...spring, delay: 0.5 + i * 0.08 }}
                        className={`flex items-center gap-3 rounded-[1.4rem] p-2.5 ${b.needs ? "bg-secondary-container/70" : ""}`}
                      >
                        <Avatar name={b.name} ring={b.needs} />
                        <div className="min-w-0 flex-1">
                          <div className="flex items-baseline justify-between gap-2">
                            <p className="truncate text-[15px] font-semibold text-on-surface">{b.name}</p>
                            <span className="text-[11px] text-on-surface-variant">{b.time}</span>
                          </div>
                          <p className={`truncate text-[13px] ${b.needs ? "font-medium text-secondary" : "text-on-surface-variant"}`}>{b.line}</p>
                        </div>
                        {b.badge && (
                          <span className="grid size-5 place-items-center rounded-full bg-primary text-[11px] font-bold text-on-primary">{b.badge}</span>
                        )}
                      </motion.li>
                    ))}
                  </ul>
                  <div className="mx-auto mt-6 flex w-fit gap-1 rounded-full bg-surface-container-high p-1">
                    <span className="rounded-full bg-primary-container px-5 py-1.5 text-xs font-semibold text-on-primary-container">Bots</span>
                    <span className="px-5 py-1.5 text-xs font-medium text-on-surface-variant">Usage</span>
                  </div>
                </div>
              </PhoneFrame>
            </motion.div>
          </motion.div>

          {/* Floating approval card */}
          <motion.div
            style={reduce ? undefined : { y: y2 }}
            className="absolute -bottom-20 -left-6 z-10 w-[78%] sm:-left-16"
          >
            <motion.div
              initial={reduce ? false : { opacity: 0, scale: 0.8, y: 40 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              transition={{ ...springBouncy, delay: 1.1 }}
              className="glass-strong rounded-[1.75rem] p-4"
            >
              <div className="flex items-center gap-2 text-sm font-semibold text-on-surface">
                <ShieldCheck size={18} weight="fill" className="text-secondary" /> Pacer wants to edit files
              </div>
              <code className="mt-2 block rounded-xl bg-surface-dim/70 px-3 py-2 font-mono text-xs text-on-surface-variant">
                edit src/pace.js  +12 −3
              </code>
              <div className="mt-3 grid grid-cols-3 gap-1.5 text-xs font-semibold">
                <span className="flow-bg rounded-full py-2 text-center text-white">Allow</span>
                <span className="rounded-full bg-primary-container py-2 text-center text-on-primary-container">Always</span>
                <span className="rounded-full bg-surface-container-highest py-2 text-center text-on-surface">Deny</span>
              </div>
            </motion.div>
          </motion.div>

          {/* Floating agent chips */}
          {[
            { t: "Claude Code", c: "top-8 -right-4 sm:-right-10", d: 0 },
            { t: "Codex", c: "top-1/2 -right-2 sm:-right-14", d: 1.2 },
          ].map((chip) => (
            <motion.span
              key={chip.t}
              initial={reduce ? false : { opacity: 0, scale: 0.6 }}
              animate={reduce ? { opacity: 1 } : { opacity: 1, scale: 1, y: [0, -10, 0] }}
              transition={{ opacity: { delay: 1.3 + chip.d * 0.2 }, scale: { ...springBouncy, delay: 1.3 }, y: { duration: 4, repeat: Infinity, ease: "easeInOut", delay: chip.d } }}
              className={`glass absolute z-20 rounded-full px-3.5 py-1.5 text-xs font-semibold text-on-surface ${chip.c}`}
            >
              {chip.t}
            </motion.span>
          ))}
        </div>
      </div>
    </section>
  );
}
