import { createHash, randomBytes } from "node:crypto";

export type GatewaySubject = {
  subjectId: string;
  userId?: string;
  hostId?: string;
  kind: "user" | "device" | "trial";
  keyId: string;
};

export function hashKey(secret: string): string {
  return createHash("sha256").update(secret, "utf8").digest("hex");
}

export function mintKeySecret(): { secret: string; prefix: string; hash: string } {
  const secret = `hk_${randomBytes(24).toString("base64url")}`;
  return { secret, prefix: secret.slice(0, 10), hash: hashKey(secret) };
}

/** Extract Bearer token from Authorization header. */
export function bearer(req: Request): string | undefined {
  const h = req.headers.get("authorization") || req.headers.get("Authorization");
  if (!h) return undefined;
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m?.[1]?.trim();
}

export async function resolveSubject(
  db: D1Database,
  secret: string | undefined,
): Promise<GatewaySubject | null> {
  if (!secret) return null;
  const hash = hashKey(secret);
  const row = await db
    .prepare(
      `SELECT id, user_id, host_id, kind FROM gateway_api_keys
       WHERE key_hash = ? AND revoked_at IS NULL`,
    )
    .bind(hash)
    .first<{ id: string; user_id: string | null; host_id: string | null; kind: string }>();
  if (!row) return null;
  await db.prepare(`UPDATE gateway_api_keys SET last_used_at = ? WHERE id = ?`).bind(Date.now(), row.id).run();
  const kind = (row.kind as GatewaySubject["kind"]) || "user";
  const subjectId =
    kind === "trial"
      ? `trial:${row.host_id ?? row.id}`
      : row.user_id
        ? `user:${row.user_id}`
        : `device:${row.host_id ?? row.id}`;
  return {
    subjectId,
    userId: row.user_id ?? undefined,
    hostId: row.host_id ?? undefined,
    kind,
    keyId: row.id,
  };
}

/** Issue a trial key bound to a host install id (abuse-limited). */
export async function issueTrialKey(db: D1Database, hostId: string, now = Date.now()) {
  const existing = await db
    .prepare(
      `SELECT id, key_prefix FROM gateway_api_keys
       WHERE host_id = ? AND kind = 'trial' AND revoked_at IS NULL LIMIT 1`,
    )
    .bind(hostId)
    .first<{ id: string; key_prefix: string }>();
  if (existing) {
    return { id: existing.id, secret: null as string | null, prefix: existing.key_prefix, reused: true };
  }
  const { secret, prefix, hash } = mintKeySecret();
  const id = `key_${randomBytes(8).toString("hex")}`;
  await db
    .prepare(
      `INSERT INTO gateway_api_keys (id, user_id, host_id, key_hash, key_prefix, label, kind, created_at)
       VALUES (?, NULL, ?, ?, ?, 'trial', 'trial', ?)`,
    )
    .bind(id, hostId, hash, prefix, now)
    .run();
  return { id, secret, prefix, reused: false };
}

/** Issue a user API key (Clerk-authenticated account). */
export async function issueUserKey(db: D1Database, userId: string, label = "default", now = Date.now()) {
  const { secret, prefix, hash } = mintKeySecret();
  const id = `key_${randomBytes(8).toString("hex")}`;
  await db
    .prepare(
      `INSERT INTO gateway_api_keys (id, user_id, host_id, key_hash, key_prefix, label, kind, created_at)
       VALUES (?, ?, NULL, ?, ?, ?, 'user', ?)`,
    )
    .bind(id, userId, hash, prefix, label, now)
    .run();
  return { id, secret, prefix };
}
