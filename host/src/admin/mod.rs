//! Team admin: who may do what (roles per paired device), the team's rules (policies:
//! allowed agents, spending limits, approval levels, plus the safety net's protected
//! branches), an append-only audit log and the activity view. Everything is enforced
//! here, on the host; clients only render. Spec: docs/features/team-admin.md.

pub mod activity;
pub mod audit;

use crate::api::devices::Caller;
use crate::hub::Hub;
use crate::store::{EntryKind, Lane, Store};
use crate::tasks::risk::Risk;
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeMap;

/// What a person may do. Ordered: viewer < member < admin.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Default)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    /// Reads chats, tasks and costs; can't start, send or approve anything.
    Viewer,
    /// Starts and runs tasks within the team's rules.
    Member,
    /// Everything, including the rules, roles and the audit log.
    #[default]
    Admin,
}

impl Role {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Viewer => "viewer",
            Self::Member => "member",
            Self::Admin => "admin",
        }
    }
}

/// Who is calling, for permission checks and the audit log.
#[derive(Clone, Debug)]
pub struct Actor {
    pub key: String,
    pub name: String,
    pub role: Role,
}

impl Actor {
    pub fn json(&self) -> Value {
        json!({"key": self.key, "name": self.name, "role": self.role})
    }

    pub fn is_admin(&self) -> bool {
        self.role == Role::Admin
    }
}

/// Roles (kv `team`). The computer itself is always an admin. A device without its own
/// role gets `defaultRole`: admin by default, so a single person's phone keeps full control
/// until they turn this into a team.
#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct Team {
    pub default_role: Role,
    pub roles: BTreeMap<String, Role>,
}

impl Team {
    pub fn load(store: &Store) -> Self {
        store.kv_get("team").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
    }

    pub fn save(&self, store: &Store) -> Result<()> {
        store.kv_set("team", &serde_json::to_string(self)?)
    }

    pub fn role_of(&self, key: &str) -> Role {
        self.roles.get(key).copied().unwrap_or(self.default_role)
    }
}

pub const LOCAL_NAME: &str = "This computer";

pub fn actor(store: &Store, caller: &Caller) -> Actor {
    match caller {
        Caller::Local => Actor { key: "local".into(), name: LOCAL_NAME.into(), role: Role::Admin },
        Caller::Device { key, .. } => Actor {
            key: key.clone(),
            name: store.device(key).map_or_else(|| "A device".to_owned(), |d| d.name),
            role: Team::load(store).role_of(key),
        },
        Caller::Pairing { key } => Actor { key: key.clone(), name: "Pairing".into(), role: Role::Viewer },
    }
}

/// The team's rules (kv `policies`). Protected branches live in the safety settings and are
/// edited through `setPolicies` too.
#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Policies {
    /// Agent ids tasks and bots may use. Empty: any installed agent.
    pub allowed_agents: Vec<String>,
    /// USD per day across all tasks (UTC day). 0: no limit.
    pub daily_limit: f64,
    /// USD per task. 0: no limit.
    pub task_limit: f64,
    /// Requests at or above this risk always ask, even for bots set to approve automatically:
    /// `low` | `medium` | `high` | `never`. Empty: follow the safety net's `alwaysAskHigh`.
    pub ask_from: String,
    /// Requests at or above this risk need an admin to approve: `medium` | `high` | `off` / empty.
    pub admin_approves_from: String,
}

fn parse_risk(s: &str) -> Option<Risk> {
    match s {
        "low" => Some(Risk::Low),
        "medium" => Some(Risk::Medium),
        "high" => Some(Risk::High),
        _ => None,
    }
}

impl Policies {
    pub fn load(store: &Store) -> Self {
        store.kv_get("policies").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
    }

    fn save(&self, store: &Store) -> Result<()> {
        store.kv_set("policies", &serde_json::to_string(self)?)
    }

    /// The lowest risk that always asks (`None`: auto-approve bots never ask).
    pub fn ask_from_risk(&self, store: &Store) -> Option<Risk> {
        match self.ask_from.as_str() {
            "" => crate::tasks::SafetySettings::load(store).always_ask_high.then_some(Risk::High),
            s => parse_risk(s),
        }
    }

    /// The lowest risk only an admin may approve.
    pub fn admin_from(&self) -> Option<Risk> {
        parse_risk(&self.admin_approves_from)
    }

