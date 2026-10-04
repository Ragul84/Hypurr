//! Cost tracker: tokens and money per task, from what the agent reports. ACP agents send
//! token counts with each prompt response (`usage`) and, some of them, the session's cost
//! so far (`usage_update.cost`). When only tokens are known, the cost is estimated from
//! list prices (kv `costRates`, USD per million tokens) and marked as an estimate.

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Usage {
    pub input_tokens: u64,
    pub output_tokens: u64,
    pub cached_tokens: u64,
    pub total_tokens: u64,
    pub turns: u64,
    /// The cost the agent reported, summed over its sessions.
    pub reported_cost: Option<f64>,
    /// The current session's last reported cost (a new session starts from zero).
    pub session_cost: f64,
    /// Earlier sessions' reported cost.
    pub previous_cost: f64,
    pub currency: String,
    /// What the user sees: the reported cost, or an estimate.
    pub cost: f64,
    pub estimated: bool,
}

/// USD per million input / output tokens.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct Rate {
    pub input: f64,
    pub output: f64,
}

/// Rough list prices by agent family, for estimates only.
pub fn rate_for(backend: &str) -> Rate {
    let b = backend.to_ascii_lowercase();
    if b.contains("codex") || b.contains("openai") || b.contains("gemini") || b.contains("cursor") {
        Rate { input: 1.25, output: 10.0 }
    } else {
        // Claude Sonnet-class pricing; also the default for unknown agents.
        Rate { input: 3.0, output: 15.0 }
    }
}

impl Usage {
    /// A prompt response's `usage` (one turn).
    pub fn add_turn(&mut self, u: &Value, rate: Rate) {
        let n = |k: &str| u[k].as_u64().unwrap_or(0);
        let (input, output) = (n("inputTokens"), n("outputTokens") + n("thoughtTokens"));
        let cached = n("cachedReadTokens") + n("cachedWriteTokens");
        self.input_tokens += input;
        self.output_tokens += output;
        self.cached_tokens += cached;
        self.total_tokens += n("totalTokens").max(input + output + cached);
        self.turns += 1;
        self.refresh(rate);
    }

    /// A `usage_update` with `cost: {amount, currency}` (the session's total so far).
    pub fn set_session_cost(&mut self, amount: f64, currency: &str, rate: Rate) {
        if amount + 1e-9 < self.session_cost {
            // A new session started counting from zero.
            self.previous_cost += self.session_cost;
        }
        self.session_cost = amount;
        self.reported_cost = Some(self.previous_cost + amount);
        currency.clone_into(&mut self.currency);
        self.refresh(rate);
    }

    #[allow(clippy::cast_precision_loss)] // token counts are far below 2^52
    fn refresh(&mut self, rate: Rate) {
        if let Some(c) = self.reported_cost {
            self.cost = c;
            self.estimated = false;
        } else {
            // Cached tokens are billed at roughly a tenth of fresh input.
            self.cost = (self.input_tokens as f64 * rate.input
                + self.cached_tokens as f64 * rate.input * 0.1
                + self.output_tokens as f64 * rate.output)
                / 1_000_000.0;
            self.estimated = true;
            if self.currency.is_empty() {
                self.currency = "USD".into();
            }
        }
    }

    pub fn is_empty(&self) -> bool {
        self.turns == 0 && self.reported_cost.is_none()
    }

    /// What clients get on the task.
    pub fn public(&self) -> Value {
        json!({
            "inputTokens": self.input_tokens,
            "outputTokens": self.output_tokens,
            "totalTokens": self.total_tokens,
            "cost": (self.cost * 10_000.0).round() / 10_000.0,
            "currency": if self.currency.is_empty() { "USD" } else { &self.currency },
            "estimated": self.estimated,
            "turns": self.turns,
        })
    }
}

/// "$0.12" / "≈ $0.12" / "less than $0.01".
pub fn label(cost: f64, estimated: bool) -> String {
    let s = if cost > 0.0 && cost < 0.01 { "less than $0.01".to_owned() } else { format!("${cost:.2}") };
    if estimated { format!("≈ {s}") } else { s }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_estimate_and_reported_cost_wins() {
        let mut u = Usage::default();
        let r = rate_for("claude");
        u.add_turn(
            &serde_json::json!({"inputTokens": 1_000_000, "outputTokens": 100_000, "totalTokens": 1_100_000}),
            r,
        );
        assert!(u.estimated && (u.cost - 4.5).abs() < 1e-9, "{u:?}");
        u.set_session_cost(0.8, "USD", r);
        assert!(!u.estimated && (u.cost - 0.8).abs() < 1e-9);
        // A new session restarts its count; the total keeps the old one.
        u.set_session_cost(0.1, "USD", r);
        assert!((u.cost - 0.9).abs() < 1e-9);
        assert_eq!(label(0.004, true), "≈ less than $0.01");
        assert_eq!(label(1.234, false), "$1.23");
        assert_eq!(u.public()["totalTokens"], 1_100_000);
    }
}
