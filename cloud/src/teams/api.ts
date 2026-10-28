// Teams on the gateway: create a team, work-account sign-in (OIDC), one shared credit pool the
// admin fills and splits with per-person monthly limits, and a SCIM 2.0 stub for provisioning.
// Docs: docs/features/teams-sso.md. Not deployed until the owner approves.

import { ApiError, user, type Ctx } from "../api";
import { hashKey, issueUserKey, mintKeySecret } from "../gateway/auth";
import { getCredits, spendCredits, topUp } from "../gateway/ledger";
import { fetchKeys, OidcError, verifyIdToken, type Jwk, type Provider } from "./oidc";
import { monthBucket } from "./pool";

interface TeamRow {
  id: string;
  name: string;
  sso_provider: Provider | null;
  sso_domain: string | null;
  sso_client_id: string | null;
  sso_tenant: string | null;
}

function rid(bytes = 8): string {
  const b = new Uint8Array(bytes);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

function body(c: Ctx): Record<string, unknown> {
  try {
    const v = JSON.parse(new TextDecoder().decode(c.raw) || "{}");
    if (v && typeof v === "object" && !Array.isArray(v)) return v as Record<string, unknown>;
  } catch {
    /* fallthrough */
  }
  throw new ApiError("badRequest");
}

const DOMAIN = /^(?=.{3,253}$)([a-z0-9-]+\.)+[a-z]{2,}$/;

/** A stable account id per team and work email, so SCIM and SSO land on the same person. */
async function ssoUserId(teamId: string, email: string): Promise<string> {
  return `sso_${(await hashKey(`${teamId}:${email.toLowerCase()}`)).slice(0, 32)}`;
}

async function ensureAccount(c: Ctx, userId: string, email: string) {
  await c.env.DB.prepare(
    `INSERT INTO accounts(user_id, email, created_at) VALUES (?1, ?2, ?3)
     ON CONFLICT(user_id) DO UPDATE SET email = excluded.email`,
  )
    .bind(userId, email, c.now)
    .run();
}

async function admin(c: Ctx, teamId: string) {
  const u = await user(c);
  const m = await c.env.DB.prepare(`SELECT role, status FROM team_members WHERE team_id = ? AND user_id = ?`)
    .bind(teamId, u.userId)
    .first<{ role: string; status: string }>();
  if (!m || m.role !== "admin" || m.status !== "active") throw new ApiError("forbidden", "Only a team admin can do this.");
  return u;
}

export async function createTeam(c: Ctx) {
  const u = await user(c);
  const name = String(body(c).name ?? "").trim();
  if (!name || name.length > 80) throw new ApiError("badRequest", "Give the team a name.");
  const already = await c.env.DB.prepare(`SELECT team_id FROM team_members WHERE user_id = ?`).bind(u.userId).first();
  if (already) throw new ApiError("conflict", "You're already in a team.");
  const id = `team_${rid()}`;
  await c.env.DB.batch([
    c.env.DB.prepare(`INSERT INTO teams(id, name, created_by, created_at) VALUES (?, ?, ?, ?)`).bind(id, name, u.userId, c.now),
    c.env.DB.prepare(
      `INSERT INTO team_members(team_id, user_id, email, role, created_at, period_start) VALUES (?, ?, ?, 'admin', ?, ?)`,
    ).bind(id, u.userId, u.email, c.now, monthBucket(c.now)),
  ]);
  return { id, name, role: "admin" };
}

/** The caller's team. Admins also get the members, their spend this month, and the pool. */
export async function myTeam(c: Ctx) {
  const u = await user(c);
  const m = await c.env.DB.prepare(
    `SELECT t.id, t.name, t.sso_provider, t.sso_domain, m.role, m.status, m.monthly_limit_cents, m.spent_cents, m.period_start
     FROM team_members m JOIN teams t ON t.id = m.team_id WHERE m.user_id = ?`,
  )
    .bind(u.userId)
    .first<Record<string, unknown> & { id: string; role: string }>();
  if (!m) return { team: null };
  const month = monthBucket(c.now);
  const spent = (r: Record<string, unknown>) => (r.period_start === month ? Number(r.spent_cents) : 0);
  const out: Record<string, unknown> = {
    team: { id: m.id, name: m.name, sso: m.sso_provider ? { provider: m.sso_provider, domain: m.sso_domain } : null },
    role: m.role,
    status: m.status,
    monthlyLimitCents: m.monthly_limit_cents,
    spentThisMonthCents: spent(m),
  };
  if (m.role === "admin") {
    const rows = await c.env.DB.prepare(
      `SELECT user_id, email, role, status, monthly_limit_cents, spent_cents, period_start FROM team_members WHERE team_id = ? ORDER BY created_at`,
    )
      .bind(m.id)
      .all<Record<string, unknown>>();
    out.poolCents = await getCredits(c.env.DB, `team:${m.id}`);
    out.members = rows.results.map((r) => ({
      userId: r.user_id,
      email: r.email,
      role: r.role,
      status: r.status,
      monthlyLimitCents: r.monthly_limit_cents,
      spentThisMonthCents: spent(r),
    }));
  }
  return out;
}

export async function setSso(c: Ctx, [teamId]: string[]) {
  await admin(c, teamId);
  const b = body(c);
  const provider = b.provider;
  if (provider !== "google" && provider !== "microsoft") throw new ApiError("badRequest", "provider: google or microsoft");
  const domain = String(b.domain ?? "").toLowerCase();
  if (!DOMAIN.test(domain) || ["gmail.com", "outlook.com", "hotmail.com", "live.com", "yahoo.com"].includes(domain)) {
    throw new ApiError("badRequest", "Use your company's own email domain.");
  }
  const clientId = String(b.clientId ?? "");
  if (!clientId || clientId.length > 200) throw new ApiError("badRequest", "clientId is the OAuth client id from your identity provider.");
  const tenant = provider === "microsoft" ? String(b.tenant ?? "") : null;
  if (provider === "microsoft" && !/^[0-9a-f-]{36}$/i.test(tenant ?? "")) throw new ApiError("badRequest", "tenant is your Microsoft Entra tenant id.");
  const taken = await c.env.DB.prepare(`SELECT id FROM teams WHERE sso_domain = ? AND id != ?`).bind(domain, teamId).first();
  if (taken) throw new ApiError("conflict", "Another team already uses this domain.");
  await c.env.DB.prepare(`UPDATE teams SET sso_provider = ?, sso_domain = ?, sso_client_id = ?, sso_tenant = ? WHERE id = ?`)
    .bind(provider, domain, clientId, tenant, teamId)
    .run();
  return { ok: true, provider, domain };
}

/**
 * Work-account sign-in: the app gets an ID token from Google or Microsoft (the team's own OAuth
 * client) and trades it here for a Hypurr gateway key that spends from the team pool.
 */
export async function ssoExchange(c: Ctx) {
  const b = body(c);
  const email = String(b.email ?? "").toLowerCase();
  const domain = email.split("@")[1] ?? String(b.domain ?? "").toLowerCase();
  const idToken = String(b.idToken ?? "");
  if (!domain || !idToken) throw new ApiError("badRequest");
  const team = await c.env.DB.prepare(
    `SELECT id, name, sso_provider, sso_domain, sso_client_id, sso_tenant FROM teams WHERE sso_domain = ?`,
  )
    .bind(domain)
    .first<TeamRow>();
  if (!team?.sso_provider || !team.sso_client_id || !team.sso_domain) {
    throw new ApiError("notFound", "Your company hasn't set up Hypurr sign-in yet.");
  }
  const testKeys = (c.env as unknown as { OIDC_TEST_JWKS?: string }).OIDC_TEST_JWKS;
  let claims;
  try {
    const keys: Jwk[] = testKeys ? JSON.parse(testKeys).keys : await fetchKeys(team.sso_provider, team.sso_tenant);
    claims = await verifyIdToken(idToken, {
      provider: team.sso_provider,
      tenant: team.sso_tenant,
      clientId: team.sso_client_id,
      domain: team.sso_domain,
      keys,
      now: c.now,
    });
  } catch (e) {
    if (e instanceof OidcError) throw new ApiError("unauthenticated", e.message);
    throw e;
  }
  const userId = await ssoUserId(team.id, claims.email);
  const existing = await c.env.DB.prepare(`SELECT status, team_id FROM team_members WHERE user_id = ?`)
    .bind(userId)
    .first<{ status: string; team_id: string }>();
  if (existing?.status === "suspended") throw new ApiError("forbidden", "Your team admin paused your access.");
  await ensureAccount(c, userId, claims.email);
  if (!existing) {
    await c.env.DB.prepare(
      `INSERT INTO team_members(team_id, user_id, email, role, created_at, period_start) VALUES (?, ?, ?, 'member', ?, ?)`,
    )
      .bind(team.id, userId, claims.email, c.now, monthBucket(c.now))
      .run();
  }
  const key = await issueUserKey(c.env.DB, userId, "sso", c.now);
  return { apiKey: key.secret, keyPrefix: key.prefix, team: { id: team.id, name: team.name }, email: claims.email };
}

/** Admin moves credits from their own balance into the team pool. */
export async function fillPool(c: Ctx, [teamId]: string[]) {
  const u = await admin(c, teamId);
  const amount = Number(body(c).amountCents);
  if (!Number.isInteger(amount) || amount <= 0 || amount > 10_000_000) throw new ApiError("badRequest");
  const spent = await spendCredits(c.env.DB, `user:${u.userId}`, amount, { to: `team:${teamId}` }, c.now);
  if (!spent.ok) throw new ApiError("conflict", "You don't have that many credits. Buy credits first.");
  const pool = await topUp(c.env.DB, `team:${teamId}`, amount, { from: `user:${u.userId}` }, c.now);
  return { poolCents: pool, yourCreditsCents: spent.balance };
}

/** Admin assigns a role, a monthly limit (null = none) or pauses someone. */
export async function updateMember(c: Ctx, [teamId, memberId]: string[]) {
  const u = await admin(c, teamId);
  const b = body(c);
  const sets: string[] = [];
  const binds: unknown[] = [];
  if (b.role !== undefined) {
    if (b.role !== "admin" && b.role !== "member") throw new ApiError("badRequest");
    if (memberId === u.userId && b.role !== "admin") throw new ApiError("badRequest", "You can't remove your own admin role.");
    sets.push("role = ?");
    binds.push(b.role);
  }
  if (b.status !== undefined) {
    if (b.status !== "active" && b.status !== "suspended") throw new ApiError("badRequest");
    if (memberId === u.userId && b.status !== "active") throw new ApiError("badRequest", "You can't pause yourself.");
    sets.push("status = ?");
    binds.push(b.status);
  }
  if (b.monthlyLimitCents !== undefined) {
    const v = b.monthlyLimitCents;
    if (v !== null && !(Number.isInteger(v) && (v as number) >= 0)) throw new ApiError("badRequest");
    sets.push("monthly_limit_cents = ?");
    binds.push(v);
  }
  if (!sets.length) throw new ApiError("badRequest");
  const r = await c.env.DB.prepare(`UPDATE team_members SET ${sets.join(", ")} WHERE team_id = ? AND user_id = ?`)
    .bind(...binds, teamId, memberId)
    .run();
  if (r.meta.changes !== 1) throw new ApiError("notFound");
  if (b.status === "suspended") {
    await c.env.DB.prepare(`UPDATE gateway_api_keys SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`).bind(c.now, memberId).run();
  }
  return { ok: true };
}

/** New SCIM bearer token for the team's identity provider; shown once. */
export async function newScimToken(c: Ctx, [teamId]: string[]) {
  await admin(c, teamId);
  const { secret } = await mintKeySecret();
  const token = `scim_${secret.slice(3)}`;
  await c.env.DB.prepare(`UPDATE teams SET scim_token_hash = ? WHERE id = ?`).bind(await hashKey(token), teamId).run();
  return { token, baseUrl: `${c.url.origin}/scim/v2` };
}

// ---- SCIM 2.0 (stub: Users create / list / deactivate) ----

const SCIM_USER = "urn:ietf:params:scim:schemas:core:2.0:User";
const scimJson = (v: unknown, status = 200) => Response.json(v, { status, headers: { "content-type": "application/scim+json" } });
const scimError = (status: number, detail: string) =>
  scimJson({ schemas: ["urn:ietf:params:scim:api:messages:2.0:Error"], status: String(status), detail }, status);

async function scimTeam(c: Ctx): Promise<string | Response> {
  const tok = /^Bearer\s+(.+)$/i.exec(c.req.headers.get("authorization") ?? "")?.[1];
  if (!tok) return scimError(401, "Missing token");
  const t = await c.env.DB.prepare(`SELECT id FROM teams WHERE scim_token_hash = ?`).bind(await hashKey(tok)).first<{ id: string }>();
  return t ? t.id : scimError(401, "Bad token");
}

function scimUser(r: { user_id: string; email: string | null; status: string; external_id: string | null }) {
  return {
    schemas: [SCIM_USER],
    id: r.user_id,
    externalId: r.external_id ?? undefined,
    userName: r.email,
    emails: r.email ? [{ value: r.email, primary: true }] : [],
    active: r.status === "active",
  };
}

export async function scimConfig() {
  return scimJson({
    schemas: ["urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"],
    patch: { supported: true },
    bulk: { supported: false, maxOperations: 0, maxPayloadSize: 0 },
    filter: { supported: true, maxResults: 200 },
    changePassword: { supported: false },
    sort: { supported: false },
    etag: { supported: false },
    authenticationSchemes: [{ type: "oauthbearertoken", name: "Bearer token", description: "Token from Team settings" }],
  });
}

export async function scimListUsers(c: Ctx) {
  const team = await scimTeam(c);
  if (team instanceof Response) return team;
  const f = /^userName eq "([^"]+)"$/.exec(c.url.searchParams.get("filter") ?? "");
  const rows = await c.env.DB.prepare(
    `SELECT user_id, email, status, external_id FROM team_members WHERE team_id = ?${f ? " AND email = ?" : ""} LIMIT 200`,
  )
    .bind(...(f ? [team, f[1].toLowerCase()] : [team]))
    .all<{ user_id: string; email: string | null; status: string; external_id: string | null }>();
  return scimJson({
    schemas: ["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
    totalResults: rows.results.length,
    startIndex: 1,
    itemsPerPage: rows.results.length,
    Resources: rows.results.map(scimUser),
  });
}