    pub fn agent_allowed(&self, id: &str) -> bool {
        self.allowed_agents.is_empty() || self.allowed_agents.iter().any(|a| a == id)
    }

    fn public(&self, store: &Store) -> Value {
        let ask = self.ask_from_risk(store).map_or("never", |r| match r {
            Risk::Low => "low",
            Risk::Medium => "medium",
            Risk::High => "high",
        });
        let admin = if self.admin_from().is_some() { self.admin_approves_from.as_str() } else { "off" };
        json!({
            "allowedAgents": self.allowed_agents,
            "dailyLimit": self.daily_limit,
            "taskLimit": self.task_limit,
            "askFrom": ask,
            "adminApprovesFrom": admin,
        })
    }
}

/// `policies`: the rules, the safety net settings, today's spend and the agents to pick from.
pub fn policies(store: &Store) -> Value {
    json!({
        "policies": Policies::load(store).public(store),
        "safety": crate::tasks::SafetySettings::load(store),
        "spentToday": crate::tasks::spent_today(store),
        "agents": crate::tasks::agents(),
    })
}

/// `setPolicies`: a partial update of the rules and the safety net settings.
pub fn set_policies(store: &Store, b: &Value) -> Result<Value> {
    let mut p = Policies::load(store);
    if let Some(list) = b["allowedAgents"].as_array() {
        p.allowed_agents =
            list.iter().filter_map(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned).collect();
    }
    for (key, slot) in [("dailyLimit", &mut p.daily_limit), ("taskLimit", &mut p.task_limit)] {
        if let Some(v) = b.get(key).filter(|v| !v.is_null()) {
            let n = v.as_f64().filter(|n| n.is_finite() && *n >= 0.0);
            let Some(n) = n else { bail!("{key} must be a number of dollars, 0 or more") };
            *slot = (n * 100.0).round() / 100.0;
        }
    }
    let mut safety = b.clone();
    if let Some(s) = b["askFrom"].as_str() {
        if !matches!(s, "low" | "medium" | "high" | "never") {
            bail!("askFrom must be low, medium, high or never");
        }
        s.clone_into(&mut p.ask_from);
        // Keep the safety net's own switch in step for clients that only know it.
        safety["alwaysAskHigh"] = (s != "never").into();
    } else if b["alwaysAskHigh"].is_boolean() {
        p.ask_from = String::new();
    }
    if let Some(s) = b["adminApprovesFrom"].as_str() {
        if !matches!(s, "low" | "medium" | "high" | "off" | "") {
            bail!("adminApprovesFrom must be low, medium, high or off");
        }
        p.admin_approves_from = if s == "off" { String::new() } else { s.to_owned() };
    }
    p.save(store)?;
    crate::tasks::set_safety(store, &safety)?;
    Ok(policies(store))
}

/// A request the caller's role or the team's rules don't allow (403).
#[derive(Debug)]
pub struct NotAllowed(pub String);

impl std::fmt::Display for NotAllowed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for NotAllowed {}

/// Only admins: the rules, roles, the audit log, and what changes the computer's setup.
const ADMIN_ONLY: &[&str] = &[
    "setPolicies",
    "setSafetySettings",
    "setRole",
    "setTeam",
    "auditLog",
    "activity",
    "setIntegrations",
    "testIntegration",
    "saveProject",
    "removeProject",
    "credentialUpdateConnector",
    "credentialSaveLogin",
    "credentialRemoveLogin",
    "credentialSetOnePassword",
    "setComposioKey",
    "composioConnect",
    "composioConnectFields",
    "composioConnection",
    "importConnectors",
    "installConnector",
    "removeConnector",
    "connectorSignIn",
    "connectorSignInFinish",
    "connectorSignOut",
    "installSkill",
    "removeSkill",
    "agentAuth",
    "agentAuthenticate",
    "setAgentEnv",
];

/// What a viewer may call: reading, plus its own device registration.
const VIEWER_OK: &[&str] = &[
    "hello",
    "sync",
    "history",
    "thread",
    "markRead",
    "readUpload",
    "tasks",
    "taskSetup",
    "routeTask",
    "taskCosts",
    "taskTemplates",
    "issues",
    "integrations",
    "safetySettings",
    "policies",
    "team",
    "usage",
    "routines",
    "routineSchedule",
    "memory",
    "screenStatus",
    "registerDevice",
    "unregisterDevice",
    "registerActivity",
    "refreshBackends",
    "agentModels",
    "marketConnectors",
    "marketSkills",
    "connectors",
    "skills",
    "credentialStatus",
    "composioStatus",
    "connectorInfo",
    "listDirs",
    "react",
];

