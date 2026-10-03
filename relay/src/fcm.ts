// Firebase Cloud Messaging (HTTP v1) for the Android app.
//
// Same contract as APNs: the phone registers its FCM token (`env: "fcm"`) and gets an opaque ticket;
// hosts push through the ticket and never see the token. Messages are data-only so the app opens the
// host's sealed title/body (§6.7) itself, the way the iOS Notification Service Extension does; FCM and
// this Worker only see the generic line.
//
// Secrets (from a Firebase service account JSON with the "Firebase Cloud Messaging API Admin" role):
//   FCM_PROJECT_ID, FCM_CLIENT_EMAIL, FCM_PRIVATE_KEY (the PEM `private_key`).

export interface FcmEnv {
  FCM_PROJECT_ID?: string;
  FCM_CLIENT_EMAIL?: string;
  FCM_PRIVATE_KEY?: string;
}

export interface FcmAlert {
  alert: { title?: string; body?: string };
  threadId?: string;
  category?: string;
  data?: Record<string, unknown>;
}

/** FCM tokens are opaque strings (base64url-ish with `:`); bound the size like APNs tokens. */
export function isFcmToken(token: unknown): token is string {
  return typeof token === "string" && /^[A-Za-z0-9_:\-]{20,4096}$/.test(token);
}

export function fcmConfigured(env: FcmEnv): boolean {
  return Boolean(env.FCM_PROJECT_ID && env.FCM_CLIENT_EMAIL && env.FCM_PRIVATE_KEY);
}

/** The v1 `messages:send` body. Data values must be strings. */
export function fcmMessage(token: string, body: FcmAlert): { message: Record<string, unknown> } {
  const data: Record<string, string> = {};
  for (const [k, v] of Object.entries(body.data ?? {})) data[k] = typeof v === "string" ? v : JSON.stringify(v);
  data.title = (body.alert.title ?? "Hypurr").slice(0, 120);
  data.body = (body.alert.body ?? "").slice(0, 400);
  if (body.threadId) data.threadId = body.threadId;
  if (body.category) data.category = body.category;
  return {
    message: {
      token,
      data,
      android: { priority: "HIGH", ttl: "3600s", ...(body.threadId ? { collapse_key: body.threadId.slice(0, 64) } : {}) },
    },
  };
}

const b64url = (bytes: Uint8Array) =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const text = (s: string) => new TextEncoder().encode(s);

function pemToDer(pem: string): Uint8Array {
  const b64 = pem.replace(/\\n/g, "\n").replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

/** A Google OAuth service-account assertion (RS256), valid for one hour. */
export async function serviceJwt(env: FcmEnv, now = Math.floor(Date.now() / 1000)): Promise<string> {
  const header = b64url(text(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64url(text(JSON.stringify({
    iss: env.FCM_CLIENT_EMAIL,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })));
  const key = await crypto.subtle.importKey("pkcs8", pemToDer(env.FCM_PRIVATE_KEY ?? ""),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, text(`${header}.${claims}`)));
  return `${header}.${claims}.${b64url(sig)}`;
}

type Fetcher = (input: string, init?: RequestInit) => Promise<Response>;

let cachedToken: { email: string; token: string; until: number } | null = null;

/** An OAuth access token for FCM, cached per isolate until a minute before it expires. */
export async function accessToken(env: FcmEnv, fetcher: Fetcher = fetch, now = Math.floor(Date.now() / 1000)): Promise<string> {
  if (cachedToken && cachedToken.email === env.FCM_CLIENT_EMAIL && cachedToken.until > now) return cachedToken.token;
  const res = await fetcher("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: await serviceJwt(env, now) }),
  });
  if (!res.ok) throw Object.assign(new Error(`oauth ${res.status}`), { statusCode: 502 });
  const body = await res.json() as { access_token: string; expires_in?: number };
  cachedToken = { email: env.FCM_CLIENT_EMAIL ?? "", token: body.access_token, until: now + (body.expires_in ?? 3600) - 60 };
  return body.access_token;
}

export function resetFcmTokenCache() {
  cachedToken = null;
}

/** Sends one alert; `gone` means the registration token is dead and the host should drop the ticket. */
export async function sendFcm(env: FcmEnv, token: string, body: FcmAlert, fetcher: Fetcher = fetch): Promise<{ status: number; gone: boolean; error?: string }> {
  const message = fcmMessage(token, body);
  if (text(JSON.stringify(message.message.data)).length > 4000) return { status: 413, gone: false, error: "payload too large" };
  const res = await fetcher(`https://fcm.googleapis.com/v1/projects/${env.FCM_PROJECT_ID}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${await accessToken(env, fetcher)}`, "Content-Type": "application/json" },
    body: JSON.stringify(message),
  });
  if (res.ok) return { status: 200, gone: false };
  const err = await res.json().catch(() => ({})) as { error?: { status?: string; message?: string; details?: Array<{ errorCode?: string }> } };
  const codes = (err.error?.details ?? []).map((d) => d.errorCode);
  const gone = res.status === 404 || codes.includes("UNREGISTERED");
  return { status: gone ? 410 : 502, gone, error: err.error?.message ?? err.error?.status ?? `fcm ${res.status}` };
}