export async function scimCreateUser(c: Ctx) {
  const team = await scimTeam(c);
  if (team instanceof Response) return team;
  const b = body(c);
  const email = String(b.userName ?? "").toLowerCase();
  const t = await c.env.DB.prepare(`SELECT sso_domain FROM teams WHERE id = ?`).bind(team).first<{ sso_domain: string | null }>();
  if (!t?.sso_domain || !email.endsWith(`@${t.sso_domain}`)) return scimError(400, "userName must be a work email on the team's SSO domain");
  const userId = await ssoUserId(team, email);
  const dupe = await c.env.DB.prepare(`SELECT 1 FROM team_members WHERE user_id = ?`).bind(userId).first();
  if (dupe) return scimError(409, "User exists");
  await ensureAccount(c, userId, email);
  const status = b.active === false ? "suspended" : "active";
  const ext = typeof b.externalId === "string" ? b.externalId.slice(0, 200) : null;
  await c.env.DB.prepare(
    `INSERT INTO team_members(team_id, user_id, email, role, status, external_id, created_at, period_start) VALUES (?, ?, ?, 'member', ?, ?, ?, ?)`,
  )
    .bind(team, userId, email, status, ext, c.now, monthBucket(c.now))
    .run();
  return scimJson(scimUser({ user_id: userId, email, status, external_id: ext }), 201);
}

/** PATCH active=false / DELETE: pause the person and revoke their keys (records stay for audit). */
export async function scimDeactivate(c: Ctx, [userId]: string[]) {
  const team = await scimTeam(c);
  if (team instanceof Response) return team;
  let active = false;
  if (c.req.method === "PATCH") {
    const ops = (body(c).Operations ?? []) as { op?: string; path?: string; value?: unknown }[];
    for (const op of ops) {
      if (op.path === "active") active = op.value === true || op.value === "true";
      else if (op.value && typeof op.value === "object" && "active" in op.value) active = (op.value as { active: unknown }).active === true;
    }
  }
  const r = await c.env.DB.prepare(`UPDATE team_members SET status = ? WHERE team_id = ? AND user_id = ? AND role != 'admin'`)
    .bind(active ? "active" : "suspended", team, userId)
    .run();
  if (r.meta.changes !== 1) return scimError(404, "No such member (admins are managed in the app)");
  if (!active) {
    await c.env.DB.prepare(`UPDATE gateway_api_keys SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`).bind(c.now, userId).run();
  }
  return c.req.method === "DELETE" ? new Response(null, { status: 204 }) : scimJson({ schemas: [SCIM_USER], id: userId, active });
}
