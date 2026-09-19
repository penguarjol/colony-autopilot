#!/usr/bin/env bash
# Artifact checks for the command center. These inspect files only; live
# behaviour is covered by command-center-integration.sh on the server.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

required_files=(
  deploy/command-center/Caddyfile
  deploy/command-center/docker-compose.yml
  deploy/command-center/services/phase-a.yml
  deploy/command-center/services/phase-b.yml
  deploy/command-center/services/phase-c.yml
  deploy/command-center/services/phase-c-immich.yml
  deploy/systemd/command-center-caddy.service
  deploy/scripts/install-command-center.sh
  deploy/scripts/uninstall-command-center.sh
  deploy/scripts/setup-cockpit-sso.sh
  deploy/scripts/pihole-dns-cutover.sh
  deploy/scripts/cc-deploy
  deploy/scripts/ccssh
  deploy/scripts/homarr_api.py
  deploy/scripts/configure-kuma.py
  deploy/scripts/build-homarr-board.py
  deploy/scripts/place-board-items.py
  deploy/tests/command-center-integration.sh
)

for relative_path in "${required_files[@]}"; do
  [[ -f "$repo_root/$relative_path" ]] || {
    printf 'missing required command center file: %s\n' "$relative_path" >&2
    exit 1
  }
done

for script in \
  deploy/scripts/install-command-center.sh \
  deploy/scripts/uninstall-command-center.sh \
  deploy/scripts/setup-cockpit-sso.sh \
  deploy/scripts/pihole-dns-cutover.sh \
  deploy/scripts/cc-deploy \
  deploy/scripts/ccssh \
  deploy/tests/command-center-integration.sh; do
  bash -n "$repo_root/$script"
done

for module in \
  deploy/scripts/homarr_api.py \
  deploy/scripts/configure-kuma.py \
  deploy/scripts/build-homarr-board.py \
  deploy/scripts/place-board-items.py; do
  python3 -c "import ast,sys; ast.parse(open(sys.argv[1]).read())" \
    "$repo_root/$module"
done

caddyfile="$repo_root/deploy/command-center/Caddyfile"
# The distro caddy.service owns 2019; sharing it put the proxy in a 2898-restart
# crash loop that took the whole dashboard down.
grep -Fq 'admin 127.0.0.1:2020' "$caddyfile"
grep -Fq 'basic_auth' "$caddyfile"
if grep -Eq '^\s*basicauth\b' "$caddyfile"; then
  printf 'basicauth is deprecated; use basic_auth\n' >&2
  exit 1
fi

# Basic auth is scoped per origin and an origin includes the port, so every
# service on its own port re-challenged. cc_auth must route through the
# cookie-based provider instead, and the login UI must sit outside it or the
# redirect loops.
if ! sed -n '/^(cc_auth)/,/^}/p' "$caddyfile" | grep -Fq 'forward_auth'; then
  printf 'cc_auth must use forward_auth; basic_auth prompts once per port\n' >&2
  exit 1
fi
grep -Fq '/api/auth/caddy' "$caddyfile"
# A cookie cannot be scoped to an IP literal, so IP arrivals must land on the
# hostname before the auth check or the session never sticks.
grep -Fq 'sslip.io' "$caddyfile"
if ! grep -A2 -E '^:3010 \{' "$caddyfile" | grep -Fq 'reverse_proxy'; then
  printf 'the sso login origin must not import cc_auth\n' >&2
  exit 1
fi

sso="$repo_root/deploy/command-center/services/sso.yml"
test -f "$sso"
python3 -c "import sys,yaml; yaml.safe_load(open(sys.argv[1]))" "$sso"
# The string level names are rejected at startup; the secret must be 32 chars.
grep -Fq 'LOG_LEVEL: "1"' "$sso"
# tinyauth scopes the cookie to APP_URL minus its first label, so the auth
# host must sit one level below the name services are reached by.
grep -Fq 'APP_URL: http://auth.192.168.1.171.sslip.io:3010' "$sso"
grep -Fq 'rand -hex 16' "$repo_root/deploy/scripts/cc-deploy"

# Recreating the board dropped every arrangement made in the UI. Placement is
# additive unless a rebuild is asked for explicitly.
placer="$repo_root/deploy/scripts/place-board-items.py"
if ! grep -Fq '"--reset" in sys.argv' "$placer"; then
  printf 'place-board-items must not delete an existing board by default\n' >&2
  exit 1
fi
# Homarr embeds the agent in an iframe widget; default headers refuse that.
grep -Fq 'frame-ancestors' "$caddyfile"
grep -Fq '127.0.0.1:7575' "$caddyfile"
# qBittorrent 401s on a Host mismatch and on any inbound Authorization header.
grep -Fq 'header_up Host {upstream_hostport}' "$caddyfile"

# Homarr reaches integrations over the Docker bridge, not loopback.
grep -Fq '172.17.0.1' "$repo_root/deploy/command-center/services/phase-a.yml"
grep -Fq '172.17.0.1' "$repo_root/deploy/command-center/docker-compose.yml"

# Every container needs a ceiling so a runaway service cannot starve the
# Minecraft JVM.
for phase in phase-a phase-b phase-c phase-c-immich; do
  grep -Fq 'mem_limit' \
    "$repo_root/deploy/command-center/services/$phase.yml"
done

# Image pulls are a memory burst; gating on MemAvailable alone took the host
# offline once already.
grep -Fq 'require_free_memory' "$repo_root/deploy/scripts/cc-deploy"
grep -Fq 'MemFree' "$repo_root/deploy/scripts/cc-deploy"

# A stopped Pi-hole must degrade to unfiltered DNS, never to no DNS.
grep -Fq 'nameserver 127.0.0.1' "$repo_root/deploy/scripts/pihole-dns-cutover.sh"
grep -Fq 'FALLBACK_1' "$repo_root/deploy/scripts/pihole-dns-cutover.sh"
grep -Fq -- '--rollback' "$repo_root/deploy/scripts/pihole-dns-cutover.sh"

# Cockpit authenticates through PAM against a real system account.
grep -Fq 'useradd' "$repo_root/deploy/scripts/setup-cockpit-sso.sh"
grep -Fq 'Origins' "$repo_root/deploy/scripts/setup-cockpit-sso.sh"

# The stored api_key column is a hash; the usable token is the plaintext.
grep -Fq 'KEY_FILE' "$repo_root/deploy/scripts/homarr_api.py"
grep -Fq 'apiKeys.create' "$repo_root/deploy/scripts/homarr_api.py"
# Homarr reports integration failures inside an HTTP 200.
grep -Fq 'isinstance(data.get("error"), dict)' \
  "$repo_root/deploy/scripts/homarr_api.py"

# Board names reject spaces; sections must be "empty" to accept items.
grep -Fq 'command-center' "$repo_root/deploy/scripts/place-board-items.py"
grep -Fq 'empty' "$repo_root/deploy/scripts/place-board-items.py"

# The agent is wired to the dashboard in both directions.
python3 -c "
import json, sys
cfg = json.load(open(sys.argv[1]))
mcp = cfg.get('mcp', {})
servers = mcp.get('servers', mcp)
assert 'homarr' in servers, 'opencode.json does not register the homarr MCP server'
assert '/api/mcp/mcp' in servers['homarr']['url'], 'wrong MCP endpoint'
" "$repo_root/deploy/config/opencode.json"
grep -Fq 'HOMARR_API_KEY' "$repo_root/deploy/scripts/run-colony-agent"
grep -Fq 'iframe' "$repo_root/deploy/scripts/place-board-items.py"

printf 'command center smoke tests passed\n'
