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

const GATEWAY_OFF: &str = "Work accounts need the Hypurr gateway, which is turned off on this computer.";

fn base() -> Result<String> {
    if crate::agent::builtin::gateway_off() {
        bail!(GATEWAY_OFF);
    }
    Ok(gateway_url()
        .unwrap_or_else(|| crate::agent::builtin::DEFAULT_GATEWAY_URL.to_owned())
        .trim_end_matches('/')
        .to_owned())
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
    let Ok(base) = base() else {
        return Ok(json!({"credits": null, "team": null, "workEmail": work_email(store), "gateway": "off"}));
    };
    let Ok(key) = gateway_key(store, &base).await else {
        return Ok(json!({"credits": null, "team": null, "workEmail": work_email(store)}));
    };
    let res = http()?
        .get(format!("{base}/v1/gateway/balance"))
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
        .get(reqwest::Url::parse_with_params(
            &format!("{}/v1/teams/sso-config", base()?),
            &[("email", email.as_str())],
        )?)
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
        .post(format!("{}/v1/teams/sso", base()?))
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
        .post(format!("{}/v1/teams/setup", base()?))
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
        .get(format!("{}/v1/teams/mine", base()?))
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
        .patch(format!("{}/v1/teams/{id}/members/{user}", base()?))
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
        .post(format!("{}/v1/teams/{id}/pool", base()?))
        .bearer_auth(key)
        .json(&json!({"amountCents": b["amountCents"]}))
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    checked(res).await
}

// ---- Desktop sign-in (this computer's own browser, loopback redirect, RFC 8252) ----

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

/// Finished desktop sign-ins by attempt id (the app polls `workDesktopResult`).
/// `None` while the browser is still out.
type Pending = Option<Result<Value, String>>;
static DESKTOP: LazyLock<Mutex<HashMap<String, Pending>>> = LazyLock::new(Default::default);

fn random_b64() -> Result<String> {
    let mut raw = [0u8; 32];
    getrandom::fill(&mut raw).map_err(|_| anyhow!("random bytes unavailable"))?;
    Ok(URL_SAFE_NO_PAD.encode(raw))
}

/// Provider endpoints for first-time setup (before the gateway knows the team).
fn setup_config(b: &Value) -> Result<Value> {
    let provider = b["provider"].as_str().unwrap_or_default();
    let client =
        b["clientId"].as_str().filter(|c| !c.is_empty()).ok_or_else(|| anyhow!("Enter the OAuth client id"))?;
    Ok(match provider {
        "google" => json!({"provider": "google", "clientId": client,
            "authorizeUrl": "https://accounts.google.com/o/oauth2/v2/auth", "tokenUrl": "https://oauth2.googleapis.com/token"}),
        "microsoft" => {
            let t = b["tenant"]
                .as_str()
                .filter(|t| t.len() == 36)
                .ok_or_else(|| anyhow!("Enter the Microsoft tenant id"))?;
            json!({"provider": "microsoft", "clientId": client, "tenant": t,
                "authorizeUrl": format!("https://login.microsoftonline.com/{t}/oauth2/v2.0/authorize"),
                "tokenUrl": format!("https://login.microsoftonline.com/{t}/oauth2/v2.0/token")})
        }
        _ => bail!("Pick Google or Microsoft"),
    })
}

/// The company sign-in page for `email`, with a loopback redirect to this computer.
pub fn authorize_url(cfg: &Value, email: &str, redirect: &str, state: &str, verifier: &str) -> Result<String> {
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let domain = email.split('@').nth(1).unwrap_or_default().to_owned();
    let mut params = vec![
        ("client_id", cfg["clientId"].as_str().unwrap_or_default().to_owned()),
        ("response_type", "code".into()),
        ("redirect_uri", redirect.into()),
        ("scope", "openid email profile".into()),
        ("state", state.into()),
        ("code_challenge", challenge),
        ("code_challenge_method", "S256".into()),
        ("login_hint", email.into()),
        ("prompt", "select_account".into()),
    ];
    if cfg["provider"] == "google" {
        params.push(("hd", domain));
    }
    Ok(reqwest::Url::parse_with_params(cfg["authorizeUrl"].as_str().unwrap_or_default(), &params)?.to_string())
}

/// `workDesktopStart {email, setup?: {name, provider, clientId, tenant?}}` (this computer only):
/// returns the company sign-in page for the desktop app to open; poll `workDesktopResult {attempt}`.
pub async fn desktop_start(hub: std::sync::Arc<crate::hub::Hub>, b: &Value) -> Result<Value> {
    let email = b["email"].as_str().unwrap_or_default().trim().to_lowercase();
    if !email.contains('@') {
        bail!("Enter your work email.");
    }
    let setup = b.get("setup").filter(|s| s.is_object()).cloned();
    let cfg = match &setup {
        Some(s) => setup_config(s)?,
        None => sso_config(&email).await?,
    };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let redirect = format!("http://127.0.0.1:{}/callback", listener.local_addr()?.port());
    let (state, verifier) = (random_b64()?, random_b64()?);
    let url = authorize_url(&cfg, &email, &redirect, &state, &verifier)?;
    let attempt = random_b64()?;
    DESKTOP.lock().map_err(|_| anyhow!("busy"))?.insert(attempt.clone(), None);
    let id = attempt.clone();
    tokio::spawn(async move {
        let r = desktop_finish(&hub.store, listener, &cfg, &email, setup, &redirect, &state, &verifier)
            .await
            .map_err(|e| format!("{e:#}"));
        if let Ok(mut m) = DESKTOP.lock() {
            m.insert(id, Some(r));
        }
    });
    Ok(json!({"attempt": attempt, "url": url}))
}

