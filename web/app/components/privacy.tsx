import { HardDrives, LockKey, WifiHigh } from "@phosphor-icons/react/ssr";
import GlowCard, { IconCookie } from "./glow-card";
import Reveal from "./reveal";

const points = [
  {
    icon: HardDrives,
    tone: "primary",
    title: "Your code stays home",
    body: "Bots, transcripts and memory live on your Mac or Linux machine. Agents run there with your own logins. No cloud copy of your code or conversations.",
  },
  {
    icon: WifiHigh,
    tone: "tertiary",
    title: "Tailscale, Wi-Fi or the internet",
    body: "On the same Wi-Fi or a Tailscale network the phone connects directly. Anywhere else it goes through a relay you run yourself.",
  },
  {
    icon: LockKey,
    tone: "secondary",
    title: "End-to-end encrypted, even the pings",
    body: "Every message is sealed between your phone and your computer, so the relay only forwards ciphertext. Notification text is sealed to your phone's key too.",
  },
] as const;

export default function Privacy() {
  return (
    <section className="px-4 py-24 sm:px-6 md:py-32">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <p className="font-medium text-secondary">Privacy</p>
          <h2 className="mt-3 max-w-[36rem] font-display text-4xl font-bold tracking-tight text-on-surface md:text-5xl">
            Private <span className="flow-text">by design.</span>
          </h2>
          <p className="mt-6 max-w-[34rem] text-lg leading-relaxed text-on-surface-variant">
            The phone is a remote for a computer you own. Nothing about your work passes through anyone else in the clear.
          </p>
        </Reveal>
        <div className="mt-14 grid grid-cols-1 gap-4 md:grid-cols-3">
          {points.map(({ icon: Icon, tone, title, body }, i) => (
            <Reveal key={title} delay={i * 0.05}>
              <GlowCard className="p-8">
                <IconCookie tone={tone}>
                  <Icon size={26} weight="fill" />
                </IconCookie>
                <h3 className="mt-6 font-display text-xl font-bold text-on-surface">{title}</h3>
                <p className="mt-3 leading-relaxed text-on-surface-variant">{body}</p>
              </GlowCard>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}
