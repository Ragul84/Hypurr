// Shared motion tokens (mirrors kit Motion.swift / the TUI and GTK timings).
export const spring = { type: "spring", stiffness: 260, damping: 26, mass: 0.9 } as const;
export const springBouncy = { type: "spring", stiffness: 380, damping: 18, mass: 0.8 } as const;
export const springGentle = { type: "spring", stiffness: 120, damping: 20 } as const;
export const easeEmphasized = [0.2, 0, 0, 1] as const;
