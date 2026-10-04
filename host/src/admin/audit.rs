//! The append-only audit log: who did what, when. Rows live in the SQLite `audit` table,
//! whose triggers refuse UPDATE and DELETE; each row carries the SHA-256 of the previous
//! one, so `auditLog` can show whether the history is intact. Secrets are never recorded.

use super::{Actor, NotAllowed};
use crate::store::{AuditRecord, Store, now_ms};
use serde_json::{Value, json};

fn append(store: &Store, actor: &str, name: &str, role: &str, action: &str, target: &str, detail: Value) {
    let rec = AuditRecord {
        at: now_ms(),
        actor: actor.into(),
        actor_name: name.into(),
        role: role.into(),
        action: action.into(),
        target: target.into(),
        detail,
    };
    if let Err(e) = store.audit_append(&rec) {
        tracing::warn!(action, error = format!("{e:#}"), "couldn't write the audit log");
    }
}

/// A record by a person.
pub fn record(store: &Store, actor: &Actor, action: &str, target: &str, detail: Value) {
    append(store, &actor.key, &actor.name, actor.role.as_str(), action, target, detail);
}

/// A record by Hypurr itself (the safety net, a spending limit).
pub fn system(store: &Store, action: &str, target: &str, detail: Value) {
    append(store, "hypurr", "Hypurr safety net", "system", action, target, detail);
}

/// The methods that leave a record when they succeed.
const AUDITED: &[&str] = &[
    "startTask",
    "finishTask",
    "rollbackTask",
    "taskCheckpoint",
    "respondPermission",
    "stop",
    "createBot",
    "updateBot",
    "deleteBot",
    "setPolicies",
    "setSafetySettings",
    "setRole",
    "setTeam",
    "setIntegrations",
    "saveProject",
    "removeProject",
    "saveTemplate",
    "deleteTemplate",
    "revokeDevice",
    "decideAccessRequest",
    "setApproval",
    "installConnector",
    "removeConnector",
    "installSkill",
    "removeSkill",
];

pub fn audited(method: &str) -> bool {
    AUDITED.contains(&method)
}

/// What to remember about the request before it runs (a deleted bot's name, the card answered).
pub fn before(store: &Store, method: &str, b: &Value) -> Value {
    let bot_name =
        |id: Option<&str>| id.and_then(|id| store.bot(id).ok().flatten()).map(|r| r.config.name).unwrap_or_default();
    match method {
        "respondPermission" => {
            let Some(e) = b["entryId"].as_str().and_then(|id| store.entry(id)) else { return Value::Null };
            let option = b["optionId"].as_str().unwrap_or_default();
            let kind = e.data["options"]
                .as_array()
                .into_iter()
                .flatten()
                .find(|o| o["optionId"] == option)
                .and_then(|o| o["kind"].as_str())
                .unwrap_or(if option.is_empty() { "cancelled" } else { "selected" });
            json!({
                "title": e.data["title"], "risk": e.data["risk"], "explain": e.data["explain"],
                "answer": kind, "botId": e.bot_id, "bot": bot_name(Some(&e.bot_id)),
            })
        }
        "deleteBot" | "stop" | "updateBot" => json!({"bot": bot_name(b["botId"].as_str())}),
        "revokeDevice" => {
            json!({"device": b["key"].as_str().and_then(|k| store.device(k)).map(|d| d.name)})
        }
        _ => Value::Null,
    }
}

fn s(v: &Value) -> String {
    v.as_str().unwrap_or_default().to_owned()
}

