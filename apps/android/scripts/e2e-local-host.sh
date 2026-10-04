#!/usr/bin/env bash
# End-to-end check of the Android client against a real hypurr-host on this machine:
# starts a throwaway host (own HOME, port 19333), creates a bot over the loopback API,
# issues a pairing link and runs LocalHostE2ETest (pair → direct channel → sync, events, send).
#
#   apps/android/scripts/e2e-local-host.sh            # builds host/ in debug if needed
#   HYPURR_HOST_BIN=/path/to/hypurr-host apps/android/scripts/e2e-local-host.sh
#   KEEP_HOST=1 …                                       # leave the host running (emulator checks)
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)
port=${HYPURR_E2E_PORT:-19333}
bin=${HYPURR_HOST_BIN:-$root/host/target/debug/hypurr-host}
[ -x "$bin" ] || (cd "$root/host" && cargo build)

home=$(mktemp -d)
export HOME=$home HYPURR_HOME=$home/.hypurr
"$bin" serve --port "$port" --bind 0.0.0.0 >"$home/host.log" 2>&1 &
pid=$!
[ -n "${KEEP_HOST:-}" ] || trap 'kill $pid 2>/dev/null; rm -rf "$home"' EXIT
for _ in $(seq 1 100); do [ -s "$HYPURR_HOME/token" ] && break; sleep 0.2; done
token=$(cat "$HYPURR_HOME/token")
api() { curl -sS --fail-with-body -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "http://127.0.0.1:$port/api/$1" -d "$2"; }
for _ in $(seq 1 50); do api hello '{}' >/dev/null && break; sleep 0.2; done

mkdir -p "$home/project"
# Backends are detected right after start; retry until the host knows them.
for _ in $(seq 1 50); do
  res=$(api createBot "{\"name\":\"Reviewer\",\"backend\":\"claude-acp\",\"cwd\":\"$home/project\"}" 2>&1) && break
  sleep 0.3
done
bot=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["bot"]["id"])' "$res")
# A git project for the beginner-task part (stage A), driven by the host's scripted agent.
git -C "$home" init -q -b main shop
echo 'console.log("shop")' >"$home/shop/app.js"
git -C "$home/shop" add . && git -C "$home/shop" -c user.name=e2e -c user.email=e2e@local commit -qm init
link=$(HOME=$home "$bin" pair --port "$port" --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["pairingUrl"])')
echo "host pid $pid · bot $bot · $link"

cd "$root/apps/android"
HYPURR_E2E_LINK=$link HYPURR_E2E_BOT=$bot HYPURR_E2E_PROJECT=$home/shop \
  HYPURR_E2E_AGENT="python3 $root/host/tests/fake_task_agent.py" ./gradlew testDebugUnitTest --tests '*LocalHostE2ETest*' --rerun --console=plain -q || true
python3 - <<'PY'
import re, sys, pathlib
f = pathlib.Path("app/build/test-results/testDebugUnitTest/TEST-com.ragul84.hypurr.net.LocalHostE2ETest.xml")
s = f.read_text()
m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', s)
t, sk, fa, er = map(int, m.groups())
for line in re.findall(r"e2e: [^\n&]*", s):
    print(line)
print(f"LocalHostE2ETest: tests={t} skipped={sk} failures={fa} errors={er}")
sys.exit(1 if sk or fa or er else 0)
PY
