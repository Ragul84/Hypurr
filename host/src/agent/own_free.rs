//! "Keep going free": the user's own `OpenRouter` account for Hypurr Free, connected with
//! `OpenRouter`'s OAuth PKCE flow (<https://openrouter.ai/docs/use-cases/oauth-pkce>).
//!
//! The user signs in or signs up at `OpenRouter` themselves; Hypurr never creates accounts.
//! Headless mode: the phone opens the auth page, `OpenRouter` shows a one-time code, the user
//! pastes it back, and this computer (which holds the PKCE verifier) exchanges it for the
//! user's own key. The key stays in this computer's vault; requests with it go straight to
//! `OpenRouter`, so they never use the owner's key or the 10 Hypurr Free messages.

use anyhow::{Context, Result, bail};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::time::Duration;

use crate::store::Store;

pub const AUTH_URL: &str = "https://openrouter.ai/auth";
pub const EXCHANGE_URL: &str = "https://openrouter.ai/api/v1/auth/keys";
pub const API_BASE: &str = "https://openrouter.ai/api/v1";
/// vault slots
const KEY_SLOT: &str = "own_free_key";
const VERIFIER_SLOT: &str = "own_free_verifier";
/// kv: when the pending flow started (unix ms); `OpenRouter` codes expire after 10 minutes.
const STARTED: &str = "own_free_started";
const FLOW_TTL_MS: i64 = 15 * 60 * 1000;

/// What Hypurr Free / Fast run on with the user's own account (the same `:free` models the
/// gateway uses first).
pub const MODELS: &[(&str, &str)] =
    &[("hypurr-free", "cohere/north-mini-code:free"), ("hypurr-fast", "nvidia/nemotron-3.5-lightning:free")];

pub const NOT_FOR_COMPANY_CODE: &str = "Free models: for trying out, not for company code.";

fn challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

/// The page to open on the phone (`OpenRouter`'s headless PKCE mode: it shows a code to paste).
pub fn auth_url(verifier: &str) -> String {
    format!("{AUTH_URL}?code_challenge={}&code_challenge_method=S256&key_label=Hypurr", challenge(verifier))
}

/// Starts a connect flow: a fresh verifier in the vault, the URL for the phone.
pub fn start(store: &Store) -> Result<Value> {
    let mut raw = [0u8; 48];
    getrandom::fill(&mut raw).map_err(|_| anyhow::anyhow!("random bytes unavailable"))?;
    let verifier = URL_SAFE_NO_PAD.encode(raw);
    crate::market::vault::write(store, VERIFIER_SLOT, &verifier)?;
    store.kv_set(STARTED, &crate::store::now_ms().to_string())?;
    Ok(json!({"url": auth_url(&verifier), "note": NOT_FOR_COMPANY_CODE}))
}

/// The code the user pasted, cleaned up (they may paste the whole callback URL or extra spaces).
pub fn clean_code(input: &str) -> Option<String> {
    let s = input.trim();
    let s = s.split_once("code=").map_or(s, |(_, rest)| rest.split(['&', '#']).next().unwrap_or(rest));
    let s = s.trim().trim_matches(|c: char| c == '"' || c == '\'');
    (!s.is_empty() && s.len() <= 512 && s.chars().all(|c| c.is_ascii_alphanumeric() || "-_.~".contains(c)))
        .then(|| s.to_owned())
}

/// Exchanges the pasted code for the user's own key and keeps it in the vault.
pub async fn finish(store: &Store, code: &str) -> Result<Value> {
    finish_at(store, code, EXCHANGE_URL).await
}

pub async fn finish_at(store: &Store, code: &str, exchange_url: &str) -> Result<Value> {
    let Some(code) = clean_code(code) else {
        bail!("That doesn't look like the code OpenRouter showed. Copy it again.")
    };
    let started: i64 = store.kv_get(STARTED).and_then(|v| v.parse().ok()).unwrap_or(0);
    let Some(verifier) = crate::market::vault::read(store, VERIFIER_SLOT)?.filter(|v| !v.is_empty()) else {
        bail!("Start again: tap Keep going free.");
    };
    if crate::store::now_ms() - started > FLOW_TTL_MS {
        bail!("That code has expired. Start again: tap Keep going free.");
    }
    let client = reqwest::Client::builder().timeout(Duration::from_secs(20)).build()?;
    let res = client
        .post(exchange_url)
        .json(&json!({"code": code, "code_verifier": verifier, "code_challenge_method": "S256"}))
        .send()
        .await
        .context("reaching OpenRouter")?;
    let status = res.status();
    let v: Value = res.json().await.unwrap_or_default();
    if !status.is_success() {
        tracing::warn!(%status, "OpenRouter code exchange failed");
        bail!(match status.as_u16() {
            400 | 403 => "OpenRouter didn't accept that code (it may have expired). Start again: tap Keep going free.",
            _ => "Couldn't connect to OpenRouter right now. Try again in a minute.",
        });
    }
    let Some(key) = v["key"].as_str().filter(|k| !k.is_empty()) else {
        bail!("OpenRouter didn't return a key. Try again.")
    };
    crate::market::vault::write(store, VERIFIER_SLOT, "")?;
    save_key(store, key)
}

