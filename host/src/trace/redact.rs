//! Secrets, tokens, cookies and personal data are removed before anything is saved.

use serde_json::Value;
use std::sync::LazyLock;

/// Longest body kept in a trace, after redaction.
pub const MAX_BODY: usize = 4000;
const MASK: &str = "[redacted]";

const SECRET_KEYS: &[&str] = &[
    "password",
    "passwd",
    "pwd",
    "pass",
    "secret",
    "token",
    "otp",
    "pin",
    "cvv",
    "cvc",
    "card",
    "cardnumber",
    "aadhaar",
    "aadhar",
    "pan",
    "ssn",
    "email",
    "mail",
    "phone",
    "mobile",
    "msisdn",
    "dob",
    "birth",
    "address",
    "auth",
    "authorization",
    "session",
    "sessionid",
    "sid",
    "cookie",
    "apikey",
    "signature",
    "sig",
    "jwt",
    "refresh",
    "bearer",
    "credential",
    "ifsc",
    "upi",
    "vpa",
    "firstname",
    "lastname",
    "fullname",
];

/// Whether a field or header name holds something private.
pub fn secret_key(name: &str) -> bool {
    let n: String = name.chars().filter(char::is_ascii_alphanumeric).collect::<String>().to_ascii_lowercase();
    if n.is_empty() {
        return false;
    }
    if SECRET_KEYS.contains(&n.as_str()) {
        return true;
    }
    [
        "password", "secret", "token", "apikey", "cookie", "session", "auth", "email", "phone", "mobile", "aadhaar",
        "card",
    ]
    .iter()
    .any(|k| n.contains(k))
}

static EMAIL: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}").expect("email re"));
static PHONE: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new(r"(?:\+91[\s-]?)?\b[6-9]\d{9}\b").expect("phone re"));
static JWT: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b").expect("jwt re")
});
static AADHAAR: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new(r"\b\d{4}\s\d{4}\s\d{4}\b").expect("aadhaar re"));
static PAN: LazyLock<regex::Regex> = LazyLock::new(|| regex::Regex::new(r"\b[A-Z]{5}\d{4}[A-Z]\b").expect("pan re"));

/// Free text: emails, phone numbers, tokens and the usual secrets.
pub fn text(s: &str) -> String {
    let s = JWT.replace_all(s, MASK);
    let s = EMAIL.replace_all(&s, MASK);
    let s = AADHAAR.replace_all(&s, MASK);
    let s = PAN.replace_all(&s, MASK);
    let s = PHONE.replace_all(&s, MASK);
    crate::triage::redact(&s).0
}

fn value(v: &mut Value) {
    match v {
        Value::Object(m) => {
            for (k, x) in m.iter_mut() {
                if secret_key(k) {
                    *x = Value::String(MASK.into());
                } else {
                    value(x);
                }
            }
        }
        Value::Array(a) => a.iter_mut().for_each(value),
        Value::String(s) => *s = text(s),
        _ => {}
    }
}

fn cut(s: &str) -> String {
    if s.len() <= MAX_BODY {
        return s.to_owned();
    }
    let mut end = MAX_BODY;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}… ({} more characters)", &s[..end], s.len() - end)
}

/// A request or response body: JSON fields by name, forms by key, anything else as text.
pub fn body(raw: &str) -> String {
    let t = raw.trim();
    if t.is_empty() {
        return String::new();
    }
    if let Ok(mut v) = serde_json::from_str::<Value>(t) {
        value(&mut v);
        return cut(&serde_json::to_string_pretty(&v).unwrap_or_default());
    }
    if !t.contains(char::is_whitespace) && t.contains('=') && !t.starts_with('<') {
        return cut(&form(t));
    }
    cut(&text(t))
}

fn form(q: &str) -> String {
    q.split('&')
        .map(|kv| match kv.split_once('=') {
            Some((k, _)) if secret_key(k) => format!("{k}={MASK}"),
            Some((k, v)) => format!("{k}={}", text(v)),
            None => kv.to_owned(),
        })
        .collect::<Vec<_>>()
        .join("&")
}

/// A URL with private query values masked.
pub fn url(u: &str) -> String {
    let (base, frag) = u.split_once('#').map_or((u, None), |(a, b)| (a, Some(b)));
    let out = match base.split_once('?') {
        Some((p, q)) => format!("{}?{}", text(p), form(q)),
        None => text(base),
    };
    match frag {
        Some(f) if f.contains('=') => format!("{out}#{}", form(f)),
        Some(f) => format!("{out}#{f}"),
        None => out,
    }
}

/// Headers worth keeping, with private ones masked.
pub fn headers(h: &serde_json::Map<String, Value>) -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = h
        .iter()
        .filter(|(k, _)| {
            let k = k.to_ascii_lowercase();
            !k.starts_with("sec-")
                && !matches!(k.as_str(), "user-agent" | "accept-encoding" | "accept-language" | "connection")
        })
        .map(|(k, v)| {
            let v = v.as_str().unwrap_or_default();
            let v = if secret_key(k) || k.eq_ignore_ascii_case("set-cookie") { MASK.to_owned() } else { text(v) };
            (k.clone(), v)
        })
        .collect();
    out.sort();
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_fields_and_free_text() {
        let b = body(
            r#"{"sku":"SOCK-RED","qty":2,"password":"hunter2","user":{"email":"a@b.in","note":"call 9876543210"}}"#,
        );
        assert!(b.contains("SOCK-RED"));
        assert!(!b.contains("hunter2"));
        assert!(!b.contains("a@b.in"));
        assert!(!b.contains("9876543210"));
        let f = body("username=ravi&password=x1&qty=3");
        assert_eq!(f, "username=ravi&password=[redacted]&qty=3");
        let t = body(
            "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U ok",
        );
        assert!(!t.contains("eyJhbGci"));
        assert!(!body("PAN ABCDE1234F").contains("ABCDE1234F"));
    }

    #[test]
    fn urls_and_headers() {
        assert_eq!(
            url("http://localhost/api?access_token=abc&page=2"),
            "http://localhost/api?access_token=[redacted]&page=2"
        );
        let mut h = serde_json::Map::new();
        h.insert("Authorization".into(), "Bearer abc".into());
        h.insert("Cookie".into(), "sid=1".into());
        h.insert("Content-Type".into(), "application/json".into());
        h.insert("User-Agent".into(), "x".into());
        let out = headers(&h);
        assert_eq!(out.len(), 3);
        assert!(out.iter().all(|(k, v)| k == "Content-Type" || v == MASK));
    }

    #[test]
    fn long_bodies_are_cut() {
        let s = "a".repeat(MAX_BODY + 50);
        assert!(body(&s).ends_with("(50 more characters)"));
    }
}
