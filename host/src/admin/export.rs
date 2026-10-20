//! Audit export (`auditExport {format: csv|jsonl, since?, until?}`): every record in the
//! append-only, hash-chained log, oldest first, for the company's own archive or SIEM.
//! Format: docs/it-kit/audit-export.md.

use crate::store::Store;
use anyhow::{Result, bail};
use serde_json::{Value, json};

pub const COLUMNS: &[&str] = &["seq", "at", "actor", "actorName", "role", "action", "target", "detail", "prev", "hash"];

fn cell(v: &Value) -> String {
    let s = match v {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    };
    // Spreadsheet formula injection: a leading = + - @ is made literal.
    let s = if s.starts_with(['=', '+', '-', '@']) { format!("'{s}") } else { s };
    format!("\"{}\"", s.replace('"', "\"\""))
}

/// All entries between `since` and `until` (ms), oldest first.
pub fn entries(store: &Store, since: i64, until: i64) -> Result<Vec<Value>> {
    let mut out = vec![];
    let mut before = i64::MAX;
    loop {
        let arr = store.audit_list(before, 500, since)?;
        if arr.is_empty() {
            break;
        }
        before = arr.last().and_then(|e| e["seq"].as_i64()).unwrap_or(0);
        out.extend(arr.into_iter().filter(|e| e["at"].as_i64().unwrap_or(0) <= until));
        if before <= 1 {
            break;
        }
    }
    out.reverse();
    Ok(out)
}

pub fn csv(entries: &[Value]) -> String {
    let mut s = COLUMNS.iter().map(|c| format!("\"{c}\"")).collect::<Vec<_>>().join(",");
    s.push_str("\r\n");
    for e in entries {
        let row: Vec<String> = COLUMNS.iter().map(|c| cell(&e[*c])).collect();
        s.push_str(&row.join(","));
        s.push_str("\r\n");
    }
    s
}

pub fn export(store: &Store, b: &Value) -> Result<Value> {
    let since = b["since"].as_i64().unwrap_or(0);
    let until = b["until"].as_i64().unwrap_or(i64::MAX);
    let list = entries(store, since, until)?;
    let (count, broken) = store.audit_verify()?;
    let (text, mime, ext) = match b["format"].as_str().unwrap_or("csv") {
        "csv" => (csv(&list), "text/csv", "csv"),
        "jsonl" => {
            (list.iter().map(Value::to_string).collect::<Vec<_>>().join("\n") + "\n", "application/x-ndjson", "jsonl")
        }
        _ => bail!("format must be csv or jsonl"),
    };
    Ok(json!({
        "text": text, "mime": mime, "fileName": format!("hypurr-audit.{ext}"),
        "rows": list.len(), "total": count, "intact": broken.is_none(), "brokenAt": broken,
    }))
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;
    use crate::admin::{Actor, Role, audit};

    #[test]
    fn exports_oldest_first_and_escapes() {
        let store = Store::open(std::path::Path::new(":memory:")).unwrap();
        let a = Actor { key: "k".into(), name: "Priya \"P\"".into(), role: Role::Member };
        audit::record(&store, &a, "task.start", "=HYPERLINK(\"x\")", json!({"taskId": "t"}));
        audit::system(&store, "policy.limit", "Fix", json!({}));
        let out = export(&store, &json!({"format": "csv"})).unwrap();
        let text = out["text"].as_str().unwrap();
        let lines: Vec<&str> = text.trim_end().split("\r\n").collect();
        assert_eq!(lines.len(), 3);
        assert!(lines[0].starts_with("\"seq\",\"at\""));
        assert!(lines[1].contains("\"Priya \"\"P\"\"\"") && lines[1].contains("\"'=HYPERLINK"));
        assert!(lines[2].contains("policy.limit"));
        assert_eq!(out["intact"], true);
        let j = export(&store, &json!({"format": "jsonl"})).unwrap();
        assert_eq!(j["text"].as_str().unwrap().lines().count(), 2);
        assert!(export(&store, &json!({"format": "xml"})).is_err());
    }
}
