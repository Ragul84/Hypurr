"use client";

import { motion, useReducedMotion } from "framer-motion";
import { spring } from "./motion";

// Glass card with a cursor-following colour-flow spotlight and a spring lift on hover.
export default function GlowCard({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  const reduce = useReducedMotion();
  return (
    <motion.div
      whileHover={reduce ? undefined : { y: -6, scale: 1.01 }}
      transition={spring}
      onPointerMove={(e) => {
        const r = e.currentTarget.getBoundingClientRect();
        e.currentTarget.style.setProperty("--mx", `${e.clientX - r.left}px`);
        e.currentTarget.style.setProperty("--my", `${e.clientY - r.top}px`);
      }}
      className={`glass group relative h-full overflow-hidden rounded-[2rem] ${className}`}
    >
      <div
        className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-500 group-hover:opacity-100"
        style={{ background: "radial-gradient(420px circle at var(--mx, 50%) var(--my, 50%), color-mix(in oklch, var(--flow-2) 18%, transparent), transparent 60%)" }}
      />
      <div className="relative h-full">{children}</div>
    </motion.div>
  );
}

export function IconCookie({ children, tone = "primary" }: { children: React.ReactNode; tone?: "primary" | "secondary" | "tertiary" }) {
  const cls = {
    primary: "bg-primary-container text-on-primary-container",
    secondary: "bg-secondary-container text-on-secondary-container",
    tertiary: "bg-tertiary-container text-on-tertiary-container",
  }[tone];
  return <span className={`cookie grid size-14 place-items-center transition-transform duration-700 group-hover:rotate-[60deg] ${cls}`}><span className="transition-transform duration-700 group-hover:-rotate-[60deg]">{children}</span></span>;
}
