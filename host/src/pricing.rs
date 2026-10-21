//! Plans and prices, from the repository's one config file (`pricing/plans.json`). Hypurr
//! never charges from here: payment providers are placeholders until keys exist
//! (docs/pricing/payments.md).

use serde_json::{Value, json};

pub const PLANS: &str = include_str!("../../pricing/plans.json");

pub fn config() -> Value {
    serde_json::from_str(PLANS).unwrap_or_else(|_| json!({"plans": []}))
}

/// Rupees per US dollar for showing agent costs in ₹ (config `usdToInr`).
pub fn usd_to_inr() -> f64 {
    config()["usdToInr"].as_f64().filter(|r| *r > 0.0).unwrap_or(84.0)
}

pub fn inr(usd: f64) -> f64 {
    (usd * usd_to_inr() * 100.0).round() / 100.0
}

/// `₹1,234` / `₹12.50`, Indian digit grouping.
pub fn rupees(amount: f64) -> String {
    #[allow(clippy::cast_possible_truncation)]
    let paise = (amount * 100.0).round() as i64;
    let (whole, frac) = (paise / 100, paise % 100);
    let digits = whole.abs().to_string();
    let grouped = if digits.len() <= 3 {
        digits
    } else {
        let (head, tail) = digits.split_at(digits.len() - 3);
        let mut parts: Vec<String> = vec![];
        let mut h = head.to_owned();
        while h.len() > 2 {
            parts.insert(0, h.split_off(h.len() - 2));
        }
        parts.insert(0, h);
        format!("{},{tail}", parts.join(","))
    };
    let sign = if whole < 0 { "-" } else { "" };
    if frac == 0 { format!("{sign}₹{grouped}") } else { format!("{sign}₹{grouped}.{:02}", frac.abs()) }
}

/// `pricing`: the plans for the Spend and You screens. Payment keys are never sent.
pub fn public() -> Value {
    let mut c = config();
    if let Some(p) = c.get_mut("payments") {
        let provider = p["provider"].clone();
        let ready = p[provider.as_str().unwrap_or("")]["keyId"].as_str().is_some_and(|k| !k.is_empty())
            || p[provider.as_str().unwrap_or("")]["publishableKey"].as_str().is_some_and(|k| !k.is_empty());
        *p = json!({"provider": provider, "ready": ready});
    }
    if let Some(o) = c.as_object_mut() {
        o.remove("_comment");
    }
    if let Some(o) = c.get_mut("creditPacks").and_then(Value::as_object_mut) {
        o.remove("_comment");
    }
    c
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    #[test]
    fn plans_parse_and_hide_keys() {
        let c = config();
        let ids: Vec<&str> = c["plans"].as_array().unwrap().iter().map(|p| p["id"].as_str().unwrap()).collect();
        assert_eq!(ids, ["free", "pro", "team"]);
        assert_eq!(c["currency"], "INR");
        let p = public();
        assert!(p["payments"].get("razorpay").is_none() && p["payments"]["ready"] == false);
        assert!(p.get("_comment").is_none());
        let packs = p["creditPacks"]["packs"].as_array().unwrap();
        assert!(packs.iter().all(|k| k["productId"].as_str().unwrap().starts_with("hypurr_credits_")
            && k["priceInr"].as_f64().unwrap() > 0.0));
        assert_eq!(p["payments"]["provider"], "revenuecat");
    }

    #[test]
    fn rupees_use_indian_grouping() {
        assert_eq!(rupees(199.0), "₹199");
        assert_eq!(rupees(1234.5), "₹1,234.50");
        assert_eq!(rupees(1_234_567.0), "₹12,34,567");
        assert_eq!(rupees(0.0), "₹0");
        assert!((inr(1.0) - usd_to_inr()).abs() < 0.001);
    }
}
