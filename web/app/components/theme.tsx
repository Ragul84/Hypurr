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

/** Wet-asphalt neon uses a fixed teal accent — no violet seed picker. */
export function SeedPicker() {
  return (
    <div className="flex items-center gap-2 text-sm text-on-primary/80" aria-label="Brand accent">
      <span className="size-7 rounded-[4px] bg-[#00D4C8]" title="Signal teal" />
      <span>Signal teal</span>
    </div>
  );
}