fn role_allows(role: Role, method: &str) -> bool {
    match role {
        Role::Admin => true,
        Role::Member => !ADMIN_ONLY.contains(&method),
        Role::Viewer => VIEWER_OK.contains(&method),
    }
}

/// Checks the caller's role and the team's rules before a method runs. May add to the body
/// (`startedBy` on `startTask`, always set here so a client can't claim someone else).
pub fn authorize(store: &Store, actor: &Actor, method: &str, b: &mut Value) -> Result<(), NotAllowed> {
    if !role_allows(actor.role, method) {
        let what = if actor.role == Role::Viewer { "can only look" } else { "can't change this" };
        return Err(NotAllowed(format!(
            "You're a {} on this computer, so you {what}. Ask an admin.",
            actor.role.as_str()
        )));
    }
    let policies = Policies::load(store);
    match method {
        "respondPermission" => {
            let risk = b["entryId"]
                .as_str()
                .and_then(|id| store.entry(id))
                .and_then(|e| e.data["risk"].as_str().and_then(parse_risk));
            if let (Some(from), Some(risk)) = (policies.admin_from(), risk)
                && risk >= from
                && !actor.is_admin()
            {
                return Err(NotAllowed(format!(
                    "Your team's rules say an admin approves {} risk requests.",
                    risk_word(risk)
                )));
            }
        }
        "startTask" => {
            check_daily(store, &policies)?;
            if let Some(backend) = b["backend"].as_str().filter(|s| !s.is_empty()) {
                check_agent(&policies, backend)?;
            }
            b["startedBy"] = json!({"key": actor.key, "name": actor.name});
        }
        "send" => {
            if let Some(task) = b["botId"].as_str().and_then(|id| crate::tasks::Task::for_bot(store, id)) {
                check_daily(store, &policies)?;
                check_task(&policies, &task)?;
            }
        }
        "createBot" if b["kind"] != "group" => {
            if let Some(backend) = b["backend"].as_str().filter(|s| !s.is_empty()) {
                check_agent(&policies, backend)?;
            }
        }
        "updateBot" => {
            if let Some(backend) = b["backend"].as_str().filter(|s| !s.is_empty()) {
                check_agent(&policies, backend)?;
            }
        }
        _ => {}
    }
    Ok(())
}

fn risk_word(r: Risk) -> &'static str {
    match r {
        Risk::Low => "low",
        Risk::Medium => "medium",
        Risk::High => "high",
    }
}

fn money(x: f64) -> String {
    format!("${x:.2}")
}

fn check_agent(p: &Policies, backend: &str) -> Result<(), NotAllowed> {
    if p.agent_allowed(backend) {
        Ok(())
    } else {
        Err(NotAllowed(format!("Your team's rules don't allow the agent “{backend}”. Pick another one.")))
    }
}

fn check_daily(store: &Store, p: &Policies) -> Result<(), NotAllowed> {
    if p.daily_limit > 0.0 && crate::tasks::spent_today(store) >= p.daily_limit {
        return Err(NotAllowed(format!(
            "Today's spending limit ({}) is reached. An admin can raise it in Team admin.",
            money(p.daily_limit)
        )));
    }
    Ok(())
}

fn check_task(p: &Policies, task: &crate::tasks::Task) -> Result<(), NotAllowed> {
    if p.task_limit > 0.0 && task.usage().cost >= p.task_limit {
        return Err(NotAllowed(format!(
            "This task reached its spending limit ({}). An admin can raise it in Team admin.",
            money(p.task_limit)
        )));
    }
    Ok(())
}

/// After a task's usage changed: over a limit, stop its agent once and say why.
pub fn after_usage(hub: &Hub, task: &mut crate::tasks::Task) {
    let store = &hub.store;
    let p = Policies::load(store);
    if task.extra.get("limitHit").is_some_and(|v| v == true) {
        return;
    }
    let over = check_task(&p, task).err().or_else(|| check_daily(store, &p).err());
    let Some(NotAllowed(reason)) = over else { return };
    task.extra.insert("limitHit".into(), true.into());
    let _ = task.save(store);
    if hub.runtime(&task.bot_id).status == crate::hub::BotStatus::Working {
        let _ = hub.send_cmd(&task.bot_id, crate::agent::bot::Cmd::Stop);
    }
    hub.add_entry(
        &Lane::main(&task.bot_id),
        EntryKind::Notice,
        store.max_turn(&task.bot_id),
        &json!({"text": format!("Stopped: {reason}"), "style": "error"}),
    );
    audit::system(
        store,
        "policy.limit",
        &task.title,
        json!({"taskId": task.id, "botId": task.bot_id, "cost": task.usage().cost, "reason": reason}),
    );
}

