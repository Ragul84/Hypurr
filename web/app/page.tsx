import Link from "next/link";
import Nav from "./components/nav";
import Hero from "./components/hero";
import Marquee from "./components/marquee";
import ChatDemo from "./components/chat-demo";
import Bento from "./components/bento";
import Platforms from "./components/platforms";
import Privacy from "./components/privacy";
import Install from "./components/install";
import Cta from "./components/cta";
import { GITHUB } from "./links";

const BADGES = [
  { alt: "GitHub stars", src: "https://img.shields.io/github/stars/Ragul84/Hypurr?style=flat&color=a78bfa", href: GITHUB },
  { alt: "Latest release", src: "https://img.shields.io/github/v/release/Ragul84/Hypurr?include_prereleases&color=22d3ee", href: `${GITHUB}/releases` },
  { alt: "License: MIT", src: "https://img.shields.io/badge/license-MIT-a78bfa", href: `${GITHUB}/blob/main/LICENSE` },
];

export default function Home() {
  return (
    <>
      <Nav />
      <main className="flex-1">
        <Hero />
        <Marquee />
        <ChatDemo />
        <Bento />
        <Platforms />
        <Privacy />
        <Install />
        <Cta />
      </main>
      <footer className="px-4 py-12 sm:px-6">
        <div className="mx-auto flex max-w-6xl flex-col gap-6 text-sm text-on-surface-variant sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-col gap-3">
          {/* Social proof badges (live counts from shields.io; no tracking). */}
          <div className="flex flex-wrap gap-2" aria-label="Project badges">
            {BADGES.map((b) => (
              <a key={b.alt} href={b.href}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={b.src} alt={b.alt} height={20} />
              </a>
            ))}
          </div>
          <p>
            Free and open source (MIT).{" "}
            <a href={GITHUB} className="font-medium text-on-surface underline underline-offset-4 hover:text-primary">Code on GitHub</a>
          </p>
          </div>
          <div className="flex gap-6">
            <Link href="/pricing" className="transition hover:text-on-surface">Pricing</Link>
            <Link href="/it" className="transition hover:text-on-surface">For IT</Link>
            <Link href="/terms" className="transition hover:text-on-surface">Terms</Link>
            <Link href="/privacy" className="transition hover:text-on-surface">Privacy</Link>
            <a href={`${GITHUB}/issues`} className="transition hover:text-on-surface">Contact</a>
          </div>
        </div>
      </footer>
    </>
  );
}
