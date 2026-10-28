// Teams: work-account SSO, the shared credit pool with per-person limits, and the SCIM stub.

import { describe, expect, it } from "vitest";
import { topUp } from "../src/gateway/ledger";
import { resolveSubject } from "../src/gateway/auth";
import { payerFor, payerSpend, TEAM_LIMIT_MESSAGE } from "../src/teams/pool";
import { b64url, enc } from "./ref";
import { call, clerkToken, env } from "./helpers";

async function idToken(claims: Record<string, unknown>): Promise<string> {
  const key = await crypto.subtle.importKey(
    "jwk",
    JSON.parse((env as unknown as { TEST_OIDC_PRIVATE_JWK: string }).TEST_OIDC_PRIVATE_JWK),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const now = Math.floor(Date.now() / 1000);
  const part = (v: unknown) => b64url(enc.encode(JSON.stringify(v)));
  const head = `${part({ alg: "RS256", kid: "oidc_test" })}.${part({
    iss: "https://accounts.google.com",
    aud: "cid-acme",
    exp: now + 300,
    iat: now,
    email_verified: true,
    hd: "acme.test",
    ...claims,
  })}`;
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(head));
  return `${head}.${b64url(new Uint8Array(sig))}`;
}

describe("teams", () => {
  it("SSO sign-in, a shared pool with limits, and SCIM deprovisioning", async () => {
    const adminTok = await clerkToken("user_teamadmin");
    const t = await call("POST", "/v1/teams", { token: adminTok, body: { name: "Acme" } });
    expect(t.status).toBe(200);
    const teamId = t.body.id as string;
    expect((await call("POST", "/v1/teams", { token: adminTok, body: { name: "Again" } })).status).toBe(409);

    // Consumer domains and non-admins are refused.
    expect((await call("PUT", `/v1/teams/${teamId}/sso`, { token: adminTok, body: { provider: "google", domain: "gmail.com", clientId: "x" } })).status).toBe(400);
    expect((await call("PUT", `/v1/teams/${teamId}/sso`, { token: await clerkToken("user_stranger"), body: { provider: "google", domain: "acme.test", clientId: "cid-acme" } })).status).toBe(403);
    expect((await call("PUT", `/v1/teams/${teamId}/sso`, { token: adminTok, body: { provider: "google", domain: "acme.test", clientId: "cid-acme" } })).status).toBe(200);

    // Work-account sign-in.
    const bad = await call("POST", "/v1/teams/sso", { body: { email: "bob@acme.test", idToken: await idToken({ sub: "g-bob", email: "bob@acme.test", aud: "someone-else" }) } });
    expect(bad.status).toBe(401);
    const other = await call("POST", "/v1/teams/sso", { body: { email: "eve@acme.test", idToken: await idToken({ sub: "g-eve", email: "eve@evil.test", hd: "evil.test" }) } });
    expect(other.status).toBe(401);
    const bob = await call("POST", "/v1/teams/sso", { body: { email: "bob@acme.test", idToken: await idToken({ sub: "g-bob", email: "bob@acme.test" }) } });
    expect(bob.status).toBe(200);
    expect(bob.body.team.name).toBe("Acme");
    const bobKey = bob.body.apiKey as string;

    // Admin fills the pool from their own credits and gives Bob a 100-cent monthly limit.
    expect((await call("POST", `/v1/teams/${teamId}/pool`, { token: adminTok, body: { amountCents: 500 } })).status).toBe(409);
    await topUp(env.DB, "user:user_teamadmin", 1000, { test: true });
    const fill = await call("POST", `/v1/teams/${teamId}/pool`, { token: adminTok, body: { amountCents: 500 } });
    expect(fill.body).toEqual({ poolCents: 500, yourCreditsCents: 500 });
    const mine = await call("GET", "/v1/teams/mine", { token: adminTok });
    const bobId = mine.body.members.find((m: { email: string }) => m.email === "bob@acme.test").userId as string;
    expect((await call("PATCH", `/v1/teams/${teamId}/members/${bobId}`, { token: adminTok, body: { monthlyLimitCents: 100 } })).status).toBe(200);

    const bal = await call("GET", "/v1/gateway/balance", { token: bobKey });
    expect(bal.status).toBe(200);
    expect(bal.body.credits.balanceCents).toBe(100);
    expect(bal.body.team.id).toBe(teamId);

    // Spending comes out of the pool, and stops at Bob's limit.
    const sub = (await resolveSubject(env.DB, bobKey))!;
    const payer = await payerFor(env.DB, sub);
    expect(payer.subjectId).toBe(`team:${teamId}`);
    expect((await payerSpend(env.DB, payer, 80, { model: "m" })).ok).toBe(true);
    const over = await payerSpend(env.DB, payer, 30, { model: "m" });
    expect(over.ok).toBe(false);
    if (!over.ok) expect(over.message).toBe(TEAM_LIMIT_MESSAGE);
    const after = await call("GET", "/v1/teams/mine", { token: adminTok });
    expect(after.body.poolCents).toBe(420);
    expect(after.body.members.find((m: { userId: string }) => m.userId === bobId).spentThisMonthCents).toBe(80);

    // SCIM: provision Carol, find her, then deprovision Bob (his key stops working).
    const scim = await call("POST", `/v1/teams/${teamId}/scim-token`, { token: adminTok });
    const h = { Authorization: `Bearer ${scim.body.token}` };
    expect((await call("GET", "/scim/v2/Users", {})).status).toBe(401);
    const carol = await call("POST", "/scim/v2/Users", { headers: h, body: { userName: "carol@acme.test", externalId: "e-carol" } });
    expect(carol.status).toBe(201);
    expect((await call("POST", "/scim/v2/Users", { headers: h, body: { userName: "mallory@elsewhere.test" } })).status).toBe(400);
    const list = await call("GET", `/scim/v2/Users?filter=${encodeURIComponent('userName eq "carol@acme.test"')}`, { headers: h });
    expect(list.body.totalResults).toBe(1);
    const off = await call("PATCH", `/scim/v2/Users/${bobId}`, { headers: h, body: { Operations: [{ op: "replace", path: "active", value: false }] } });
    expect(off.body.active).toBe(false);
    expect((await call("GET", "/v1/gateway/balance", { token: bobKey })).status).toBe(401);
    const again = await call("POST", "/v1/teams/sso", { body: { email: "bob@acme.test", idToken: await idToken({ sub: "g-bob", email: "bob@acme.test" }) } });
    expect(again.status).toBe(403);
  });
});
