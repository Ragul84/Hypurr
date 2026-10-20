//! A click in plain words, the whole trace as markdown, and the flow as a Playwright test.

use super::model::{Call, Click, Trace};
use std::fmt::Write as _;

fn status_words(c: &Call) -> String {
    if c.blocked {
        return "blocked (that host isn't allowed for this project)".into();
    }
    if let Some(f) = &c.failed {
        return format!("failed ({f})");
    }
    let s = c.status.map_or_else(|| "no answer".into(), |s| format!("{s} {}", c.status_text).trim().to_owned());
    match c.ms {
        Some(ms) => format!("{s} in {ms} ms"),
        None => s,
    }
}

fn short_url(u: &str) -> String {
    let p = super::locate::path_of(u);
    match u.split_once('?') {
        Some((_, q)) => format!("{p}?{q}"),
        None => p,
    }
}

/// One click as numbered plain-words steps.
pub fn words(c: &Click) -> Vec<String> {
    let what = if c.label.is_empty() { format!("a {}", c.tag) } else { format!("\"{}\"", c.label) };
    let mut out = vec![format!("You clicked {what} on {}.", if c.page.is_empty() { "the page" } else { &c.page })];
    match (&c.handler, &c.handler_name) {
        (Some(h), _) => {
            let name = h.name.clone().unwrap_or_else(|| "its click handler".into());
            let via = if h.how == "code search" { " (found by name)" } else { "" };
            out.push(format!("The click runs {name} in {}{via}.", h.place().unwrap_or_default()));
        }
        (None, Some(n)) if !n.is_empty() && n != "anonymous" => {
            out.push(format!("The click runs {n}, but Hypurr couldn't find where it lives."));
        }
        _ => out.push("Hypurr couldn't see the click handler (it may be inline or minified).".into()),
    }
    if c.calls.is_empty() {
        out.push("No API calls were made.".into());
    }
    for call in &c.calls {
        let shown =
            if call.blocked { call.url.split('?').next().unwrap_or_default().to_owned() } else { short_url(&call.url) };
        let mut s = format!("It calls {} {shown} → {}.", call.method, status_words(call));
        if let Some(f) = &call.sent_from {
            let by = f.name.as_ref().map_or_else(String::new, |n| format!("{n} in "));
            let _ = write!(s, " Sent by {by}{}.", f.place().unwrap_or_default());
        }
        match &call.handled_by {
            Some(r) => {
                let h = r.handler.as_ref().map_or_else(String::new, |h| format!(" ({h})"));
                let _ = write!(s, " Handled by {} {} {} in {}:{}{h}.", r.framework, r.method, r.path, r.file, r.line);
            }
            None if !call.blocked => s.push_str(" The backend for this isn't in the repo."),
            None => {}
        }
        out.push(s);
    }
    if let Some(t) = c.appeared.first() {
        out.push(format!("Then the page shows \"{t}\"."));
    }
    out
}

fn fence(s: &str) -> String {
    format!("```\n{}\n```", s.replace("```", "'''"))
}

/// The whole trace as a markdown file.
pub fn markdown(t: &Trace) -> String {
    let mut m = format!("# Trace of {}\n\n", t.url);
    let _ = writeln!(
        m,
        "Recorded {} click{}. Secrets, tokens, cookies and personal data are redacted.\n",
        t.clicks.len(),
        if t.clicks.len() == 1 { "" } else { "s" }
    );
    if !t.blocked_hosts.is_empty() {
        let _ = writeln!(m, "Blocked (not allowed for this project): {}\n", t.blocked_hosts.join(", "));
    }
    for (i, c) in t.clicks.iter().enumerate() {
        let _ = writeln!(m, "## {}. {}\n", i + 1, if c.label.is_empty() { &c.tag } else { &c.label });
        for (n, l) in words(c).iter().enumerate() {
            let _ = writeln!(m, "{}. {l}", n + 1);
        }
        for call in &c.calls {
            let _ = writeln!(m, "\n### {} {}\n", call.method, short_url(&call.url));
            let _ = writeln!(m, "- Status: {}", status_words(call));
            for (k, v) in &call.request_headers {
                let _ = writeln!(m, "- Header {k}: {v}");
            }
            if !call.request_body.is_empty() {
                let _ = writeln!(m, "\nSent:\n\n{}", fence(&call.request_body));
            }
            if !call.response_body.is_empty() {
                let _ = writeln!(m, "\nGot back:\n\n{}", fence(&call.response_body));
            }
        }
        m.push('\n');
    }
    m
}

fn js(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_else(|_| "\"\"".into())
}

