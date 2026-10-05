"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Check, Copy } from "@phosphor-icons/react";
import { springBouncy } from "./motion";

export default function CopyCommand({ command }: { command: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(command);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard blocked (insecure context): the command stays selectable.
    }
  }

  return (
    <div className="flex items-center gap-3 rounded-2xl bg-surface-dim/80 py-3 pr-3 pl-5 ring-1 ring-outline-variant/60">
      <span className="font-mono text-sm text-primary select-none">$</span>
      <code className="min-w-0 flex-1 overflow-x-auto font-mono text-sm whitespace-nowrap text-on-surface">{command}</code>
      <motion.button
        type="button"
        onClick={copy}
        whileTap={{ scale: 0.85 }}
        transition={springBouncy}
        aria-label={copied ? "Copied" : "Copy command"}
        title={copied ? "Copied" : "Copy"}
        className="relative grid size-9 shrink-0 place-items-center rounded-full bg-primary-container text-on-primary-container"
      >
        <AnimatePresence mode="popLayout" initial={false}>
          <motion.span
            key={copied ? "ok" : "copy"}
            initial={{ scale: 0.4, opacity: 0, rotate: -30 }}
            animate={{ scale: 1, opacity: 1, rotate: 0 }}
            exit={{ scale: 0.4, opacity: 0, rotate: 30 }}
            transition={springBouncy}
          >
            {copied ? <Check size={16} weight="bold" /> : <Copy size={16} />}
          </motion.span>
        </AnimatePresence>
      </motion.button>
    </div>
  );
}
