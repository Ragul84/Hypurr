import Image from "next/image";
import { GITHUB } from "../links";
import Reveal from "./reveal";
import { SeedPicker } from "./theme";

export default function Cta() {
  return (
    <section className="px-4 pb-24 sm:px-6">
      <Reveal className="mx-auto max-w-6xl">
        <div className="flow-bg relative overflow-hidden rounded-[2.5rem] px-8 py-16 text-center text-white md:py-24">
          <div className="morph absolute -top-24 -left-24 size-80 bg-white/15 blur-2xl" aria-hidden />
          <div className="morph absolute -right-20 -bottom-28 size-96 bg-black/15 blur-2xl [animation-delay:-5s]" aria-hidden />
          <Image src="/icon.svg" alt="" width={88} height={88} className="relative mx-auto rounded-[1.4rem] shadow-2xl" />
          <h2 className="relative mt-8 font-display text-4xl font-bold tracking-tight md:text-6xl">Hear your agents purr.</h2>
          <p className="relative mx-auto mt-5 max-w-xl text-lg text-white/85">Free and open source. Bring any coding agent, keep your code at home.</p>
          <div className="relative mt-9 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <a href="#install" className="rounded-full bg-white px-7 py-3.5 font-semibold text-black transition hover:scale-105 active:scale-95">Get Hypurr</a>
            <a href={GITHUB} className="rounded-full bg-black/25 px-7 py-3.5 font-semibold text-white backdrop-blur transition hover:scale-105 hover:bg-black/35 active:scale-95">View the code</a>
          </div>
          <div className="relative mt-10 flex flex-col items-center gap-3">
            <p className="text-sm text-white/80">Pick a seed colour — the whole page re-tones.</p>
            <div className="rounded-full bg-black/25 p-2 backdrop-blur"><SeedPicker /></div>
          </div>
        </div>
      </Reveal>
    </section>
  );
}
