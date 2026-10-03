// Device frames for the HTML app mocks (the mocks are drawn in the new Hypurr style,
// so the site never shows stale screenshots).
export function PhoneFrame({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={`rounded-[3rem] bg-surface-container-highest/70 p-2 shadow-[var(--shadow)] ring-1 ring-outline-variant/40 ${className}`}>
      <div className="relative overflow-hidden rounded-[2.5rem] bg-surface">
        <div className="pointer-events-none absolute inset-0 opacity-60" style={{ background: "radial-gradient(120% 60% at 0% 0%, color-mix(in oklch, var(--flow-1) 22%, transparent), transparent 60%), radial-gradient(100% 50% at 100% 100%, color-mix(in oklch, var(--flow-3) 18%, transparent), transparent 60%)" }} />
        <div className="relative flex items-center justify-between px-7 pt-4 pb-2 text-[11px] font-semibold text-on-surface">
          <span>9:41</span>
          <span className="h-6 w-24 rounded-full bg-black" aria-hidden />
          <span className="flex items-center gap-1">
            <span className="h-2.5 w-4 rounded-[3px] bg-on-surface/80" />
          </span>
        </div>
        <div className="relative">{children}</div>
      </div>
    </div>
  );
}

const avatarHues: Record<string, number> = { P: 0, S: 55, R: -85, V: 30, C: -40, H: 0 };

export function Avatar({ name, size = 40, ring = false }: { name: string; size?: number; ring?: boolean }) {
  const off = avatarHues[name[0]] ?? 0;
  return (
    <span
      className={`relative grid shrink-0 place-items-center font-display font-bold text-white ${ring ? "purr" : ""}`}
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.36,
        fontSize: size * 0.42,
        background: `linear-gradient(135deg, oklch(0.72 0.19 calc(var(--h1) + ${off})), oklch(0.62 0.22 calc(var(--h2) + ${off})))`,
      }}
      aria-hidden
    >
      {name[0]}
    </span>
  );
}
