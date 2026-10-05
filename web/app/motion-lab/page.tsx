import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Hypurr motion lab",
  description: "Wet-asphalt neon micro-animation catalogue",
};

export default function MotionLabPage() {
  return (
    <main className="min-h-screen bg-surface text-on-surface">
      <iframe
        title="Hypurr motion lab"
        src="/motion-lab/index.html"
        className="h-screen w-full border-0"
      />
    </main>
  );
}
