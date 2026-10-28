//! Work accounts and the team credit pool on the Hypurr gateway (`cloud/src/teams`).
//!
//! The phone or desktop app signs the person in with their company's Google or Microsoft
//! account (the OAuth client id comes from the gateway's `sso-config`), then hands the ID token
//! to this computer. The computer trades it for a work gateway key and keeps that key in the
//! vault in place of its personal key, so Hypurr models are paid from the team pool. Admin
//! actions are forwarded with that key; the gateway checks the team role.

use crate::agent::builtin::{GATEWAY_KEY_SLOT, gateway_key, gateway_url};
use crate::store::Store;
use anyhow::{Context, Result, anyhow, bail};
use serde_json::{Value, json};
use std::time::Duration;

/// Vault: the personal key, kept while a work key is in use, restored on sign-out.
const PERSONAL_KEY_SLOT: &str = "gateway_key_personal";
/// kv: the work email this computer is signed in with.
const WORK_EMAIL: &str = "work_email";

fn base() -> String {
    gateway_url()
        .unwrap_or_else(|| crate::agent::builtin::DEFAULT_GATEWAY_URL.to_owned())
        .trim_end_matches('/')
        .to_owned()
}

fn http() -> Result<reqwest::Client> {
    Ok(reqwest::Client::builder().timeout(Duration::from_secs(20)).build()?)
}

/// The gateway's error message in plain words, or its status.
async fn checked(res: reqwest::Response) -> Result<Value> {
    let status = res.status();
    let v: Value = res.json().await.unwrap_or(Value::Null);
    if status.is_success() {
        return Ok(v);
    }
    let msg = v["error"]["message"].as_str().filter(|m| !m.is_empty() && *m != "unauthenticated");
    match (status.as_u16(), msg) {
        (_, Some(m)) => bail!("{m}"),
        (401, None) => bail!("Your work sign-in has expired. Sign in again."),
        (403, None) => bail!("Only a team admin can do this."),
        _ => bail!("The Hypurr gateway said {status}"),
    }
}

fn work_email(store: &Store) -> Option<String> {
    store.kv_get(WORK_EMAIL).filter(|e| !e.is_empty())
}

fn work_key(store: &Store) -> Result<String> {
    if work_email(store).is_none() {
        bail!("Sign in with your work account first.");
    }
    crate::market::vault::read(store, GATEWAY_KEY_SLOT)?
        .filter(|k| !k.is_empty())
        .ok_or_else(|| anyhow!("Sign in with your work account first."))
}

/// `gatewayBalance`: what this computer can spend at the gateway, and the team paying for it.
pub async fn balance(store: &Store) -> Result<Value> {
    // No key yet (no credits, never signed in): nothing to show, not an error.
    let Ok(key) = gateway_key(store, &base()).await else {
        return Ok(json!({"credits": null, "team": null, "workEmail": work_email(store)}));
    };
    let res = http()?
        .get(format!("{}/v1/gateway/balance", base()))
        .bearer_auth(key)
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    let v = checked(res).await?;
    Ok(json!({
        "credits": v["credits"],
        "free": v["free"],
        "team": v["team"],
        "workEmail": work_email(store),
    }))
}

/// `workSsoConfig {email}`: where this work email signs in (provider, client id, endpoints).
pub async fn sso_config(email: &str) -> Result<Value> {
    let email = email.trim().to_lowercase();
    if !email.contains('@') {
        bail!("Enter your work email.");
    }
    let res = http()?
        .get(reqwest::Url::parse_with_params(&format!("{}/v1/teams/sso-config", base()), &[("email", email.as_str())])?)
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    checked(res).await
}

fn keep_work_key(store: &Store, v: &Value) -> Result<()> {
    let key = v["apiKey"]
        .as_str()
        .filter(|k| k.starts_with("hk_"))
        .ok_or_else(|| anyhow!("The gateway didn't return a key"))?;
    if work_email(store).is_none()
        && let Some(personal) = crate::market::vault::read(store, GATEWAY_KEY_SLOT)?.filter(|k| !k.is_empty())
    {
        crate::market::vault::write(store, PERSONAL_KEY_SLOT, &personal)?;
    }
    crate::market::vault::write(store, GATEWAY_KEY_SLOT, key)?;
    store.kv_set(WORK_EMAIL, v["email"].as_str().unwrap_or_default())?;
    Ok(())
}

