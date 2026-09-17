#!/usr/bin/env bash
# Integration tests for the command center. Runs ON the server as root.
#
# Unlike the artifact smoke tests, every assertion here exercises a live
# endpoint, credential, or integration. Exits non-zero on the first failure
# class so a broken deploy cannot be reported as green.
set -uo pipefail

readonly AGENT_ENV="/etc/colony-agent/agent.env"
readonly HOMARR="http://127.0.0.1:7575"
readonly KUMA_INTERNAL="http://127.0.0.1:3002"
readonly HOST_LAN="192.168.1.171"

failures=0
checks=0

pass() {
  checks=$((checks + 1))
  printf '  ok    %s\n' "$*"
}

fail() {
  checks=$((checks + 1))
  failures=$((failures + 1))
  printf '  FAIL  %s\n' "$*" >&2
}

section() {
  printf '\n== %s ==\n' "$*"
}

[[ "${EUID:-$(id -u)}" -eq 0 ]] || {
  printf 'run as root\n' >&2
  exit 77
}
[[ -f "$AGENT_ENV" ]] || {
  printf 'missing %s\n' "$AGENT_ENV" >&2
  exit 1
}
# shellcheck disable=SC1090
source "$AGENT_ENV"
CC_USER="${OPENCODE_SERVER_USERNAME:?}"
CC_PASS="${OPENCODE_SERVER_PASSWORD:?}"
CC_LOGIN="$(printf '%s' "$CC_USER" | tr '[:upper:]' '[:lower:]')"

http_code() {
  curl -sS -k -L -o /dev/null -w '%{http_code}' --max-time "${2:-10}" "$1" 2>/dev/null || echo 000
}

http_code_auth() {
  curl -sS -k -L -o /dev/null -w '%{http_code}' --max-time "${2:-10}" \
    -u "$CC_USER:$CC_PASS" "$1" 2>/dev/null || echo 000
}

expect_code() {
  local label="$1" url="$2" want="$3" got
  got="$(http_code_auth "$url")"
  if [[ "$want" == *"$got"* ]]; then
    pass "$label ($got)"
  else
    fail "$label expected one of [$want] got $got -- $url"
  fi
}

section "Caddy edge: unauthenticated must be refused"
for port in 3000 3001 4096 9091; do
  got="$(http_code "http://127.0.0.1:$port/")"
  if [[ "$got" == "401" ]]; then
    pass "port $port rejects anonymous (401)"
  else
    fail "port $port returned $got for anonymous request, expected 401"
  fi
done

section "Caddy edge: shared credentials must be accepted"
expect_code "dashboard :3000" "http://127.0.0.1:3000/" "200"
expect_code "uptime kuma :3001" "http://127.0.0.1:3001/" "200 302"
expect_code "opencode :4096" "http://127.0.0.1:4096/" "200"
expect_code "cockpit :9091" "http://127.0.0.1:9091/" "200"
expect_code "cockpit path on :3000" "http://127.0.0.1:3000/cockpit/" "200"
expect_code "ollama path on :3000" "http://127.0.0.1:3000/ollama/api/tags" "200"

section "Cockpit accepts the shared account through PAM"
ck_body="$(mktemp)"
ck_code="$(curl -sS -k -o "$ck_body" -w '%{http_code}' --max-time 15 \
  -u "$CC_LOGIN:$CC_PASS" "https://127.0.0.1:9090/cockpit/login" 2>/dev/null || echo 000)"
if [[ "$ck_code" == "200" ]] && grep -q 'csrf-token' "$ck_body"; then
  pass "cockpit PAM login for $CC_LOGIN issued a session token"
else
  fail "cockpit PAM login for $CC_LOGIN returned $ck_code without a token"
fi
rm -f "$ck_body"

section "Board tiles resolve"
# Each tile the board links to must answer. Redirects are followed and
# self-signed certificates tolerated, because that is what a browser does.
tile_failures=0
while IFS='|' read -r name url want; do
  [[ -n "$name" ]] || continue
  got="$(http_code "$url" 12)"
  if [[ "$want" == *"$got"* ]]; then
    pass "tile $name ($got)"
  else
    fail "tile $name expected [$want] got $got -- $url"
    tile_failures=$((tile_failures + 1))
  fi
done <<TILES
Portainer|https://127.0.0.1:9443|200 307
Pterodactyl|http://127.0.0.1:8080|200
Nginx Proxy Manager|http://127.0.0.1:81|200
Cockpit|https://127.0.0.1:9090|200
Homarr|http://127.0.0.1:7575|200 302 307
TILES

section "Uptime Kuma"
kuma_db_query() {
  docker exec uptime-kuma sqlite3 /app/data/kuma.db "$1" 2>/dev/null
}
sp_count="$(kuma_db_query "SELECT COUNT(*) FROM status_page WHERE published=1;")"
if [[ "${sp_count:-0}" -ge 1 ]]; then
  pass "published status page exists (Homarr integration needs one)"
else
  fail "no published Uptime Kuma status page; Homarr widget will render empty"
fi

mon_total="$(kuma_db_query "SELECT COUNT(*) FROM monitor WHERE active=1;")"
if [[ "${mon_total:-0}" -ge 1 ]]; then
  pass "$mon_total active monitors configured"
else
  fail "no active Uptime Kuma monitors"
fi

