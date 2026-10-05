import type { Metadata, Viewport } from "next";
import { Bricolage_Grotesque, Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });
const display = Bricolage_Grotesque({ variable: "--font-display", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Hypurr: message your coding agents like teammates",
  description:
    "Hypurr is a free, open-source app for messaging Claude Code, Codex, Cursor, Gemini and 40+ coding agents as persistent bots from your iPhone, Mac, Linux desktop or terminal. Group chats, threads, approvals, memory, remote screen and voice, on your own computer.",
  metadataBase: new URL("https://www.hypurr.dev"),
  icons: {
    icon: [
      { url: "/icon.svg", type: "image/svg+xml" },
      { url: "/icon.png", sizes: "512x512", type: "image/png" },
    ],
    apple: "/apple-touch-icon.png",
  },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: dark)", color: "#120a26" },
    { media: "(prefers-color-scheme: light)", color: "#fbf8ff" },
  ],
};

// Applies the saved theme and seed colour before first paint (no flash).
const boot = `(()=>{try{var d=document.documentElement,t=localStorage.getItem('hypurr-theme'),s=localStorage.getItem('hypurr-seed');
if(!t)t=matchMedia('(prefers-color-scheme: light)').matches?'light':'dark';d.dataset.theme=t;if(s)d.style.setProperty('--seed-h',s)}catch(e){}})()`;

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="en"
      data-theme="dark"
      suppressHydrationWarning
      className={`${geistSans.variable} ${geistMono.variable} ${display.variable} h-full antialiased`}
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: boot }} />
      </head>
      <body className="min-h-full flex flex-col overflow-x-hidden">
        <div className="aurora" aria-hidden>
          <span />
          <span />
          <span />
        </div>
        <div className="grain" aria-hidden />
        {/* M3 Expressive "cookie" (9-scallop) shape, referenced by .cookie */}
        <svg width="0" height="0" className="absolute" aria-hidden>
          <clipPath id="cookie" clipPathUnits="objectBoundingBox">
            <path d="M0.5,0 C0.58,0 0.62,0.07 0.69,0.09 C0.76,0.11 0.84,0.08 0.89,0.14 C0.94,0.2 0.91,0.28 0.94,0.35 C0.97,0.42 1,0.46 1,0.53 C1,0.6 0.94,0.64 0.93,0.71 C0.92,0.78 0.95,0.86 0.89,0.91 C0.83,0.96 0.75,0.92 0.68,0.95 C0.61,0.98 0.57,1 0.5,1 C0.43,1 0.39,0.98 0.32,0.95 C0.25,0.92 0.17,0.96 0.11,0.91 C0.05,0.86 0.08,0.78 0.07,0.71 C0.06,0.64 0,0.6 0,0.53 C0,0.46 0.03,0.42 0.06,0.35 C0.09,0.28 0.06,0.2 0.11,0.14 C0.16,0.08 0.24,0.11 0.31,0.09 C0.38,0.07 0.42,0 0.5,0 Z" />
          </clipPath>
        </svg>
        {children}
      </body>
    </html>
  );
}