/// Whether an approval card needs an admin (`data.needsAdmin`).
pub fn needs_admin(store: &Store, risk: Risk) -> bool {
    Policies::load(store).admin_from().is_some_and(|from| risk >= from)
}

/// `team`: you, the default role and everyone who can reach this computer.
pub fn team(store: &Store, you: &Actor) -> Result<Value> {
    let t = Team::load(store);
    let mut people = vec![json!({
        "key": "local", "name": LOCAL_NAME, "platform": std::env::consts::OS, "role": Role::Admin,
        "fixed": true, "you": you.key == "local",
    })];
    for d in store.devices()? {
        people.push(json!({
            "key": d.key, "name": d.name, "platform": d.platform, "role": t.role_of(&d.key),
            "ownRole": t.roles.contains_key(&d.key), "lastSeenAt": d.last_seen_at, "you": d.key == you.key,
        }));
    }
    Ok(json!({"you": you.json(), "defaultRole": t.default_role, "people": people}))
}

fn parse_role(v: &Value) -> Result<Role> {
    serde_json::from_value(v.clone()).map_err(|_| anyhow::anyhow!("role must be admin, member or viewer"))
}

/// `setRole {key, role}`: `role: null` goes back to the default.
pub fn set_role(store: &Store, you: &Actor, b: &Value) -> Result<Value> {
    let key = b["key"].as_str().unwrap_or_default();
    if key == "local" {
        bail!("this computer is always an admin");
    }
    if store.device(key).is_none() {
        bail!("unknown device");
    }
    let mut t = Team::load(store);
    if b["role"].is_null() {
        t.roles.remove(key);
    } else {
        t.roles.insert(key.to_owned(), parse_role(&b["role"])?);
    }
    t.save(store)?;
    team(store, you)
}

