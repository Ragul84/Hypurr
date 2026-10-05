"use client";

import { useState } from "react";
import Image from "next/image";
import { AnimatePresence, motion } from "framer-motion";
import { AppleLogo, DeviceMobile, LinuxLogo } from "@phosphor-icons/react";
import CopyCommand from "./copy-command";
import Reveal from "./reveal";
import { spring, springBouncy } from "./motion";
import { APP_STORE, DMG } from "../links";

const INSTALL_SH = "curl -fsSL https://raw.githubusercontent.com/Ragul84/Hypurr/main/packaging/install.sh | sh";

const tabs = [
  { id: "mac", label: "Mac", icon: AppleLogo },
  { id: "linux", label: "Linux", icon: LinuxLogo },
  { id: "iphone", label: "iPhone", icon: DeviceMobile },
] as const;

export default function Install() {
  const [tab, setTab] = useState<(typeof tabs)[number]["id"]>("mac");
  return (
    <section id="install" className="scroll-mt-24 px-4 py-24 sm:px-6 md:py-32">
      <div className="mx-auto max-w-3xl">
        <Reveal className="text-center">
          <p className="font-medium text-secondary">Install</p>
          <h2 className="mt-3 font-display text-4xl font-bold tracking-tight text-on-surface md:text-5xl">
            Install once. <span className="flow-text">Purr forever.</span>
          </h2>
          <p className="mx-auto mt-5 max-w-[34rem] text-lg leading-relaxed text-on-surface-variant">
            Put Hypurr on the computer your agents run on, then pair your phone from it.
          </p>
        </Reveal>

        <Reveal className="mt-12">
          <div className="glass-strong rounded-[2rem] p-3 sm:p-4">
            <div role="tablist" className="flex gap-1 rounded-full bg-surface-container-low p-1">
              {tabs.map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  role="tab"
                  aria-selected={tab === id}
                  onClick={() => setTab(id)}
                  className={`relative flex flex-1 items-center justify-center gap-2 rounded-full px-4 py-2.5 text-sm font-semibold transition-colors ${tab === id ? "text-on-primary-container" : "text-on-surface-variant hover:text-on-surface"}`}
                >
                  {tab === id && <motion.span layoutId="install-pill" transition={springBouncy} className="absolute inset-0 rounded-full bg-primary-container" />}
                  <Icon size={18} weight="fill" className="relative" />
                  <span className="relative">{label}</span>
                </button>
              ))}
            </div>
            <div className="relative min-h-[11rem] px-3 pt-6 pb-3 sm:px-5">
              <AnimatePresence mode="wait">
                <motion.div key={tab} initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -14 }} transition={spring}>
                  {tab === "mac" && (
                    <>
                      <p className="text-on-surface-variant">
                        Homebrew, or the{" "}
                        <a href={DMG} className="font-medium text-primary underline underline-offset-4">signed download</a>. Open Hypurr and it starts the host itself.
                      </p>
                      <div className="mt-5"><CopyCommand command="brew install --cask Ragul84/hypurr/hypurr" /></div>
                    </>
                  )}
                  {tab === "linux" && (
                    <>
                      <p className="text-on-surface-variant">
                        The host, plus the desktop app when there is a display. Then run <code className="font-mono text-on-surface">hypurr-host install</code>.
                      </p>
                      <div className="mt-5"><CopyCommand command={INSTALL_SH} /></div>
                    </>
                  )}
                  {tab === "iphone" && (
                    <>
                      <p className="text-on-surface-variant">
                        Get the app, then scan the code from <span className="text-on-surface">Pair iPhone…</span> in the Mac menu bar, the Linux app or{" "}
                        <code className="font-mono text-on-surface">hypurr-host pair</code>.
                      </p>
                      <a href={APP_STORE} target="_blank" rel="noopener noreferrer" className="mt-5 inline-flex transition hover:opacity-80 active:scale-[0.98]">
                        <Image src="/app-store-badge.svg" alt="Download on the App Store" width={135} height={45} className="h-11 w-auto" />
                      </a>
                    </>
                  )}
                </motion.div>
              </AnimatePresence>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