#[allow(clippy::too_many_arguments)]
async fn desktop_finish(
    store: &Store,
    listener: tokio::net::TcpListener,
    cfg: &Value,
    email: &str,
    setup: Option<Value>,
    redirect: &str,
    state: &str,
    verifier: &str,
) -> Result<Value> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let (mut sock, _) = tokio::time::timeout(Duration::from_secs(600), listener.accept())
        .await
        .map_err(|_| anyhow!("Sign-in didn't finish within 10 minutes."))??;
    let mut buf = vec![0u8; 8192];
    let n = sock.read(&mut buf).await?;
    let req = String::from_utf8_lossy(&buf[..n]);
    let path = req.split_whitespace().nth(1).unwrap_or("/");
    let url = reqwest::Url::parse(&format!("http://127.0.0.1{path}"))?;
    let q: HashMap<String, String> = url.query_pairs().into_owned().collect();
    let page = "<!doctype html><meta charset=utf-8><title>Hypurr</title><body style=\"font-family:sans-serif;padding:40px\">You can close this tab and go back to Hypurr.";
    let _ = sock
        .write_all(
            format!(
                "HTTP/1.1 200 OK\r\ncontent-type: text/html\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{page}",
                page.len()
            )
            .as_bytes(),
        )
        .await;
    if let Some(e) = q.get("error") {
        bail!("{}", q.get("error_description").unwrap_or(e));
    }
    if q.get("state").map(String::as_str) != Some(state) {
        bail!("That sign-in didn't come from Hypurr. Try again.");
    }
    let code = q.get("code").ok_or_else(|| anyhow!("Sign-in was cancelled."))?;
    let form = reqwest::Url::parse_with_params(
        "http://x/",
        &[
            ("grant_type", "authorization_code"),
            ("code", code),
            ("redirect_uri", redirect),
            ("client_id", cfg["clientId"].as_str().unwrap_or_default()),
            ("code_verifier", verifier),
        ],
    )?;
    let res = http()?
        .post(cfg["tokenUrl"].as_str().unwrap_or_default())
        .header("content-type", "application/x-www-form-urlencoded")
        .body(form.query().unwrap_or_default().to_owned())
        .send()
        .await
        .context("reaching your company's sign-in")?;
    let v: Value = res.json().await.unwrap_or(Value::Null);
    let Some(id_token) = v["id_token"].as_str() else {
        bail!("{}", v["error_description"].as_str().unwrap_or("Your company's sign-in didn't finish."));
    };
    match setup {
        Some(mut s) => {
            s["idToken"] = json!(id_token);
            s["domain"] = json!(email.split('@').nth(1).unwrap_or_default());
            self::setup(store, &s).await
        }
        None => sign_in(store, &json!({"email": email, "idToken": id_token})).await,
    }
}

/// `workDesktopResult {attempt}`: `{done:false}` while waiting, the sign-in result when done.
pub fn desktop_result(attempt: &str) -> Result<Value> {
    let mut m = DESKTOP.lock().map_err(|_| anyhow!("busy"))?;
    match m.get(attempt) {
        None => bail!("Start the sign-in again."),
        Some(None) => Ok(json!({"done": false})),
        Some(Some(_)) => match m.remove(attempt).flatten() {
            Some(Ok(v)) => Ok(json!({"done": true, "result": v})),
            Some(Err(e)) => bail!("{e}"),
            None => Ok(json!({"done": false})),
        },
    }
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

    #[test]
    fn desktop_url_has_pkce_and_domain() {
        let cfg = setup_config(&json!({"provider": "google", "clientId": "cid"})).unwrap();
        let u = authorize_url(&cfg, "sam@acme.com", "http://127.0.0.1:5000/callback", "st", "ver").unwrap();
        assert!(u.starts_with("https://accounts.google.com/o/oauth2/v2/auth?client_id=cid"));
        assert!(u.contains("code_challenge_method=S256") && u.contains("hd=acme.com") && u.contains("state=st"));
        assert!(setup_config(&json!({"provider": "microsoft", "clientId": "c"})).is_err());
        assert!(desktop_result("nope").is_err());
    }

    #[tokio::test]
    async fn team_needs_a_work_sign_in() {
        let store = Store::open(std::path::Path::new(":memory:")).unwrap();
        let err = team(&store).await.unwrap_err().to_string();
        assert_eq!(err, "Sign in with your work account first.");
    }
}
