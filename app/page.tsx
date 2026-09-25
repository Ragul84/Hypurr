import Hero from "./components/hero";
import Features from "./components/features";

export default function Home() {
  return (
    <main className="flex-1 flex flex-col">
      <Hero />
      <Features />

      <section className="px-6 pb-20">
        <div className="max-w-4xl mx-auto grid grid-cols-1 sm:grid-cols-3 gap-4">
          <div className="p-5 rounded-2xl border border-neutral-800 bg-neutral-900/30">
            <div className="text-xs text-neutral-500 mb-2">1</div>
            <h3 className="font-semibold text-white text-sm mb-1.5">Install on your computer</h3>
            <p className="text-sm text-neutral-400 leading-relaxed">Mac: brew install --cask leepokai/codync/codync, then Install host from the menu bar. Linux: codync-host install.</p>
          </div>
          <div className="p-5 rounded-2xl border border-neutral-800 bg-neutral-900/30">
            <div className="text-xs text-neutral-500 mb-2">2</div>
            <h3 className="font-semibold text-white text-sm mb-1.5">Pair your phone</h3>
            <p className="text-sm text-neutral-400 leading-relaxed">Scan the code from the Mac menu bar, the Linux app or codync-host pair. Tailscale lets you reach it from anywhere.</p>
          </div>
          <div className="p-5 rounded-2xl border border-neutral-800 bg-neutral-900/30">
            <div className="text-xs text-neutral-500 mb-2">3</div>
            <h3 className="font-semibold text-white text-sm mb-1.5">Create bots and message them</h3>
            <p className="text-sm text-neutral-400 leading-relaxed">A reviewer on one repo, a fixer on another — each keeps its own conversation and approvals.</p>
          </div>
        </div>
      </section>

      <footer className="flex justify-center gap-6 px-6 py-10 text-sm text-neutral-500 border-t border-neutral-900">
        <a href="/terms" className="hover:text-neutral-300 transition-colors">
          Terms of Use
        </a>
        <a href="/privacy" className="hover:text-neutral-300 transition-colors">
          Privacy Policy
        </a>
        <a href="https://github.com/leepokai/Codync/issues" className="hover:text-neutral-300 transition-colors">
          Contact
        </a>
        <a href="https://github.com/leepokai/Codync" className="hover:text-neutral-300 transition-colors">
          GitHub
        </a>
      </footer>
    </main>
  );
}