# A monitor that has never gone UP is a misconfigured monitor.
down="$(kuma_db_query "
  SELECT COALESCE(GROUP_CONCAT(name, ', '), '') FROM monitor m
  WHERE m.active=1 AND NOT EXISTS (
    SELECT 1 FROM heartbeat h WHERE h.monitor_id=m.id AND h.status=1
  );")"
if [[ -z "$down" ]]; then
  pass "every active monitor has reported UP at least once"
else
  fail "monitors never UP: $down"
fi

section "Homarr integrations"
homarr_db() {
  python3 - "$1" <<'PY'
import sqlite3, sys
db = "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"
conn = sqlite3.connect(db)
try:
    rows = conn.execute(sys.argv[1]).fetchall()
    print("\n".join(str(r[0]) for r in rows) if rows else "")
finally:
    conn.close()
PY
}
integ_count="$(homarr_db 'SELECT COUNT(*) FROM integration')"
if [[ "${integ_count:-0}" -ge 1 ]]; then
  pass "$integ_count Homarr integrations registered"
else
  fail "zero Homarr integrations; board has no live data"
fi

key_count="$(homarr_db 'SELECT COUNT(*) FROM apiKey')"
if [[ "${key_count:-0}" -ge 1 ]]; then
  pass "Homarr API key present (required for MCP)"
else
  fail "no Homarr API key; MCP and scripted config are unavailable"
fi

item_count="$(homarr_db 'SELECT COUNT(*) FROM item')"
if [[ "${item_count:-0}" -ge 5 ]]; then
  pass "$item_count board items placed"
else
  fail "only ${item_count:-0} board items; board is not built"
fi

section "Homarr MCP endpoint"
mcp_key="$(homarr_db "SELECT api_key FROM apiKey LIMIT 1")"
if [[ -n "$mcp_key" ]]; then
  mcp_resp="$(curl -sS -X POST --max-time 20 \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "ApiKey: $mcp_key" \
    -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' \
    "$HOMARR/api/mcp/mcp" 2>/dev/null || echo '')"
  if grep -q '"tools"' <<<"$mcp_resp"; then
    tool_n="$(grep -o '"name"' <<<"$mcp_resp" | wc -l | tr -d ' ')"
    pass "MCP tools/list returned ~$tool_n tools"
  else
    fail "MCP tools/list did not return a tool set"
  fi
else
  fail "cannot test MCP without an API key"
fi

section "OpenCode sees the Homarr MCP server"
if [[ -f /etc/colony-agent/opencode.json ]]; then
  if python3 -c "
import json,sys
cfg=json.load(open('/etc/colony-agent/opencode.json'))
mcp=cfg.get('mcp',{})
servers=mcp.get('servers',mcp)
sys.exit(0 if 'homarr' in servers else 1)
" 2>/dev/null; then
    pass "opencode.json registers the homarr MCP server"
  else
    fail "opencode.json has no homarr MCP server entry"
  fi
else
  fail "missing /etc/colony-agent/opencode.json"
fi

section "OpenCode is embeddable in the board"
hdrs="$(curl -sS -k -I --max-time 10 -u "$CC_USER:$CC_PASS" \
  "http://127.0.0.1:4096/" 2>/dev/null || echo '')"
if grep -qi 'x-frame-options: *deny' <<<"$hdrs"; then
  fail "OpenCode still sends X-Frame-Options: DENY; iframe widget will be blank"
else
  pass "no frame-busting X-Frame-Options on the OpenCode origin"
fi

section "DNS survives Pi-hole being stopped"
if docker ps --format '{{.Names}}' | grep -qx 'pihole'; then
  docker stop pihole >/dev/null 2>&1
  sleep 3
  if getent hosts github.com >/dev/null 2>&1; then
    pass "host DNS still resolves with Pi-hole stopped (fallback works)"
  else
    fail "host DNS broke when Pi-hole stopped; no working fallback resolver"
  fi
  docker start pihole >/dev/null 2>&1
  sleep 3
else
  printf '  skip  Pi-hole not installed yet\n'
fi

section "Memory headroom with Ollama loaded"
avail_mb="$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)"
readonly MIN_AVAIL_MB=1024
if ((avail_mb >= MIN_AVAIL_MB)); then
  pass "MemAvailable ${avail_mb}MB (floor ${MIN_AVAIL_MB}MB)"
else
  fail "MemAvailable ${avail_mb}MB is under the ${MIN_AVAIL_MB}MB floor; Minecraft TPS is at risk"
fi

swap_used_mb="$(free -m | awk '/^Swap:/{print $3}')"
if ((swap_used_mb < 512)); then
  pass "swap in use ${swap_used_mb}MB"
else
  fail "swap in use ${swap_used_mb}MB indicates memory pressure"
fi

section "Critical services still healthy"
for unit in command-center-caddy.service colony-agent.service \
  colony-autopilot.service ollama.service cockpit.socket; do
  if systemctl is-active --quiet "$unit"; then
    pass "$unit active"
  else
    fail "$unit is not active"
  fi
done

if [[ "$(docker inspect --format '{{.State.Running}}' \
  fea5b15c-2aa0-473f-b361-3885b286cd7e 2>/dev/null)" == "true" ]]; then
  pass "Minecraft container running"
else
  fail "Minecraft container is not running"
fi

printf '\n%s\n' "-----------------------------------------"
if ((failures == 0)); then
  printf 'command center integration: %d/%d checks passed\n' "$checks" "$checks"
  exit 0
fi
printf 'command center integration: %d of %d checks FAILED\n' "$failures" "$checks" >&2
exit 1