/// The record for a method that just succeeded: `(action, target, detail)`.
fn describe(method: &str, b: &Value, out: &Value, before: &Value) -> (String, String, Value) {
    let task = &out["task"];
    let (action, target, detail) = match method {
        "startTask" => (
            "task.start",
            s(&task["title"]),
            json!({"taskId": task["id"], "botId": task["botId"], "project": task["projectName"],
                   "agent": task["backend"], "branch": task["branch"], "issue": task["issue"]["key"]}),
        ),
        "finishTask" => (
            "task.finish",
            s(&task["title"]),
            json!({"taskId": task["id"], "openPr": b["openPr"], "notify": b["notify"], "learning": b["learning"]}),
        ),
        "rollbackTask" => {
            ("task.rollback", s(&task["title"]), json!({"taskId": b["taskId"], "checkpointId": b["checkpointId"]}))
        }
        "taskCheckpoint" => ("task.checkpoint", s(&task["title"]), json!({"taskId": b["taskId"]})),
        "respondPermission" => ("approval.answer", s(&before["title"]), before.clone()),
        "stop" => ("bot.stop", s(&before["bot"]), json!({"botId": b["botId"]})),
        "createBot" => (
            "bot.create",
            s(&out["bot"]["name"]),
            json!({"botId": out["bot"]["id"], "agent": out["bot"]["backend"], "kind": out["bot"]["kind"]}),
        ),
        "updateBot" => {
            let changed: Vec<&String> =
                b.as_object().into_iter().flat_map(|o| o.keys()).filter(|k| *k != "botId").collect();
            let mut d = json!({"botId": b["botId"], "changed": changed});
            for k in ["permission", "backend", "cwd"] {
                if !b[k].is_null() {
                    d[k] = b[k].clone();
                }
            }
            ("bot.update", s(&before["bot"]), d)
        }
        "deleteBot" => ("bot.delete", s(&before["bot"]), json!({"botId": b["botId"]})),
        "setPolicies" | "setSafetySettings" => ("policy.change", "Team rules".to_owned(), b.clone()),
        "setRole" => (
            "role.change",
            s(&out["people"]
                .as_array()
                .into_iter()
                .flatten()
                .find(|p| p["key"] == b["key"])
                .map_or(Value::Null, |p| p["name"].clone())),
            json!({"key": b["key"], "role": b["role"]}),
        ),
        "setTeam" => ("role.default", "New devices".to_owned(), json!({"defaultRole": b["defaultRole"]})),
        // Only which tools changed, never the keys themselves.
        "setIntegrations" => {
            let keys: Vec<&String> = b.as_object().into_iter().flat_map(|o| o.keys()).collect();
            ("integrations.change", "Work tools".to_owned(), json!({"changed": keys}))
        }
        "saveProject" => ("project.save", s(&b["path"]), json!({"name": b["name"]})),
        "removeProject" => ("project.remove", s(&b["path"]), Value::Null),
        "saveTemplate" => ("template.save", s(&b["title"]), json!({"id": b["id"]})),
        "deleteTemplate" => ("template.delete", s(&b["id"]), Value::Null),
        "revokeDevice" => ("device.revoke", s(&before["device"]), json!({"key": b["key"]})),
        "decideAccessRequest" => ("device.access", s(&b["id"]), json!({"approve": b["approve"]})),
        "setApproval" => ("device.approval", String::new(), b.clone()),
        "installConnector" | "removeConnector" => (
            if method == "installConnector" { "connector.install" } else { "connector.remove" },
            s(&b["id"]),
            Value::Null,
        ),
        "installSkill" | "removeSkill" => {
            (if method == "installSkill" { "skill.install" } else { "skill.remove" }, s(&b["id"]), Value::Null)
        }
        _ => (method, String::new(), Value::Null),
    };
    (action.to_owned(), target, detail)
}

/// Called by `api::dispatch` after an audited method succeeded.
pub fn after(store: &Store, actor: &Actor, method: &str, b: &Value, out: &Value, before: &Value) {
    let (action, target, detail) = describe(method, b, out, before);
    record(store, actor, &action, &target, detail);
}

/// A request refused by a role or a rule.
pub fn blocked(store: &Store, actor: &Actor, method: &str, why: &NotAllowed) {
    record(store, actor, "blocked", method, json!({"reason": why.0}));
}

/// `auditLog {before?, limit?, since?}`: newest first, plus whether the chain is intact.
pub fn list(store: &Store, b: &Value) -> anyhow::Result<Value> {
    let before = b["before"].as_i64().unwrap_or(i64::MAX);
    let limit = b["limit"].as_i64().unwrap_or(100).clamp(1, 500);
    let since = b["since"].as_i64().unwrap_or(0);
    let entries = store.audit_list(before, limit, since)?;
    let (count, broken) = store.audit_verify()?;
    Ok(json!({"entries": entries, "count": count, "intact": broken.is_none(), "brokenAt": broken}))
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;
    use crate::admin::Role;

    #[test]
    fn the_log_is_append_only_and_chained() {
        let store = Store::open(std::path::Path::new(":memory:")).unwrap();
        let a = Actor { key: "k".into(), name: "Priya's phone".into(), role: Role::Member };
        record(&store, &a, "task.start", "Fix checkout", json!({"taskId": "t"}));
        system(&store, "approval.blocked", "git push origin main", json!({"risk": "high"}));
        blocked(&store, &a, "setPolicies", &NotAllowed("no".into()));
        let l = list(&store, &json!({})).unwrap();
        assert_eq!(l["count"], 3);
        assert_eq!(l["intact"], true);
        let e = l["entries"].as_array().unwrap();
        assert_eq!(e[0]["action"], "blocked");
        assert_eq!(e[2]["actorName"], "Priya's phone");
        assert_eq!(e[2]["role"], "member");
        assert_eq!(list(&store, &json!({"before": e[0]["seq"], "limit": 1})).unwrap()["entries"][0]["actor"], "hypurr");
        // Edits and deletes are refused by the database itself.
        let raw = Store::raw_for_tests(&store);
        assert!(raw.execute("UPDATE audit SET target = 'x'", []).is_err());
        assert!(raw.execute("DELETE FROM audit", []).is_err());
        // A row smuggled in without the right hash shows up as a broken chain.
        raw.execute(
            "INSERT INTO audit(at, actor, actor_name, role, action, target, detail, prev, hash)
             VALUES(1, 'x', 'x', 'admin', 'task.start', 'x', 'null', 'bad', 'bad')",
            [],
        )
        .unwrap();
        drop(raw);
        let l = list(&store, &json!({})).unwrap();
        assert_eq!(l["intact"], false);
        assert_eq!(l["brokenAt"], 4);
    }

    #[test]
    fn integration_changes_never_record_secrets() {
        let (_, _, d) =
            describe("setIntegrations", &json!({"github": {"token": "ghp_secret"}}), &json!({}), &Value::Null);
        assert!(!d.to_string().contains("ghp_secret"));
        assert_eq!(d["changed"], json!(["github"]));
    }
}
