# Live preview on the phone

When an agent starts a dev server (its output mentions `http://localhost:5173`, `127.0.0.1:8000`, …) and the port is
listening, the chat shows **Preview localhost:PORT**. Tapping it opens the site in an in-app browser.

How it travels: the phone's WebView intercepts requests to `localhost:PORT` and sends each one as a `previewFetch`
call over the existing end-to-end encrypted channel (direct Wi-Fi or the relay). The host fetches it from
`127.0.0.1:PORT` and sends the response back in ≤384 KiB slices. Nothing new is exposed on the network and no
hosting is involved.

Rules (host, `host/src/preview.rs`):
- loopback only; only ports this bot's own output mentioned and that are listening now;
- never the host's API port, nothing below 1024 except 80;
- paths must start with `/` (no `//host`, no CR/LF); only cookies, `accept*`, `content-type`, `authorization`,
  conditional and `x-*` headers are passed on; hop-by-hop headers are dropped;
- responses up to 32 MiB, cached 30 s for paging.

Limits: no web sockets, so hot reload doesn't push changes (tap Reload); request bodies from forms aren't sent
(WebView doesn't expose them), so previews are for looking, not for submitting forms; sites that hard-code another
host (e.g. an API on a second port) need that port previewed separately.

API: `previewPorts {botId}` → `{previews:[{port,url}]}`; `previewFetch {botId, port, path, method, headers, offset}` →
`{status, headers, body(base64), offset, total}`.