/// `setTeam {defaultRole}`: the role of devices without their own.
pub fn set_team(store: &Store, you: &Actor, b: &Value) -> Result<Value> {
    let mut t = Team::load(store);
    if !b["defaultRole"].is_null() {
        t.default_role = parse_role(&b["defaultRole"])?;
    }
    t.save(store)?;
    team(store, you)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    fn store() -> Store {
        Store::open(std::path::Path::new(":memory:")).unwrap()
    }

    fn device(store: &Store, key: &str) -> Actor {
        let d = crate::store::Device {
            key: key.into(),
            name: format!("{key}'s phone"),
            platform: "android".into(),
            source: crate::store::DeviceSource::Local,
            grant_id: None,
            scopes: vec![crate::store::Scope::Control],
            lease_until: None,
            created_at: 1,
            last_seen_at: None,
        };
        store.put_device(&d).unwrap();
        actor(store, &Caller::Device { key: key.into(), scopes: d.scopes })
    }

    #[test]
    fn roles_default_to_admin_until_the_team_says_otherwise() {
        let s = store();
        assert_eq!(actor(&s, &Caller::Local).role, Role::Admin);
        let a = device(&s, "a");
        assert_eq!(a.role, Role::Admin, "a lone phone keeps full control");
        assert_eq!(a.name, "a's phone");
        let local = actor(&s, &Caller::Local);
        set_team(&s, &local, &json!({"defaultRole": "member"})).unwrap();
        assert_eq!(device(&s, "a").role, Role::Member);
        set_role(&s, &local, &json!({"key": "a", "role": "viewer"})).unwrap();
        assert_eq!(device(&s, "a").role, Role::Viewer);
        set_role(&s, &local, &json!({"key": "a", "role": null})).unwrap();
        assert_eq!(device(&s, "a").role, Role::Member);
        assert!(set_role(&s, &local, &json!({"key": "local", "role": "viewer"})).is_err());
        assert!(set_role(&s, &local, &json!({"key": "nobody", "role": "viewer"})).is_err());
        assert!(set_role(&s, &local, &json!({"key": "a", "role": "owner"})).is_err());
        let t = team(&s, &local).unwrap();
        assert_eq!(t["people"].as_array().unwrap().len(), 2);
        assert_eq!(t["you"]["role"], "admin");
    }

    #[test]
    fn roles_gate_methods() {
        let s = store();
        let member = Actor { key: "m".into(), name: "M".into(), role: Role::Member };
        let viewer = Actor { key: "v".into(), name: "V".into(), role: Role::Viewer };
        let admin = Actor { key: "x".into(), name: "X".into(), role: Role::Admin };
        let mut b = json!({});
        for m in ["setPolicies", "setRole", "auditLog", "setIntegrations", "installSkill"] {
            assert!(authorize(&s, &member, m, &mut b).is_err(), "{m} is admin only");
            assert!(authorize(&s, &admin, m, &mut b).is_ok());
        }
        for m in ["send", "startTask", "respondPermission", "finishTask", "createBot"] {
            assert!(authorize(&s, &member, m, &mut json!({})).is_ok(), "members can {m}");
            let e = authorize(&s, &viewer, m, &mut json!({})).unwrap_err();
            assert!(e.0.contains("viewer"), "{e}");
        }
        for m in ["sync", "history", "tasks", "taskCosts", "policies", "team"] {
            assert!(authorize(&s, &viewer, m, &mut b).is_ok(), "viewers can {m}");
        }
        let mut start = json!({"goal": "x", "startedBy": {"key": "someone else"}});
        authorize(&s, &member, "startTask", &mut start).unwrap();
        assert_eq!(start["startedBy"]["key"], "m", "the host says who started it");
    }

    #[test]
    fn policies_round_trip_and_validate() {
        let s = store();
        let p = policies(&s);
        assert_eq!(p["policies"]["askFrom"], "high", "follows alwaysAskHigh by default");
        assert_eq!(p["policies"]["adminApprovesFrom"], "off");
        let p = set_policies(
            &s,
            &json!({"allowedAgents": ["claude", " "], "dailyLimit": 5.004, "taskLimit": 1, "askFrom": "medium",
                    "adminApprovesFrom": "high", "protectedBranches": ["main", "release/*"]}),
        )
        .unwrap();
        assert_eq!(p["policies"]["allowedAgents"], json!(["claude"]));
        assert_eq!(p["policies"]["dailyLimit"], 5.0);
        assert_eq!(p["policies"]["askFrom"], "medium");
        assert_eq!(p["safety"]["protectedBranches"], json!(["main", "release/*"]));
        let loaded = Policies::load(&s);
        assert_eq!(loaded.ask_from_risk(&s), Some(Risk::Medium));
        assert_eq!(loaded.admin_from(), Some(Risk::High));
        assert!(loaded.agent_allowed("claude") && !loaded.agent_allowed("codex"));
        assert!(needs_admin(&s, Risk::High) && !needs_admin(&s, Risk::Medium));
        set_policies(&s, &json!({"askFrom": "never"})).unwrap();
        assert!(!crate::tasks::SafetySettings::load(&s).always_ask_high, "kept in step");
        assert_eq!(Policies::load(&s).ask_from_risk(&s), None);
        assert!(set_policies(&s, &json!({"dailyLimit": -1})).is_err());
        assert!(set_policies(&s, &json!({"askFrom": "sometimes"})).is_err());
        assert!(set_policies(&s, &json!({"adminApprovesFrom": "always"})).is_err());
    }

    #[test]
    fn agent_rules_apply_to_tasks_and_bots() {
        let s = store();
        set_policies(&s, &json!({"allowedAgents": ["claude"]})).unwrap();
        let member = Actor { key: "m".into(), name: "M".into(), role: Role::Member };
        assert!(authorize(&s, &member, "startTask", &mut json!({"backend": "codex"})).is_err());
        assert!(authorize(&s, &member, "startTask", &mut json!({"backend": "claude"})).is_ok());
        assert!(authorize(&s, &member, "createBot", &mut json!({"backend": "codex"})).is_err());
        assert!(authorize(&s, &member, "createBot", &mut json!({"kind": "group", "backend": "codex"})).is_ok());
        assert!(authorize(&s, &member, "updateBot", &mut json!({"botId": "b", "backend": "codex"})).is_err());
        assert!(authorize(&s, &member, "updateBot", &mut json!({"botId": "b", "name": "x"})).is_ok());
    }
}
