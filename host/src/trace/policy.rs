//! Which hosts a trace may open: localhost and staging only, never production.
//!
//! - Local (localhost, loopback, private network) and staging-looking hosts (`staging`, `uat`,
//!   `qa`, `dev`, `test`, `preprod`, `sandbox` in the name) are fine once the project is approved.
//! - Any other host needs its own approval for this project.
//! - Hosts that look like production (`prod`, `production`, `live`) are refused outright.
//!
//! The same rule is applied to every request the page makes while recording: calls to hosts
//! that aren't allowed are blocked in the browser, so a staging page can't reach production.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostKind {
    Local,
    Staging,
    Other,
    Production,
}

impl HostKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Local => "local",
            Self::Staging => "staging",
            Self::Other => "other",
            Self::Production => "production",
        }
    }
}

/// The host of a URL (`https://a.b:8080/x` → `a.b`), lower-case, without the port.
pub fn host_of(url: &str) -> Option<String> {
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    let auth = rest.split(['/', '?', '#']).next()?;
    let auth = auth.rsplit_once('@').map_or(auth, |(_, h)| h);
    let host = if let Some(stripped) = auth.strip_prefix('[') {
        stripped.split(']').next()?.to_owned()
    } else {
        auth.split(':').next()?.to_owned()
    };
    let host = host.trim().to_ascii_lowercase();
    (!host.is_empty()).then_some(host)
}

fn private_ip(h: &str) -> bool {
    let parts: Vec<u8> = h.split('.').filter_map(|p| p.parse().ok()).collect();
    if parts.len() != 4 || h.split('.').count() != 4 {
        return false;
    }
    matches!(parts[..], [127 | 10, ..] | [192, 168, ..] | [0, 0, 0, 0])
        || (parts[0] == 172 && (16..=31).contains(&parts[1]))
}

const STAGING: &[&str] =
    &["staging", "stage", "stg", "uat", "qa", "dev", "test", "testing", "preprod", "sandbox", "sit"];
const PROD: &[&str] = &["prod", "production", "live"];

/// What kind of host this is.
pub fn classify(host: &str) -> HostKind {
    let h = host.trim_end_matches('.').to_ascii_lowercase();
    if h == "localhost"
        || h.ends_with(".localhost")
        || h.rsplit('.').next() == Some("local")
        || h == "::1"
        || private_ip(&h)
    {
        return HostKind::Local;
    }
    let words: Vec<&str> = h.split(['.', '-', '_']).collect();
    if words.iter().any(|w| PROD.contains(w)) {
        return HostKind::Production;
    }
    // Only the sub-domain part counts: "qa.example.com" is staging, "example.dev" isn't.
    let labels: Vec<&str> = h.split('.').collect();
    let sub = &labels[..labels.len().saturating_sub(2)];
    if sub.iter().any(|l| l.split(['-', '_']).any(|w| STAGING.contains(&w))) {
        return HostKind::Staging;
    }
    HostKind::Other
}

/// Whether a request to `url` may go out, given the hosts approved for this project.
pub fn allowed(url: &str, approved: &[String]) -> bool {
    let lower = url.to_ascii_lowercase();
    if lower.starts_with("data:") || lower.starts_with("blob:") || lower.starts_with("about:") {
        return true;
    }
    if !(lower.starts_with("http://")
        || lower.starts_with("https://")
        || lower.starts_with("ws://")
        || lower.starts_with("wss://"))
    {
        return false;
    }
    let Some(h) = host_of(url) else { return false };
    match classify(&h) {
        HostKind::Production => false,
        HostKind::Local | HostKind::Staging => true,
        HostKind::Other => approved.iter().any(|a| a.eq_ignore_ascii_case(&h)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hosts() {
        assert_eq!(host_of("http://localhost:5173/orders"), Some("localhost".into()));
        assert_eq!(host_of("https://user:pw@QA.Shop.in/x?y"), Some("qa.shop.in".into()));
        assert_eq!(host_of("http://[::1]:3000/"), Some("::1".into()));
        assert_eq!(classify("localhost"), HostKind::Local);
        assert_eq!(classify("192.168.1.20"), HostKind::Local);
        assert_eq!(classify("172.20.0.4"), HostKind::Local);
        assert_eq!(classify("app.local"), HostKind::Local);
        assert_eq!(classify("staging.shop.in"), HostKind::Staging);
        assert_eq!(classify("shop-uat.corp.example.com"), HostKind::Staging);
        assert_eq!(classify("qa.shop.in"), HostKind::Staging);
        assert_eq!(classify("shop.dev"), HostKind::Other);
        assert_eq!(classify("www.shop.in"), HostKind::Other);
        assert_eq!(classify("api.prod.shop.in"), HostKind::Production);
        assert_eq!(classify("shop-live.example.com"), HostKind::Production);
    }

    #[test]
    fn requests() {
        let none: Vec<String> = vec![];
        assert!(allowed("http://localhost:3001/api/orders", &none));
        assert!(allowed("https://staging.shop.in/api", &none));
        assert!(!allowed("https://www.shop.in/api", &none));
        assert!(allowed("https://www.shop.in/api", &["www.shop.in".into()]));
        assert!(!allowed("https://api.prod.shop.in/x", &["api.prod.shop.in".into()]));
        assert!(!allowed("file:///etc/passwd", &none));
        assert!(allowed("data:image/png;base64,AA", &none));
    }
}
