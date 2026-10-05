//! The activity view: what everyone's agents did, for a manager. Built from the tasks table
//! and the audit log; nothing new is stored.

use super::{LOCAL_NAME, Team};
use crate::store::{Store, now_ms};
use anyhow::Result;
use serde_json::{Value, json};

const DAY: i64 = 86_400_000;

/// `activity {days?}`: people with their tasks and spend, recent tasks, and recent events.
pub fn activity(store: &Store, b: &Value) -> Result<Value> {
    let days = b["days"].as_i64().unwrap_or(7).clamp(1, 90);
    let now = now_ms();
    let since = now - days * DAY;
    let today_start = now - now.rem_euclid(DAY);
    let team = Team::load(store);

    // Everyone who can reach this computer, plus whoever started a task here.
    // In order: this computer, devices as paired, then people whose device was removed.
    let mut people: Vec<Value> = vec![];
    people.push(
        json!({"key": "local", "name": LOCAL_NAME, "role": "admin", "tasks": 0, "spend": 0.0, "approvals": 0, "blocked": 0}),
    );
    for d in store.devices()? {
        people.push(json!({"key": d.key, "name": d.name, "role": team.role_of(&d.key), "lastSeenAt": d.last_seen_at,
                   "tasks": 0, "spend": 0.0, "approvals": 0, "blocked": 0}));
    }
    let mut tasks = vec![];
    let (mut today_tasks, mut today_spend, mut spend) = (0, 0.0_f64, 0.0_f64);
    for t in store.tasks_data(500)?.into_iter().filter_map(|v| serde_json::from_value::<crate::tasks::Task>(v).ok()) {
        if t.created_at < since {
            continue;
        }
        let cost = t.usage().cost;
        spend += cost;
        if t.created_at >= today_start {
            today_tasks += 1;
            today_spend += cost;
        }
        let by = t.extra.get("startedBy").cloned().unwrap_or_else(|| json!({"key": "local", "name": LOCAL_NAME}));
        let key = by["key"].as_str().unwrap_or("local").to_owned();
        let i = people.iter().position(|p| p["key"] == key.as_str()).unwrap_or_else(|| {
            people.push(json!({"key": key, "name": by["name"], "role": team.role_of(&key), "tasks": 0,
                               "spend": 0.0, "approvals": 0, "blocked": 0, "removed": true}));
            people.len() - 1
        });
        let person = &mut people[i];
        person["tasks"] = (person["tasks"].as_i64().unwrap_or(0) + 1).into();
        person["spend"] = round(person["spend"].as_f64().unwrap_or(0.0) + cost).into();
        if tasks.len() < 50 {
            let p = t.public();
            tasks.push(json!({
                "taskId": t.id, "botId": t.bot_id, "title": t.title, "status": p["status"],
                "projectName": t.project_name, "backend": t.backend, "branch": t.branch,
                "startedBy": by, "usage": p["usage"], "pr": p["pr"], "createdAt": t.created_at,
                "updatedAt": t.updated_at,
            }));
        }
    }
    let events = store.audit_list(i64::MAX, 500, since)?;
    let (mut approvals, mut blocked) = (0, 0);
    for e in &events {
        let key = e["actor"].as_str().unwrap_or_default();
        let field = match e["action"].as_str().unwrap_or_default() {
            "approval.answer" => "approvals",
            "blocked" | "approval.blocked" | "policy.limit" => "blocked",
            _ => continue,
        };
        if field == "approvals" {
            approvals += 1;
        } else {
            blocked += 1;
        }
        if let Some(p) = people.iter_mut().find(|p| p["key"] == key) {
            p[field] = (p[field].as_i64().unwrap_or(0) + 1).into();
        }
    }
    Ok(json!({
        "days": days,
        "people": people,
        "tasks": tasks,
        "events": events.into_iter().take(60).collect::<Vec<_>>(),
        "totals": {"spend": round(spend), "todaySpend": round(today_spend), "todayTasks": today_tasks,
                   "approvals": approvals, "blocked": blocked},
    }))
}

fn round(x: f64) -> f64 {
    (x * 10_000.0).round() / 10_000.0
}
