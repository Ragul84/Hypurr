//! Rupee spend limits: per person per day and per month (with per-person overrides), and the
//! whole team per month. Agent costs are tracked in US dollars (what the agents report) and
//! converted with the plans config rate (`pricing/plans.json` `usdToInr`).

use super::{NotAllowed, Policies};
use crate::store::Store;
use crate::tasks::Task;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct PersonLimit {
    /// ₹ per day; 0 uses the team default.
    pub daily_inr: f64,
    /// ₹ per month; 0 uses the team default.
    pub monthly_inr: f64,
}

const DAY: i64 = 86_400_000;

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis().try_into().unwrap_or(0))
}

/// The start of this UTC month, from a day count (civil-from-days).
pub fn month_start(now: i64) -> i64 {
    let days = now.div_euclid(DAY);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    (days - (d - 1)) * DAY
}

fn tasks(store: &Store) -> Vec<Task> {
    store.tasks_data(5000).unwrap_or_default().into_iter().filter_map(|v| serde_json::from_value(v).ok()).collect()
}

fn starter(t: &Task) -> String {
    t.extra.get("startedBy").and_then(|v| v["key"].as_str()).unwrap_or("local").to_owned()
}

/// ₹ spent since `from` by one person (`None`: everyone).
pub fn spent_inr(store: &Store, who: Option<&str>, from: i64) -> f64 {
    let usd: f64 = tasks(store)
        .iter()
        .filter(|t| t.created_at >= from && who.is_none_or(|w| starter(t) == w))
        .map(|t| t.usage().cost)
        .sum();
    crate::pricing::inr(usd) + 0.0
}

/// The limits that apply to one person.
pub fn limits_for(p: &Policies, who: &str) -> (f64, f64) {
    let own = p.person_limits.get(who).cloned().unwrap_or_default();
    let daily = if own.daily_inr > 0.0 { own.daily_inr } else { p.person_daily_inr };
    let monthly = if own.monthly_inr > 0.0 { own.monthly_inr } else { p.person_monthly_inr };
    (daily, monthly)
}

/// Refuses new work when this person or the team is over a ₹ limit.
pub fn check(store: &Store, p: &Policies, who: &str) -> Result<(), NotAllowed> {
    let now = now_ms();
    let today = now - now.rem_euclid(DAY);
    let month = month_start(now);
    let (daily, monthly) = limits_for(p, who);
    let r = crate::pricing::rupees;
    if daily > 0.0 && spent_inr(store, Some(who), today) >= daily {
        return Err(NotAllowed(format!(
            "Your daily limit ({}) is used up. An admin can raise it in the Manager console.",
            r(daily)
        )));
    }
    if monthly > 0.0 && spent_inr(store, Some(who), month) >= monthly {
        return Err(NotAllowed(format!(
            "Your monthly limit ({}) is used up. An admin can raise it in the Manager console.",
            r(monthly)
        )));
    }
    if p.team_monthly_inr > 0.0 && spent_inr(store, None, month) >= p.team_monthly_inr {
        return Err(NotAllowed(format!(
            "The team's monthly limit ({}) is used up. An admin can raise it in the Manager console.",
            r(p.team_monthly_inr)
        )));
    }
    Ok(())
}

/// `spendByPerson`: ₹ today and this month per person, with their limits.
pub fn by_person(store: &Store) -> Value {
    let p = Policies::load(store);
    let now = now_ms();
    let (today, month) = (now - now.rem_euclid(DAY), month_start(now));
    let mut keys: Vec<String> = vec!["local".into()];
    keys.extend(store.devices().unwrap_or_default().into_iter().map(|d| d.key));
    for t in tasks(store) {
        let k = starter(&t);
        if !keys.contains(&k) {
            keys.push(k);
        }
    }
    let people: Vec<Value> = keys
        .iter()
        .map(|k| {
            let (daily, monthly) = limits_for(&p, k);
            let name = if k == "local" {
                super::LOCAL_NAME.to_owned()
            } else {
                store.device(k).map_or_else(|| "A removed device".to_owned(), |d| d.name)
            };
            json!({
                "key": k, "name": name,
                "todayInr": spent_inr(store, Some(k), today),
                "monthInr": spent_inr(store, Some(k), month),
                "dailyInr": daily, "monthlyInr": monthly,
                "custom": p.person_limits.contains_key(k),
            })
        })
        .collect();
    json!({
        "people": people,
        "teamMonthInr": spent_inr(store, None, month),
        "teamMonthlyInr": p.team_monthly_inr,
        "usdToInr": crate::pricing::usd_to_inr(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn month_start_is_the_first() {
        // 2026-10-20T10:00Z → 2026-10-01T00:00Z
        assert_eq!(month_start(1_792_490_400_000), 1_790_812_800_000);
        // 2024-02-29 (leap) → 2024-02-01
        assert_eq!(month_start(1_709_164_800_000), 1_706_745_600_000);
    }

    #[test]
    fn personal_limits_override_the_default() {
        let mut p = Policies { person_daily_inr: 100.0, person_monthly_inr: 2000.0, ..Policies::default() };
        p.person_limits.insert("k".into(), PersonLimit { daily_inr: 50.0, monthly_inr: 0.0 });
        assert_eq!(limits_for(&p, "k"), (50.0, 2000.0));
        assert_eq!(limits_for(&p, "other"), (100.0, 2000.0));
    }
}
