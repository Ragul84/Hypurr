//! A small source-map reader: turns a position in the served (bundled) script back into the
//! original file and line, so a trace can point at `src/api.js:9` instead of `bundle.js:1`.

use serde_json::Value;

/// One decoded mapping: generated (line, column) → (source index, original line).
#[derive(Debug, Clone, Copy)]
struct Seg {
    col: i64,
    src: i64,
    line: i64,
}

#[derive(Debug, Default)]
pub struct SourceMap {
    sources: Vec<String>,
    lines: Vec<Vec<Seg>>,
}

fn b64(c: u8) -> Option<i64> {
    Some(i64::from(match c {
        b'A'..=b'Z' => c - b'A',
        b'a'..=b'z' => c - b'a' + 26,
        b'0'..=b'9' => c - b'0' + 52,
        b'+' => 62,
        b'/' => 63,
        _ => return None,
    }))
}

fn vlq(seg: &str) -> Vec<i64> {
    let mut out = Vec::new();
    let (mut val, mut shift) = (0i64, 0u32);
    for c in seg.bytes() {
        let Some(d) = b64(c) else { return out };
        val += (d & 31) << shift;
        if d & 32 == 0 {
            out.push(if val & 1 == 1 { -(val >> 1) } else { val >> 1 });
            val = 0;
            shift = 0;
        } else {
            shift += 5;
        }
    }
    out
}

impl SourceMap {
    pub fn parse(json: &str) -> Option<Self> {
        let v: Value = serde_json::from_str(json).ok()?;
        let root = v["sourceRoot"].as_str().unwrap_or_default();
        let sources = v["sources"]
            .as_array()?
            .iter()
            .map(|s| {
                let s = s.as_str().unwrap_or_default();
                if root.is_empty() || s.contains("://") {
                    s.to_owned()
                } else {
                    format!("{}/{s}", root.trim_end_matches('/'))
                }
            })
            .collect();
        let mappings = v["mappings"].as_str()?;
        let (mut src, mut line) = (0i64, 0i64);
        let mut lines = Vec::new();
        for l in mappings.split(';') {
            let mut col = 0i64;
            let mut segs = Vec::new();
            for s in l.split(',').filter(|s| !s.is_empty()) {
                let f = vlq(s);
                if f.is_empty() {
                    continue;
                }
                col += f[0];
                if f.len() >= 4 {
                    src += f[1];
                    line += f[2];
                    segs.push(Seg { col, src, line });
                }
            }
            lines.push(segs);
        }
        Some(Self { sources, lines })
    }

    /// Generated position (0-based line and column) → (original source, 1-based line).
    pub fn lookup(&self, line: u32, col: u32) -> Option<(String, u32)> {
        let segs = self.lines.get(line as usize)?;
        let col = i64::from(col);
        let seg = segs.iter().rev().find(|s| s.col <= col).or_else(|| segs.first())?;
        let src = self.sources.get(usize::try_from(seg.src).ok()?)?;
        Some((src.clone(), u32::try_from(seg.line + 1).ok()?))
    }
}

/// A `data:` source-map URL's JSON.
pub fn data_url(url: &str) -> Option<String> {
    use base64::Engine as _;
    let rest = url.strip_prefix("data:")?;
    let (meta, data) = rest.split_once(',')?;
    if meta.ends_with(";base64") {
        let bytes = base64::engine::general_purpose::STANDARD.decode(data.trim()).ok()?;
        String::from_utf8(bytes).ok()
    } else {
        Some(data.to_owned())
    }
}

/// `webpack://shop/./src/api.js?abc` → `src/api.js`: the part that can match a repo file.
pub fn clean_source(s: &str) -> String {
    let s = s.split(['?', '#']).next().unwrap_or_default();
    let s = s.split_once("://").map_or(s, |(_, r)| r);
    let s = s.trim_start_matches("/@fs").trim_start_matches('/');
    let s = s.strip_prefix("./").unwrap_or(s);
    // webpack://<package>/./src/x → drop the package name.
    let s = s.split_once("/./").map_or(s, |(_, r)| r);
    s.trim_start_matches("./").to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_vlq() {
        assert_eq!(vlq("AAAA"), vec![0, 0, 0, 0]);
        assert_eq!(vlq("SAAQ"), vec![9, 0, 0, 8]);
        assert_eq!(vlq("D"), vec![-1]);
        assert_eq!(vlq("gB"), vec![16]);
    }

    #[test]
    fn looks_up_lines() {
        // Line 0 maps to src/api.js line 1; line 1, col 4 → line 9 (0-based 8).
        let m = SourceMap::parse(r#"{"version":3,"sources":["webpack://shop/./src/api.js"],"mappings":"AAAA;IAQA"}"#)
            .expect("map");
        assert_eq!(m.lookup(0, 0), Some(("webpack://shop/./src/api.js".into(), 1)));
        assert_eq!(m.lookup(1, 10), Some(("webpack://shop/./src/api.js".into(), 9)));
        assert_eq!(m.lookup(5, 0), None);
        assert_eq!(clean_source("webpack://shop/./src/api.js?x"), "src/api.js");
        assert_eq!(clean_source("/@fs/home/u/shop/client/src/api.js"), "home/u/shop/client/src/api.js");
        assert_eq!(clean_source("../src/pages/Orders.jsx"), "../src/pages/Orders.jsx");
    }

    #[test]
    fn data_urls() {
        assert_eq!(data_url("data:application/json;base64,eyJhIjoxfQ=="), Some(r#"{"a":1}"#.into()));
        assert_eq!(data_url("data:application/json,{}"), Some("{}".into()));
    }
}
