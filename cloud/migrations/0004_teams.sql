-- Teams: work-account SSO (OIDC), one shared credit pool per team, SCIM provisioning stub.
-- The pool's balance lives in gateway_credits under subject 'team:<id>'.

CREATE TABLE teams (
  id              TEXT PRIMARY KEY,              -- team_…
  name            TEXT NOT NULL,
  created_by      TEXT NOT NULL REFERENCES accounts(user_id),
  -- SSO (null until an admin sets it up)
  sso_provider    TEXT CHECK (sso_provider IN ('google','microsoft')),
  sso_domain      TEXT,                          -- e.g. acme.com; emails must end in @domain
  sso_client_id   TEXT,                          -- OIDC audience (public, not a secret)
  sso_tenant      TEXT,                          -- Microsoft tenant id (GUID); null for Google
  scim_token_hash TEXT,                          -- sha256 of the SCIM bearer token
  created_at      INTEGER NOT NULL
);
CREATE UNIQUE INDEX teams_sso_domain ON teams(sso_domain) WHERE sso_domain IS NOT NULL;

CREATE TABLE team_members (
  team_id             TEXT NOT NULL REFERENCES teams(id),
  user_id             TEXT NOT NULL REFERENCES accounts(user_id),
  email               TEXT,
  role                TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('admin','member')),
  status              TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended')),
  monthly_limit_cents INTEGER,                   -- null: no per-person limit
  spent_cents         INTEGER NOT NULL DEFAULT 0,
  period_start        INTEGER NOT NULL DEFAULT 0, -- unix ms, UTC month
  external_id         TEXT,                       -- SCIM externalId
  created_at          INTEGER NOT NULL,
  PRIMARY KEY (team_id, user_id)
);
-- One team per person: the pool that pays is never ambiguous.
CREATE UNIQUE INDEX team_members_user ON team_members(user_id);
