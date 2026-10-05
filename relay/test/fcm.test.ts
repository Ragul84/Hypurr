// FCM (Android): registration, data-only message shape, service-account JWT, token exchange, dead tokens.
import assert from "node:assert/strict";
import worker, { sealTicket, openTicket } from "../src/index.ts";
import { fcmMessage, isFcmToken, resetFcmTokenCache, sendFcm, serviceJwt } from "../src/fcm.ts";

const fcmToken = "dGVzdC10b2tlbg:APA91bH" + "x".repeat(140);
const env: any = { TICKET_KEY: btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))) };
async function request(path: string, body: unknown, e = env) {
  return worker.fetch(new Request(`https://relay.test${path}`, { method: "POST", body: JSON.stringify(body) }), e);
}

// Registration: FCM tokens get alert tickets; APNs-only kinds and malformed tokens are refused.
assert.equal(isFcmToken(fcmToken), true);
assert.equal(isFcmToken("short"), false);
assert.equal(isFcmToken("has spaces in it but is long enough"), false);
const registered = await request("/register", { token: fcmToken, env: "fcm", kind: "alert" });
assert.equal(registered.status, 200);
const { ticket } = await registered.json() as { ticket: string };
assert.deepEqual(await openTicket(env, ticket), { t: fcmToken, e: "fcm", k: "alert" });
assert.equal((await request("/register", { token: fcmToken, env: "fcm", kind: "liveactivity" })).status, 400);
assert.equal((await request("/register", { token: "bad token", env: "fcm", kind: "alert" })).status, 400);
console.log("fcm registration tests ok");

// Data-only message: the app opens `sealed` itself; every value is a string.
const msg = fcmMessage(fcmToken, {
  alert: { title: "Hypurr", body: "A bot needs your response. Open Hypurr to review." },
  threadId: "cid:b1", category: "needsInput",
  data: { botId: "b1", computerId: "cid", ctx: "local", sealed: "abc", n: 3 },
}).message as any;
assert.equal(msg.token, fcmToken);
assert.equal("notification" in msg, false);
assert.deepEqual(msg.data, { botId: "b1", computerId: "cid", ctx: "local", sealed: "abc", n: "3", title: "Hypurr",
  body: "A bot needs your response. Open Hypurr to review.", threadId: "cid:b1", category: "needsInput" });
assert.equal(msg.android.priority, "HIGH");
assert.equal(msg.android.collapse_key, "cid:b1");
console.log("fcm message tests ok");

// Without FCM secrets an FCM ticket is refused with 503 (APNs-only deployments keep working).
assert.equal((await request("/push", { ticket, alert: { body: "x" } })).status, 503);

// Service-account JWT: RS256 over header.claims, verifiable with the account's public key.
const pair = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]) as CryptoKeyPair;
const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey) as ArrayBuffer);
const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8)).replace(/(.{64})/g, "$1\n")}\n-----END PRIVATE KEY-----\n`;
const fcmEnv = { ...env, FCM_PROJECT_ID: "hypurr-test", FCM_CLIENT_EMAIL: "relay@hypurr-test.iam.gserviceaccount.com", FCM_PRIVATE_KEY: pem.replace(/\n/g, "\\n") };
const jwt = await serviceJwt(fcmEnv, 1_790_000_000);
const [h, c, s] = jwt.split(".");
const unb64 = (x: string) => Uint8Array.from(atob(x.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((x.length + 3) % 4)), (ch) => ch.charCodeAt(0));
assert.deepEqual(JSON.parse(new TextDecoder().decode(unb64(h))), { alg: "RS256", typ: "JWT" });
const claims = JSON.parse(new TextDecoder().decode(unb64(c)));
assert.equal(claims.iss, fcmEnv.FCM_CLIENT_EMAIL);
assert.equal(claims.scope, "https://www.googleapis.com/auth/firebase.messaging");
assert.equal(claims.exp - claims.iat, 3600);
assert.equal(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pair.publicKey, unb64(s), new TextEncoder().encode(`${h}.${c}`)), true);
console.log("fcm service account JWT tests ok");

// Send: token exchange once (cached), then messages:send; UNREGISTERED means the ticket is gone.
const calls: string[] = [];
let sendStatus = 200;
const fetcher = async (url: string, init?: RequestInit) => {
  calls.push(url);
  if (url.startsWith("https://oauth2.googleapis.com/")) {
    assert.match(String(init?.body), /grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer/);
    return Response.json({ access_token: "ya29.test", expires_in: 3600 });
  }
  assert.equal((init?.headers as Record<string, string>).Authorization, "Bearer ya29.test");
  assert.equal(JSON.parse(String(init?.body)).message.token, fcmToken);
  return sendStatus === 200 ? Response.json({ name: "projects/hypurr-test/messages/1" })
    : Response.json({ error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } }, { status: 404 });
};
resetFcmTokenCache();
assert.deepEqual(await sendFcm(fcmEnv, fcmToken, { alert: { body: "hi" } }, fetcher), { status: 200, gone: false });
sendStatus = 404;
const dead = await sendFcm(fcmEnv, fcmToken, { alert: { body: "hi" } }, fetcher);
assert.equal(dead.gone, true);
assert.equal(dead.status, 410);
assert.deepEqual(calls, ["https://oauth2.googleapis.com/token",
  "https://fcm.googleapis.com/v1/projects/hypurr-test/messages:send", "https://fcm.googleapis.com/v1/projects/hypurr-test/messages:send"]);
assert.equal((await sendFcm(fcmEnv, fcmToken, { alert: { body: "x" }, data: { sealed: "x".repeat(5000) } }, fetcher)).status, 413);

// Through the Worker: /push with an FCM ticket reaches FCM, and a dead token answers 410 so the host forgets it.
const realFetch = globalThis.fetch;
sendStatus = 200;
globalThis.fetch = fetcher as typeof fetch;
try {
  const fcmTicket = await sealTicket(fcmEnv, { t: fcmToken, e: "fcm", k: "alert" });
  assert.equal((await request("/push", { ticket: fcmTicket, alert: { title: "Hypurr", body: "done" }, data: { botId: "b1" } }, fcmEnv)).status, 200);
  sendStatus = 404;
  assert.equal((await request("/push", { ticket: fcmTicket, alert: { body: "done" } }, fcmEnv)).status, 410);
  const batch = await request("/push-batch", { notifications: [{ ticket: fcmTicket, alert: { body: "done" } }] }, fcmEnv);
  assert.deepEqual(await batch.json(), { results: [{ index: 0, status: 410 }] });
} finally {
  globalThis.fetch = realFetch;
}
console.log("fcm delivery tests ok");
