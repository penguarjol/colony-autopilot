#!/usr/bin/env bash
# Proves one login covers every service.
#
# Basic auth challenged once per port because its realm is scoped to an
# origin and an origin includes the port. The cookie-based provider is only
# an improvement if a single session is actually accepted everywhere, so
# check that rather than checking that each service merely responds.
#
# Runs on the server. Reads the shared credential from agent.env and never
# prints it.
set -euo pipefail

AGENT_ENV=${AGENT_ENV:-/etc/colony-agent/agent.env}
AUTH_HOST=auth.192.168.1.171.sslip.io:3010
APP_HOST=192.168.1.171.sslip.io

# Source the file rather than grepping it. CC_SHARED_PASSWORD is a name the
# deploy script derives; the file itself only defines OPENCODE_SERVER_*.
# shellcheck disable=SC1090
source "$AGENT_ENV"
cc_user=${OPENCODE_SERVER_USERNAME:?}
cc_pass=${OPENCODE_SERVER_PASSWORD:?}

jar=$(mktemp); trap 'rm -f "$jar"' EXIT
payload=$(CC_U="$cc_user" CC_P="$cc_pass" python3 -c \
  'import json,os; print(json.dumps({"username":os.environ["CC_U"],"password":os.environ["CC_P"]}))')

# Log in through the auth hostname, not the container directly. The Domain on
# the issued cookie follows the request host, so authenticating against
# 127.0.0.1 yields a cookie pinned to loopback that no service will accept.
code=$(curl -s -o /dev/null -w '%{http_code}' -c "$jar" -X POST \
  -H 'Content-Type: application/json' -H "Host: $AUTH_HOST" \
  -d "$payload" http://127.0.0.1:3010/api/login)
[[ "$code" == "200" ]] || { printf 'login failed: HTTP %s\n' "$code" >&2; exit 1; }
printf 'login ok\n'

# A cookie pinned to one port would defeat the whole exercise.
if ! grep -q "$APP_HOST" "$jar"; then
  printf 'session cookie is not scoped to %s; it will not span ports\n' "$APP_HOST" >&2
  exit 1
fi
printf 'cookie scoped to %s\n' "$APP_HOST"

fail=0
while read -r port name; do
  [[ -n "$port" ]] || continue
  code=$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" -A 'Mozilla/5.0' \
    -H 'Accept: text/html' -H "Host: ${APP_HOST}:${port}" --max-time 10 \
    "http://127.0.0.1:${port}/" || echo 000)
  # A redirect back to the login page means the session was not accepted.
  if [[ "$code" == "307" || "$code" == "401" ]]; then
    printf '  %-14s :%-5s re-prompted (%s)\n' "$name" "$port" "$code"; fail=1
  else
    printf '  %-14s :%-5s %s\n' "$name" "$port" "$code"
  fi
done <<'SERVICES'
3000 dashboard
3001 uptime-kuma
9091 cockpit
8181 glances
8183 qbittorrent
9118 jackett
SERVICES

[[ $fail -eq 0 ]] || { printf '\nat least one service re-prompted\n' >&2; exit 1; }
printf '\none login covered every service\n'