/// The part of a sign-in result the app may see (never the key).
fn public(v: &Value) -> Value {
    json!({"team": v["team"], "email": v["email"], "role": v["role"]})
}

/// `workSignIn {email, idToken}`.
pub async fn sign_in(store: &Store, b: &Value) -> Result<Value> {
    let res = http()?
        .post(format!("{}/v1/teams/sso", base()))
        .json(&json!({"email": b["email"], "idToken": b["idToken"]}))
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    let v = checked(res).await?;
    keep_work_key(store, &v)?;
    Ok(public(&v))
}

/// `workTeamSetup {name, provider, domain, clientId, tenant?, idToken}`: IT creates the team.
pub async fn setup(store: &Store, b: &Value) -> Result<Value> {
    let body = json!({
        "name": b["name"], "provider": b["provider"], "domain": b["domain"],
        "clientId": b["clientId"], "tenant": b["tenant"], "idToken": b["idToken"],
    });
    let res = http()?
        .post(format!("{}/v1/teams/setup", base()))
        .json(&body)
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    let v = checked(res).await?;
    keep_work_key(store, &v)?;
    Ok(public(&v))
}

/// `workSignOut`: back to this computer's personal key.
pub fn sign_out(store: &Store) -> Result<Value> {
    let personal = crate::market::vault::read(store, PERSONAL_KEY_SLOT)?.unwrap_or_default();
    crate::market::vault::write(store, GATEWAY_KEY_SLOT, &personal)?;
    crate::market::vault::write(store, PERSONAL_KEY_SLOT, "")?;
    store.kv_set(WORK_EMAIL, "")?;
    Ok(json!({}))
}

/// `workTeam`: the team, your role and limit; admins also get members and the pool.
pub async fn team(store: &Store) -> Result<Value> {
    let key = work_key(store)?;
    let res = http()?
        .get(format!("{}/v1/teams/mine", base()))
        .bearer_auth(key)
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    checked(res).await
}

async fn team_id(store: &Store) -> Result<(String, String)> {
    let key = work_key(store)?;
    let t = team(store).await?;
    let id = t["team"]["id"].as_str().ok_or_else(|| anyhow!("You're not in a team"))?.to_owned();
    Ok((key, id))
}

/// `workTeamMember {userId, role?, status?, monthlyLimitCents?}` (team admin).
pub async fn member(store: &Store, b: &Value) -> Result<Value> {
    let user = b["userId"].as_str().filter(|u| !u.is_empty()).ok_or_else(|| anyhow!("`userId` is required"))?;
    let mut patch = serde_json::Map::new();
    for k in ["role", "status", "monthlyLimitCents"] {
        if let Some(v) = b.get(k) {
            patch.insert(k.into(), v.clone());
        }
    }
    let (key, id) = team_id(store).await?;
    let res = http()?
        .patch(format!("{}/v1/teams/{id}/members/{user}", base()))
        .bearer_auth(key)
        .json(&Value::Object(patch))
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    checked(res).await
}

/// `workTeamPool {amountCents}` (team admin): move your own credits into the team pool.
pub async fn fill_pool(store: &Store, b: &Value) -> Result<Value> {
    let (key, id) = team_id(store).await?;
    let res = http()?
        .post(format!("{}/v1/teams/{id}/pool", base()))
        .bearer_auth(key)
        .json(&json!({"amountCents": b["amountCents"]}))
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    checked(res).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sign_in_results_never_carry_the_key() {
        let v = json!({"apiKey": "hk_secret", "team": {"id": "t", "name": "Acme"}, "email": "a@acme.test"});
        let p = public(&v);
        assert!(p.get("apiKey").is_none());
        assert_eq!(p["team"]["name"], "Acme");
    }

    #[tokio::test]
    async fn team_needs_a_work_sign_in() {
        let store = Store::open(std::path::Path::new(":memory:")).unwrap();
        let err = team(&store).await.unwrap_err().to_string();
        assert_eq!(err, "Sign in with your work account first.");
    }
}
