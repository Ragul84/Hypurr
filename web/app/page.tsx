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
          <p>
            Free and open source (MIT).{" "}
            <a href={GITHUB} className="font-medium text-on-surface underline underline-offset-4 hover:text-primary">Code on GitHub</a>
          </p>
          <div className="flex gap-6">
            <Link href="/terms" className="transition hover:text-on-surface">Terms</Link>
            <Link href="/privacy" className="transition hover:text-on-surface">Privacy</Link>
            <a href={`${GITHUB}/issues`} className="transition hover:text-on-surface">Contact</a>
          </div>
        </div>
      </footer>
    </>
  );
}
