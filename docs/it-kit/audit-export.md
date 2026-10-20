# Audit export format

Team admin → Audit → Export (CSV or JSON lines), or the `auditExport` host method
(admin only) with `{format: "csv" | "jsonl", since?, until?}` (ms since epoch).

| Column | Meaning |
| --- | --- |
| `seq` | Record number, increasing, no gaps |
| `at` | Time, ISO 8601 UTC |
| `actor` | Device key of who did it (`local` = the laptop itself) |
| `actorName` | Their name at the time |
| `role` | `admin`, `member`, `viewer` |
| `action` | Host method, e.g. `setPolicies`, `approve`, `removeDevice` |
| `target` | What it acted on (task, device, folder) |
| `detail` | JSON with the details (secrets removed) |
| `prev` | Hash of the previous record |
| `hash` | SHA-256 over this record and `prev` |

Oldest first, UTF-8, RFC 4180 quoting. Cells starting with `= + - @` are prefixed with
`'` so spreadsheets do not run them as formulas.

**Is it intact?** The export reports `intact` and, if not, the first broken `seq`
(`brokenAt`). To check yourself, recompute each `hash` from the row and the `prev`
before it; any edited or deleted row breaks the chain from there on.
