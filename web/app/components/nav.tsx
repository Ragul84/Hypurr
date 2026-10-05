"use client";

import Image from "next/image";
import Link from "next/link";
import { motion, useMotionValueEvent, useScroll } from "framer-motion";
import { useState } from "react";
import { GithubLogo } from "@phosphor-icons/react";
import { ThemeToggle } from "./theme";
import { spring } from "./motion";
import { GITHUB } from "../links";

export default function Nav() {
  const { scrollY } = useScroll();
  const [compact, setCompact] = useState(false);
  useMotionValueEvent(scrollY, "change", (y) => setCompact(y > 24));
  return (
    <header className="sticky top-0 z-40 px-3 pt-3 sm:px-6">
      <motion.nav
        animate={{ maxWidth: compact ? 860 : 1152, y: compact ? 4 : 0 }}
        transition={spring}
        className="glass mx-auto flex h-14 items-center justify-between rounded-full pr-2 pl-3"
      >
        <Link href="/" className="flex items-center gap-2.5 font-display text-lg font-bold text-on-surface">
          <Image src="/icon.svg" alt="" width={32} height={32} className="rounded-[9px]" />
          Hypurr
        </Link>
        <div className="flex items-center gap-1">
          <a href="#features" className="hidden rounded-full px-4 py-2 text-sm font-medium text-on-surface-variant transition hover:bg-surface-container-high hover:text-on-surface sm:block">Features</a>
          <a href="#install" className="hidden rounded-full px-4 py-2 text-sm font-medium text-on-surface-variant transition hover:bg-surface-container-high hover:text-on-surface sm:block">Install</a>
          <ThemeToggle />
          <a href={GITHUB} aria-label="Hypurr on GitHub" title="GitHub" className="grid size-10 place-items-center rounded-full text-on-surface-variant transition hover:bg-surface-container-high hover:text-on-surface">
            <GithubLogo size={20} weight="bold" />
          </a>
          <a href="#install" className="flow-bg ml-1 rounded-full px-4 py-2 text-sm font-semibold text-white transition active:scale-95">Get Hypurr</a>
        </div>
      </motion.nav>
    </header>
  );
}
