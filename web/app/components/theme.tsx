"use client";

import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Moon, Sun } from "@phosphor-icons/react";
import { springBouncy } from "./motion";

export function ThemeToggle() {
  const [theme, setTheme] = useState<"dark" | "light">("dark");
  useEffect(() => {
    // Sync once with the theme the boot script applied before hydration.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setTheme((document.documentElement.dataset.theme as "dark" | "light") ?? "dark");
  }, []);
  function toggle() {
    const next = theme === "dark" ? "light" : "dark";
    setTheme(next);
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem("hypurr-theme", next);
    } catch {}
  }
  return (
    <motion.button
      type="button"
      onClick={toggle}
      whileTap={{ scale: 0.85, rotate: -20 }}
      transition={springBouncy}
      aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
      title="Theme"
      className="grid size-10 place-items-center rounded-full text-on-surface-variant transition-colors hover:bg-surface-container-high hover:text-on-surface"
    >
      <motion.span key={theme} initial={{ rotate: -90, scale: 0.5, opacity: 0 }} animate={{ rotate: 0, scale: 1, opacity: 1 }} transition={springBouncy}>
        {theme === "dark" ? <Sun size={20} weight="bold" /> : <Moon size={20} weight="bold" />}
      </motion.span>
    </motion.button>
  );
}

// Dynamic colour: pick a seed hue and every tonal role re-derives from it.
const seeds = [
  { h: 295, name: "Violet purr" },
  { h: 345, name: "Magenta" },
  { h: 210, name: "Cyan" },
  { h: 150, name: "Mint" },
  { h: 40, name: "Sunset" },
];

export function SeedPicker() {
  const [seed, setSeed] = useState(295);
  useEffect(() => {
    const s = getComputedStyle(document.documentElement).getPropertyValue("--seed-h").trim();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (s) setSeed(Number(s));
  }, []);
  function pick(h: number) {
    setSeed(h);
    document.documentElement.style.setProperty("--seed-h", String(h));
    try {
      localStorage.setItem("hypurr-seed", String(h));
    } catch {}
  }
  return (
    <div className="flex items-center gap-2" role="radiogroup" aria-label="Seed colour">
      {seeds.map((s) => (
        <motion.button
          key={s.h}
          type="button"
          role="radio"
          aria-checked={seed === s.h}
          aria-label={s.name}
          title={s.name}
          onClick={() => pick(s.h)}
          whileHover={{ scale: 1.15 }}
          whileTap={{ scale: 0.9 }}
          transition={springBouncy}
          className="relative size-7 rounded-full"
          style={{
            background: `conic-gradient(oklch(0.7 0.2 ${s.h}), oklch(0.75 0.2 ${s.h + 55}), oklch(0.8 0.14 ${s.h - 85}), oklch(0.7 0.2 ${s.h}))`,
          }}
        >
          {seed === s.h && (
            <motion.span layoutId="seed-ring" transition={springBouncy} className="absolute -inset-1 rounded-full ring-2 ring-on-surface" />
          )}
        </motion.button>
      ))}
    </div>
  );
}