pub fn save_key(store: &Store, key: &str) -> Result<Value> {
    crate::market::vault::write(store, KEY_SLOT, key)?;
    sync(store);
    tracing::info!("own OpenRouter account connected for Hypurr Free");
    Ok(status_json(store))
}

/// The user's own key, when connected (needs the vault open).
pub fn key(store: &Store) -> Option<String> {
    crate::market::vault::read(store, KEY_SLOT).ok().flatten().filter(|k| !k.is_empty())
}

/// Whether a key is saved, without opening the vault.
pub fn connected(store: &Store) -> bool {
    store.kv_get(KEY_SLOT).is_some_and(|v| !v.is_empty())
}

/// Keeps [`crate::agent::builtin::OWN_FREE`] in step with the vault slot.
pub fn sync(store: &Store) {
    crate::agent::builtin::OWN_FREE.store(connected(store), std::sync::atomic::Ordering::Relaxed);
}

pub fn disconnect(store: &Store) -> Result<Value> {
    store.kv_set(KEY_SLOT, "")?;
    sync(store);
    Ok(status_json(store))
}

pub fn status_json(store: &Store) -> Value {
    json!({"connected": connected(store), "note": NOT_FOR_COMPANY_CODE})
}

/// Hypurr Agent config for the user's own account: the same `hypurr` provider and model ids,
/// pointed at `OpenRouter` with their key (so nothing else changes for the bot).
fn api_base() -> String {
    // Debug builds only: end-to-end tests point this at a local stand-in.
    #[cfg(debug_assertions)]
    if let Ok(v) = std::env::var("HYPURR_OWN_FREE_BASE") {
        return v;
    }
    API_BASE.to_owned()
}

pub fn agent_config(key: &str) -> Value {
    let models: serde_json::Map<String, Value> = MODELS
        .iter()
        .map(|(alias, upstream)| {
            let name = crate::agent::builtin::FREE_MODELS
                .iter()
                .find(|m| m.id.trim_start_matches("hypurr/") == *alias)
                .map_or(*alias, |m| m.name);
            ((*alias).to_owned(), json!({"id": upstream, "name": name}))
        })
        .collect();
    json!({
        "provider": {
            "hypurr": {
                "npm": "@ai-sdk/openai-compatible",
                "name": "Hypurr",
                "options": {"baseURL": api_base(), "apiKey": key, "headers": {"X-Title": "Hypurr"}},
                "models": models,
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_challenge_matches_rfc7636_example() {
        // Cross-checked with Python: base64url(sha256(verifier)) without padding.
        assert_eq!(
            challenge("dBjftJeZ4CVP-mA3Ow-kT2bTqcz1nZGk5b5TxZf8w4E"),
            "373Lxv4WyH4LoWnF8zThvvUAiBJujKh29_SGxYMSjVk"
        );
        let url = auth_url("dBjftJeZ4CVP-mA3Ow-kT2bTqcz1nZGk5b5TxZf8w4E");
        assert!(url.starts_with("https://openrouter.ai/auth?code_challenge=373Lxv4W"));
        assert!(url.contains("code_challenge_method=S256") && !url.contains("callback_url"));
    }

    #[test]
    fn pasted_codes_are_cleaned() {
        assert_eq!(clean_code("  abc-123_x \n").as_deref(), Some("abc-123_x"));
        assert_eq!(clean_code("https://x.test/cb?code=zz9&state=1").as_deref(), Some("zz9"));
        assert_eq!(clean_code(""), None);
        assert_eq!(clean_code("not a code!"), None);
    }

    #[test]
    fn own_account_config_keeps_hypurr_ids_and_points_at_openrouter() {
        let c = agent_config("k");
        let p = &c["provider"]["hypurr"];
        assert_eq!(p["options"]["baseURL"], API_BASE);
        assert_eq!(p["models"]["hypurr-free"]["id"], "cohere/north-mini-code:free");
        assert_eq!(p["models"]["hypurr-free"]["name"], "Hypurr Free");
        assert!(p["models"]["hypurr-fast"]["id"].as_str().unwrap().ends_with(":free"));
    }

    #[tokio::test]
    async fn connect_saves_the_key_and_disconnect_forgets_it() {
        use axum::{Json, Router, routing::post};
        let app = Router::new().route(
            "/keys",
            post(|Json(b): Json<Value>| async move {
                if b["code"] == "good"
                    && b["code_challenge_method"] == "S256"
                    && b["code_verifier"].as_str().is_some_and(|v| v.len() > 40)
                {
                    (axum::http::StatusCode::OK, Json(json!({"key": "sk-or-v1-test"})))
                } else {
                    (axum::http::StatusCode::FORBIDDEN, Json(json!({"error": "Invalid code"})))
                }
            }),
        );
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/keys", l.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(l, app).await.unwrap() });
        let store = Store::open(std::path::Path::new(":memory:")).unwrap();
        assert!(finish_at(&store, "good", &url).await.unwrap_err().to_string().contains("Start again"));
        start(&store).unwrap();
        assert!(finish_at(&store, "bad", &url).await.unwrap_err().to_string().contains("didn't accept"));
        let s = finish_at(&store, "good", &url).await.unwrap();
        assert_eq!(s["connected"], true);
        assert_eq!(key(&store).as_deref(), Some("sk-or-v1-test"));
        disconnect(&store).unwrap();
        assert!(!connected(&store));
        assert!(key(&store).is_none());
    }
}
