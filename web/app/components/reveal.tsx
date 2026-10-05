"use client";

import { motion, useReducedMotion } from "framer-motion";
import { spring } from "./motion";

// Springs a block up into place once as it scrolls into view; static under reduced motion.
export default function Reveal({
  children,
  delay = 0,
  className,
}: {
  children: React.ReactNode;
  delay?: number;
  className?: string;
}) {
  const reduce = useReducedMotion();
  return (
    <motion.div
      className={className}
      initial={reduce ? false : { opacity: 0, y: 36, scale: 0.97, filter: "blur(6px)" }}
      whileInView={{ opacity: 1, y: 0, scale: 1, filter: "blur(0px)" }}
      viewport={{ once: true, amount: 0.2 }}
      transition={{ ...spring, delay }}
    >
      {children}
    </motion.div>
  );
}