/// The flow as a Playwright test that checks the API calls as well as the page.
pub fn playwright(t: &Trace) -> String {
    let origin = {
        let u = &t.url;
        let rest = u.split_once("://").map_or(u.as_str(), |(_, r)| r);
        let host = rest.split('/').next().unwrap_or_default();
        u.split_once("://").map_or_else(String::new, |(s, _)| format!("{s}://{host}"))
    };
    let first = super::locate::path_of(&t.url);
    let mut s = String::from("// Made by Hypurr from a recorded trace. Review before adding it to your tests.\n");
    s.push_str("const { test, expect } = require('@playwright/test');\n\n");
    let _ = writeln!(s, "const BASE = process.env.BASE_URL || {};\n", js(&origin));
    let name = t.clicks.first().map_or("recorded flow", |c| c.label.as_str());
    let _ = writeln!(s, "test({}, async ({{ page }}) => {{", js(name));
    let _ = writeln!(s, "  await page.goto(BASE + {});", js(&first));
    for (i, c) in t.clicks.iter().enumerate() {
        let n = i + 1;
        let _ = writeln!(s, "\n  // {n}. {}", if c.label.is_empty() { &c.tag } else { &c.label });
        let real: Vec<&Call> = c.calls.iter().filter(|x| !x.blocked).collect();
        for (j, call) in real.iter().enumerate() {
            let path = super::locate::path_of(&call.url);
            let _ = writeln!(
                s,
                "  const call{n}_{j} = page.waitForResponse((r) => r.url().includes({}) && r.request().method() === {});",
                js(&path),
                js(&call.method)
            );
        }
        let target = match (c.tag.as_str(), c.label.is_empty()) {
            ("button" | "a", false) => {
                let role = if c.tag == "a" { "link" } else { "button" };
                format!("page.getByRole({}, {{ name: {} }})", js(role), js(&c.label))
            }
            (_, false) => format!("page.getByText({}, {{ exact: true }})", js(&c.label)),
            (_, true) => format!("page.locator({})", js(&c.selector)),
        };
        let _ = writeln!(s, "  await {target}.click();");
        for (j, call) in real.iter().enumerate() {
            let _ = writeln!(s, "  const res{n}_{j} = await call{n}_{j};");
            if let Some(st) = call.status {
                let _ = writeln!(s, "  expect(res{n}_{j}.status()).toBe({st});");
            }
            if call.request_body.trim_start().starts_with('{') && !call.request_body.contains("[redacted]") {
                let _ = writeln!(
                    s,
                    "  expect(res{n}_{j}.request().postDataJSON()).toEqual({});",
                    call.request_body.replace('\n', "\n  ")
                );
            }
        }
        if let Some(text) = c.appeared.first() {
            let _ = writeln!(s, "  await expect(page.getByText({})).toBeVisible();", js(text));
        }
    }
    s.push_str("});\n");
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::trace::model::{CodeRef, Trace};

    fn t() -> Trace {
        let mut t: Trace = serde_json::from_value(serde_json::json!({
            "id": "t", "project": "/p", "url": "http://localhost:5173/orders", "at": 0, "status": "done", "error": null,
            "clicks": [{"label": "Place order", "tag": "button", "role": null, "selector": "button", "page": "/orders",
                "handlerName": "handlePlaceOrder", "handlerFrame": null, "handler": null, "urlAfter": null,
                "appeared": ["Order 1 placed"],
                "calls": [{"method": "POST", "url": "http://localhost:5173/api/orders", "status": 201, "statusText": "Created",
                    "ms": 84, "requestBody": "{\n  \"qty\": 2\n}", "responseBody": "{\"id\":1}", "failed": null,
                    "sentFrom": null, "handledBy": null},
                    {"method": "GET", "url": "https://www.google-analytics.com/c", "status": null, "ms": null, "failed": null,
                    "blocked": true, "sentFrom": null, "handledBy": null}]}]
        }))
        .expect("trace");
        t.clicks[0].handler = Some(CodeRef {
            name: Some("handlePlaceOrder".into()),
            file: Some("client/src/pages/Orders.jsx".into()),
            line: Some(9),
            how: "source map".into(),
        });
        t
    }

    #[test]
    fn plain_words() {
        let w = words(&t().clicks[0]);
        assert_eq!(w[0], "You clicked \"Place order\" on /orders.");
        assert_eq!(w[1], "The click runs handlePlaceOrder in client/src/pages/Orders.jsx:9.");
        assert!(w[2].starts_with("It calls POST /api/orders → 201 Created in 84 ms."));
        assert!(w[3].contains("blocked"));
        assert_eq!(w.last().map(String::as_str), Some("Then the page shows \"Order 1 placed\"."));
        let md = markdown(&t());
        assert!(md.contains("### POST /api/orders"));
        assert!(md.contains("Sent:"));
    }

    #[test]
    fn exports_a_playwright_test() {
        let s = playwright(&t());
        assert!(s.contains("const BASE = process.env.BASE_URL || \"http://localhost:5173\";"));
        assert!(s.contains("await page.goto(BASE + \"/orders\");"));
        assert!(s.contains("r.url().includes(\"/api/orders\") && r.request().method() === \"POST\""));
        assert!(s.contains("page.getByRole(\"button\", { name: \"Place order\" }).click();"));
        assert!(s.contains("expect(res1_0.status()).toBe(201);"));
        assert!(s.contains("postDataJSON()).toEqual({"));
        assert!(s.contains("getByText(\"Order 1 placed\")).toBeVisible()"));
        assert!(!s.contains("google-analytics"));
    }
}
