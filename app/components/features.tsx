"use client";

import { useRef } from "react";
import { motion, useMotionValue, useTransform } from "framer-motion";

const features = [
  {
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <circle cx="12" cy="9" r="5" /><circle cx="10" cy="8.5" r="0.8" fill="currentColor" /><circle cx="14" cy="8.5" r="0.8" fill="currentColor" /><path d="M6 20c1.2-2.5 3.4-4 6-4s4.8 1.5 6 4" strokeLinecap="round" />
      </svg>
    ),
    title: "Bots, not sessions",
    description:
      "Give each agent a name, a job and a project. One ongoing chat per bot — no hunting for the right session.",
  },
  {
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <path d="M12 3l7 4v5c0 4.4-3 8-7 9-4-1-7-4.6-7-9V7l7-4z" /><path d="M9 12l2 2 4-4" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    ),
    title: "Approve from anywhere",
    description:
      "Commands and file changes arrive as approval cards: Allow once, Always allow or Deny.",
  },
  {
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <rect x="3" y="4" width="18" height="14" rx="2" /><path d="M8 9h8M8 13h5" strokeLinecap="round" />
      </svg>
    ),
    title: "Every agent you have",
    description:
      "Finds Claude Code, Codex, Cursor, Pi, OpenCode, Grok, Gemini, Copilot and more — or installs any agent from the ACP registry.",
  },
  {
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <path d="M6 8a6 6 0 0112 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10 21h4" strokeLinecap="round" />
      </svg>
    ),
    title: "Only the pings that matter",
    description:
      "A notification when a bot needs you or finishes. Everything else waits in Full conversation.",
  },
  {
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <rect x="5" y="2" width="14" height="20" rx="4" /><path d="M9 2h6" strokeLinecap="round" />
      </svg>
    ),
    title: "iPhone, Mac and Linux",
    description:
      "A native app on each: iPhone with widgets and Live Activities, a Mac window, and a GTK app for Linux.",
  },
  {
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 018 0v4" />
      </svg>
    ),
    title: "Your computer, your code",
    description:
      "Your phone talks straight to your machine. Agents use your own logins. No account, no cloud copy of your code.",
  },
];

function FeatureCard({ feature, index }: { feature: (typeof features)[number]; index: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const mouseX = useMotionValue(0);
  const mouseY = useMotionValue(0);

  function handleMouse(e: React.MouseEvent) {
    const rect = ref.current?.getBoundingClientRect();
    if (!rect) return;
    mouseX.set(e.clientX - rect.left);
    mouseY.set(e.clientY - rect.top);
  }

  return (
    <motion.div
      ref={ref}
      key={feature.title}
      initial={{ opacity: 0, y: 20 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: "-30px" }}
      transition={{ duration: 0.5, delay: index * 0.08, ease: [0.21, 0.47, 0.32, 0.98] }}
      onMouseMove={handleMouse}
      className="group relative p-5 rounded-2xl border border-neutral-800 bg-neutral-900/30 hover:bg-neutral-900/60 hover:border-neutral-700 transition-all duration-300 overflow-hidden"
    >
      {/* Spotlight glow on hover */}
      <motion.div
        className="pointer-events-none absolute -inset-px rounded-2xl opacity-0 group-hover:opacity-100 transition-opacity duration-300"
        style={{
          background: useTransform(
            [mouseX, mouseY],
            ([x, y]) =>
              `radial-gradient(300px circle at ${x}px ${y}px, rgba(255,255,255,0.04), transparent 60%)`
          ),
        }}
      />
      <div className="relative z-10">
        <div className="w-9 h-9 rounded-lg bg-neutral-800/80 border border-neutral-700/50 flex items-center justify-center text-neutral-300 mb-3 group-hover:text-white group-hover:border-neutral-600 transition-colors">
          {feature.icon}
        </div>
        <h3 className="font-semibold text-white text-sm mb-1.5">
          {feature.title}
        </h3>
        <p className="text-sm text-neutral-400 leading-relaxed">
          {feature.description}
        </p>
      </div>
    </motion.div>
  );
}

export default function Features() {
  return (
    <section className="px-6 py-20">
      <div className="max-w-4xl mx-auto">
        <motion.h2
          initial={{ opacity: 0 }}
          whileInView={{ opacity: 1 }}
          viewport={{ once: true }}
          className="text-2xl font-bold text-white text-center mb-12"
        >
          Delegate like you'd message a teammate
        </motion.h2>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {features.map((feature, i) => (
            <FeatureCard key={feature.title} feature={feature} index={i} />
          ))}
        </div>
      </div>
    </section>
  );
}
