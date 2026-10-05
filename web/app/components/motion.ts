// Shared motion tokens (mirrors docs/design/hypurr-motion.md + kit Motion).
export const spring = { type: "spring", stiffness: 260, damping: 26, mass: 0.9 } as const;
export const springBouncy = { type: "spring", stiffness: 380, damping: 18, mass: 0.8 } as const;
export const springGentle = { type: "spring", stiffness: 120, damping: 20 } as const;
export const easeEmphasized = [0.2, 0, 0, 1] as const;

/** Sunfield motion timing tokens (ms). */
export const HypurrMotion = {
  strike: 120,
  snap: 180,
  settle: 280,
  draw: 420,
  assemble: 720,
  loop: 1600,
  reduced: 150,
} as const;

export const rise = (delay = 0) => ({
  initial: { opacity: 0, y: 8 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: HypurrMotion.settle / 1000, ease: easeEmphasized, delay },
});
