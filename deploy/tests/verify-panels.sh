#!/usr/bin/env bash
# Proves the embedded panels actually render, not merely respond.
#
# A panel can answer 401 with an empty body, or be refused by the browser
# because the framing rules do not list the parent origin. Both look like a
# white box and neither is visible in a status code alone, so this checks
# body size, frame-ancestors, and that every tile points at the origin the
# board is served from.
#
# Runs on the server.
set -uo pipefail

AGENT_ENV=${AGENT_ENV:-/etc/colony-agent/agent.env}
ORIGIN_HOST=192.168.1.171.sslip.io
AUTH_HOST=auth.${ORIGIN_HOST}:3010
DB=/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite
MIN_BYTES=500

set -a; . "$AGENT_ENV"; set +a
jar=$(mktemp); trap 'rm -f "$jar"' EXIT

payload=$(python3 -c 'import json,os; print(json.dumps({
 "username": os.environ["OPENCODE_SERVER_USERNAME"],
 "password": os.environ["OPENCODE_SERVER_PASSWORD"]}))')
code=$(curl -s -o /dev/null -w '%{http_code}' -c "$jar" -X POST \
  -H 'Content-Type: application/json' -H "Host: $AUTH_HOST" \
  -d "$payload" http://127.0.0.1:3010/api/login)
[[ "$code" == "200" ]] || { printf 'login failed: %s\n' "$code" >&2; exit 1; }
printf 'login ok\n\n'

fail=0

printf 'panel content:\n'
for spec in "4096:opencode" "7682:terminal"; do
  port=${spec%%:*}; path=${spec##*:}
  read -r code bytes < <(curl -s -o /dev/null \
    -w '%{http_code} %{size_download}' -b "$jar" -A 'Mozilla/5.0' \
    -H 'Accept: text/html' -H "Host: ${ORIGIN_HOST}:${port}" --max-time 20 \
    "http://127.0.0.1:${port}/")
  if [[ "$code" != "200" ]]; then
    printf '  %-12s HTTP %s  <-- not served\n' "$path" "$code"; fail=1
  elif (( bytes < MIN_BYTES )); then
    # 401 with an empty body was exactly how the blank panel presented.
    printf '  %-12s %s bytes  <-- empty, renders blank\n' "$path" "$bytes"; fail=1
  else
    printf '  %-12s %s bytes\n' "$path" "$bytes"
  fi
done

printf '\nframing allowance:\n'
for spec in "4096:opencode" "7682:terminal"; do
  port=${spec%%:*}; path=${spec##*:}
  csp=$(curl -s -D- -o /dev/null -b "$jar" -A 'Mozilla/5.0' \
    -H "Host: ${ORIGIN_HOST}:${port}" --max-time 20 \
    "http://127.0.0.1:${port}/" | grep -i '^content-security-policy' || true)
  # Same-origin framing is what the board relies on; 'self' covers it.
  # The board frames these from its own origin, which must be listed;
  # 'self' does not cover a different port.
  if ! grep -qF "${ORIGIN_HOST}:3000" <<<"$csp"; then
    printf '  %-12s parent origin not allowed\n' "$path"; fail=1
  else
    printf '  %-12s ok\n' "$path"
  fi
done

printf '\ntile targets:\n'
while read -r line; do
  printf '  %s\n' "$line"
  [[ "$line" == ok* ]] || fail=1
done < <(python3 - "$DB" "$ORIGIN_HOST" <<'PY'
import json, sqlite3, sys
db, host = sys.argv[1], sys.argv[2]
con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
want = (f"http://{host}:4096/", f"http://{host}:7682/")
for (raw,) in con.execute("SELECT options FROM item WHERE kind='iframe'"):
    url = json.loads(raw).get("json", {}).get("embedUrl", "")
    print(("ok   " if url in want else "BAD  ") + url)
PY
)

# An app served where another owns /api answers with the wrong server's
# response and renders blank, which no status check would reveal.
printf '\nown api reachable:\n'
for spec in "4096:/api/health:opencode"; do
  port=$(cut -d: -f1 <<<"$spec"); api=$(cut -d: -f2 <<<"$spec")
  name=$(cut -d: -f3 <<<"$spec")
  body=$(curl -s -b "$jar" -H "Host: ${ORIGIN_HOST}:${port}" --max-time 20 \
    "http://127.0.0.1:${port}${api}")
  if grep -qi "trpc\|homarr" <<<"$body"; then
    printf '  %-12s %s answered by the wrong app\n' "$name" "$api"; fail=1
  elif [[ -z "$body" ]]; then
    printf '  %-12s %s empty\n' "$name" "$api"; fail=1
  else
    printf '  %-12s %s ok (%s)\n' "$name" "$api" "$(head -c 60 <<<"$body")"
  fi
done

printf '\n'
if (( fail )); then
  printf 'panels are not fully working\n' >&2
  exit 1
fi
printf 'panels serve content, are framable, and point at the board origin\n'
