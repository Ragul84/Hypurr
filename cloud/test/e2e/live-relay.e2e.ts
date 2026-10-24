// Live check of "Connect from anywhere" against the deployed relay (default https://relay.mindgains.ai):
// the real hypurr-host on this machine (no LAN address given to the phone) + a fake phone that only
// ever talks through the relay. Proves: the host's built-in default relay, QR pairing over the relay,
// an agent task with an approval through it, that the relay only carried ciphertext, and that
// turning the relay off on the computer cuts the phone off.
//
//   cargo build (host/) && npm run e2e:live      # HYPURR_RELAY_URL=… to point elsewhere
import { type ChildProcess, spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { FakePhone, parsePairing, waitFor, type Json } from "./phone.ts";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)), "..");
const HOST_BIN = process.env.HYPURR_HOST_BIN ?? join(ROOT, "host/target/debug/hypurr-host");
const FAKE_AGENT = join(ROOT, "host/tests/fake_agent.py");
const RELAY = process.env.HYPURR_RELAY_URL; // unset: the host's built-in default
const tmp = mkdtempSync(join(tmpdir(), "hypurr-live-"));
const home = join(tmp, "home");
let host: ChildProcess | undefined;

const check = (cond: unknown, what: string) => {
  if (!cond) throw new Error(`check failed: ${what}`);
  console.log(`  ✓ ${what}`);
};

async function freePort(): Promise<number> {
  return new Promise((r) => {
    const s = createServer();
    s.listen(0, "127.0.0.1", () => {
      const p = (s.address() as { port: number }).port;
      s.close(() => r(p));
    });
  });
}

async function main() {
  const port = await freePort();
  const env: Record<string, string> = { ...(process.env as Record<string, string>), HYPURR_HOME: home };
  delete env.HYPURR_CLOUD_URL;
  if (RELAY) env.HYPURR_CLOUD_URL = RELAY;
  host = spawn(HOST_BIN, ["serve", "--bind", "127.0.0.1", "--port", String(port)], { env, stdio: ["ignore", "pipe", "pipe"] });
  host.stderr?.on("data", (d) => process.env.E2E_VERBOSE && process.stderr.write(d));
  await waitFor("host /health", () => fetch(`http://127.0.0.1:${port}/health`).then((r) => r.ok || undefined).catch(() => undefined), 30_000);
  const api = async (m: string, body: unknown = {}): Promise<Json> => {
    const token = readFileSync(join(home, "token"), "utf8").trim();
    const r = await fetch(`http://127.0.0.1:${port}/api/${m}`, { method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: JSON.stringify(body) });
    return r.json();
  };

  console.log("▶ host registers with the relay and is ready");
  const status = await waitFor("cloud ready", async () => {
    const s = await api("cloudStatus");
    return s.connected ? s : undefined;
  }, 45_000);
  check(status.url && status.connected, `host is on the relay at ${status.url}`);

  console.log("▶ QR pairing over the relay (phone has no LAN route)");
  const pairing = parsePairing((await api("pairing")).pairingUrl);
  check(pairing.cloud === status.url, "QR carries the relay URL and the host's keys");
  const phone = new FakePhone();
  // The host's pairing offer reaches the relay a moment after the QR is made.
  await waitFor("pairing over the relay", () => phone.pair(pairing).then(() => true, () => undefined), 30_000);
  check(true, "paired through the relay (4100)");

  console.log("▶ task with an approval, end to end through the relay");
  const ch = await phone.relay(pairing.cloud!, pairing.computerId, pairing.signKey);
  const bot = (await ch.call("createBot", { name: "Live", backend: "custom", command: `python3 '${FAKE_AGENT}'`, cwd: tmp, permission: "ask" })).bot.id;
  ch.subscribe("events", { since: 0, client: "android" });
  const secret = "PLAINTEXT-CANARY-7f3a";
  await ch.call("send", { botId: bot, text: secret, clientNonce: "live-1" });
  const card = await waitFor("approval card", () => ch.events.find((e) => e.type === "entry" && e.entry.kind === "permission" && e.entry.data.status === "pending"), 20_000);
  await ch.call("respondPermission", { entryId: card.entry.id, optionId: "allow" });
  await waitFor("final reply", () => ch.events.find((e) => e.type === "entry" && e.entry.kind === "agent" && e.entry.data.final === true), 30_000);
  check(true, "approval + agent reply arrived through the relay");
  check(!ch.wire.raw.some((f) => f.includes(secret)), "the relay only carried ciphertext (message text never on the wire)");

  console.log("▶ turning the relay off on the computer cuts the phone off");
  await api("setCloud", { enabled: false });
  await waitFor("presence offline", () => ch.wire.msgs.find((m) => m.t === "presence" && m.online === false) ?? (ch.wire.close ? true : undefined), 30_000);
  check(true, "phone sees the computer go offline on the relay");
  await api("setCloud", { enabled: true });
  console.log("\nlive relay check passed");
}

main()
  .then(() => 0, (e) => (console.error(e), 1))
  .then((code) => {
    host?.kill("SIGTERM");
    rmSync(tmp, { recursive: true, force: true });
    process.exit(code);
  });
