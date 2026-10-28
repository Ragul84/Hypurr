// OIDC ID-token verification for work-account sign-in (Google Workspace, Microsoft Entra ID).
// Only RS256 ID tokens from the two known issuers are accepted; the audience is the team's own
// OAuth client id, and the email must be verified and on the team's domain.

export type Provider = "google" | "microsoft";

export interface IdClaims {
  sub: string;
  email: string;
  name?: string;
}

export interface Jwk {
  kid?: string;
  kty: string;
  n: string;
  e: string;
  alg?: string;
}

export function issuerFor(provider: Provider, tenant?: string | null): string {
  if (provider === "google") return "https://accounts.google.com";
  if (!tenant || !/^[0-9a-f-]{36}$/i.test(tenant)) throw new Error("Microsoft needs the tenant id (a GUID)");
  return `https://login.microsoftonline.com/${tenant}/v2.0`;
}

export function jwksUrl(provider: Provider, tenant?: string | null): string {
  return provider === "google"
    ? "https://www.googleapis.com/oauth2/v3/certs"
    : `https://login.microsoftonline.com/${tenant}/discovery/v2.0/keys`;
}

function b64urlBytes(s: string): Uint8Array {
  const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(b, (ch) => ch.charCodeAt(0));
}

export class OidcError extends Error {}

/** Verifies `token` and returns its claims; throws OidcError with a plain reason otherwise. */
export async function verifyIdToken(
  token: string,
  opts: { provider: Provider; tenant?: string | null; clientId: string; domain: string; keys: Jwk[]; now?: number },
): Promise<IdClaims> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new OidcError("Not a sign-in token");
  let head: { alg?: string; kid?: string };
  let claims: Record<string, unknown>;
  try {
    head = JSON.parse(new TextDecoder().decode(b64urlBytes(parts[0])));
    claims = JSON.parse(new TextDecoder().decode(b64urlBytes(parts[1])));
  } catch {
    throw new OidcError("Not a sign-in token");
  }
  if (head.alg !== "RS256") throw new OidcError("Unsupported token");
  const jwk = opts.keys.find((k) => k.kid === head.kid) ?? (opts.keys.length === 1 ? opts.keys[0] : undefined);
  if (!jwk) throw new OidcError("Unknown signing key");
  const key = await crypto.subtle.importKey(
    "jwk",
    { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    b64urlBytes(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!ok) throw new OidcError("Bad signature");

  const now = Math.floor((opts.now ?? Date.now()) / 1000);
  const iss = issuerFor(opts.provider, opts.tenant);
  const issOk = claims.iss === iss || (opts.provider === "google" && claims.iss === "accounts.google.com");
  if (!issOk) throw new OidcError("Wrong issuer");
  const aud = claims.aud;
  if (!(aud === opts.clientId || (Array.isArray(aud) && aud.includes(opts.clientId)))) throw new OidcError("Wrong audience");
  if (typeof claims.exp !== "number" || claims.exp < now - 60) throw new OidcError("Expired");
  if (typeof claims.sub !== "string" || !claims.sub) throw new OidcError("No subject");

  // Google: email + email_verified (+ hd for Workspace). Microsoft: email or preferred_username.
  const email = String(claims.email ?? claims.preferred_username ?? "").toLowerCase();
  if (opts.provider === "google") {
    if (claims.email_verified !== true) throw new OidcError("Email not verified");
    if (claims.hd !== opts.domain) throw new OidcError("Not a work account on this team's domain");
  }
  if (!email.endsWith(`@${opts.domain.toLowerCase()}`)) throw new OidcError("Not a work account on this team's domain");
  return { sub: claims.sub, email, name: typeof claims.name === "string" ? claims.name : undefined };
}

/** Fetches the provider's signing keys (cached by Cloudflare for an hour). */
export async function fetchKeys(provider: Provider, tenant?: string | null): Promise<Jwk[]> {
  const res = await fetch(jwksUrl(provider, tenant), { cf: { cacheTtl: 3600, cacheEverything: true } } as RequestInit);
  if (!res.ok) throw new OidcError("Couldn't reach the sign-in provider");
  return ((await res.json()) as { keys: Jwk[] }).keys;
}
