#!/usr/bin/env bash
# Deploys the Hypurr gateway to YOUR Cloudflare account at hypurr.mindgains.ai.
# Safe to re-run: every step checks before it creates. See DEPLOY.md for what each step does.
#
#   cd cloud && npm ci
#   npx wrangler login                      # once, opens the browser
#   OPENROUTER_API_KEY=... REVENUECAT_WEBHOOK_AUTH=... ./scripts/deploy.sh
#
# Options: --env dev (deploys hypurr-dev.mindgains.ai instead), --dry-run (bundle only, no account changes).
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_NAME=""; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --env) ENV_NAME="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

WR=(npx wrangler)
if [ -n "$ENV_NAME" ]; then ENV_ARGS=(--env "$ENV_NAME"); DB_NAME="hypurr-$ENV_NAME"; HOST="hypurr-$ENV_NAME.mindgains.ai"
else ENV_ARGS=(--env ""); DB_NAME="hypurr"; HOST="hypurr.mindgains.ai"; fi

node -e 'process.exit(+process.versions.node.split(".")[0] >= 22 ? 0 : 1)' || { echo "wrangler needs Node 22+"; exit 1; }

if [ "$DRY" = 1 ]; then
  "${WR[@]}" deploy "${ENV_ARGS[@]}" --dry-run --outdir .wrangler/dry-run
  exit 0
fi

: "${OPENROUTER_API_KEY:?set OPENROUTER_API_KEY (your OpenRouter key)}"
: "${REVENUECAT_WEBHOOK_AUTH:?set REVENUECAT_WEBHOOK_AUTH (any long random string; paste the same into RevenueCat)}"

echo "== 1/6 Cloudflare sign-in"
"${WR[@]}" whoami >/dev/null 2>&1 || { echo "Run: npx wrangler login"; exit 1; }

echo "== 2/6 D1 database $DB_NAME"
ID=$("${WR[@]}" d1 list --json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s).find(d=>d.name===process.argv[1]);console.log(r?r.uuid:"")})' "$DB_NAME")
if [ -z "$ID" ]; then
  "${WR[@]}" d1 create "$DB_NAME" >/dev/null
  ID=$("${WR[@]}" d1 list --json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{console.log(JSON.parse(s).find(d=>d.name===process.argv[1]).uuid)})' "$DB_NAME")
fi
echo "   database_id $ID"
# Write the id into wrangler.toml in place of the placeholder for this environment.
if [ -n "$ENV_NAME" ]; then PH="REPLACE_WITH_YOUR_DEV_D1_DATABASE_ID"; else PH="REPLACE_WITH_YOUR_D1_DATABASE_ID"; fi
sed -i.bak "s/$PH/$ID/" wrangler.toml && rm -f wrangler.toml.bak

echo "== 3/6 migrations"
"${WR[@]}" d1 migrations apply "$DB_NAME" "${ENV_ARGS[@]}" --remote

echo "== 4/6 secrets (values never printed)"
printf '%s' "$OPENROUTER_API_KEY" | "${WR[@]}" secret put UPSTREAM_OPENROUTER_API_KEY "${ENV_ARGS[@]}"
printf '%s' "$REVENUECAT_WEBHOOK_AUTH" | "${WR[@]}" secret put REVENUECAT_WEBHOOK_AUTH "${ENV_ARGS[@]}"

echo "== 5/6 deploy (custom domain $HOST on the mindgains.ai zone)"
"${WR[@]}" deploy "${ENV_ARGS[@]}"

echo "== 6/6 health"
for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS "https://$HOST/v1/health" ; then echo; echo "Live: https://$HOST"; exit 0; fi
  sleep 15   # a new custom domain's certificate can take a minute or two
done
echo "Deployed, but https://$HOST/v1/health isn't answering yet; retry in a few minutes."
