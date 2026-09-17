#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(cd "$script_dir/.." && pwd)"
source "$deploy_dir/lib/common.sh"

readonly cc_config_root="/etc/command-center"
readonly cc_compose_root="/opt/command-center"
readonly agent_env="/etc/colony-agent/agent.env"

usage() {
  cat <<'EOF'
Usage: install-command-center.sh [options]

Installs the command center: Caddy reverse proxy, Homepage dashboard, and
Uptime Kuma monitoring. The default is a dry run.

Options:
  --apply   Perform the installation
  --help    Show this help
EOF
}

while (($#)); do
  case "$1" in
    --apply) APPLY=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

require_apply_root

if [[ "$APPLY" != true ]]; then
  quote_command apt-get install -y caddy
  quote_command install -d -m 0750 "$cc_config_root" "$cc_compose_root"
  quote_command install -m 0640 Caddyfile "$cc_config_root/Caddyfile"
  quote_command install -m 0640 caddy.env "$cc_config_root/caddy.env"
  quote_command cp -r docker-compose.yml homepage "$cc_compose_root/"
  quote_command docker compose -f "$cc_compose_root/docker-compose.yml" up -d
  quote_command systemctl enable --now command-center-caddy.service
  exit 0
fi

require_command docker
require_command openssl

[[ -f "$deploy_dir/command-center/Caddyfile" ]] ||
  die "missing Caddyfile"
[[ -f "$deploy_dir/command-center/docker-compose.yml" ]] ||
  die "missing docker-compose.yml"
[[ -f "$deploy_dir/systemd/command-center-caddy.service" ]] ||
  die "missing command-center-caddy.service"

if ! command -v caddy >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' |
      gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' |
      tee /etc/apt/sources.list.d/caddy-stable.list
    apt-get update
    apt-get install -y caddy
  else
    die "caddy is not installed and apt-get is not available"
  fi
fi

# The .deb enables its own caddy.service, which binds the admin endpoint on
# 127.0.0.1:2019 and leaves command-center-caddy in a permanent restart loop.
systemctl disable --now caddy.service 2>/dev/null || true

if [[ -f "$agent_env" ]]; then
  source "$agent_env"
  cc_user="${OPENCODE_SERVER_USERNAME:-opencode}"
  cc_pass="${OPENCODE_SERVER_PASSWORD:-}"
  [[ -n "$cc_pass" ]] || die "OPENCODE_SERVER_PASSWORD is empty in agent.env"
else
  cc_user="opencode"
  cc_pass="$(openssl rand -hex 24)"
  log "no agent.env found; generated standalone credentials"
fi

cc_hash="$(caddy hash-password --plaintext "$cc_pass")"

install -d -m 0750 "$cc_config_root"
install -d -m 0755 "$cc_compose_root"
install -d -m 0755 /var/lib/caddy /var/log/caddy

install -m 0640 "$deploy_dir/command-center/Caddyfile" \
  "$cc_config_root/Caddyfile"

cat >"$cc_config_root/caddy.env" <<EOF
CC_AUTH_USER=$cc_user
CC_AUTH_HASH=$cc_hash
EOF
chmod 0640 "$cc_config_root/caddy.env"

install -m 0644 "$deploy_dir/systemd/command-center-caddy.service" \
  /etc/systemd/system/command-center-caddy.service

cp "$deploy_dir/command-center/docker-compose.yml" \
  "$cc_compose_root/docker-compose.yml"
if [[ -d "$deploy_dir/command-center/services" ]]; then
  cp -r "$deploy_dir/command-center/services" "$cc_compose_root/services"
fi

caddy validate --config "$cc_config_root/Caddyfile" --adapter caddyfile ||
  die "Caddyfile failed validation; refusing to restart the proxy"

docker compose -f "$cc_compose_root/docker-compose.yml" up -d

systemctl daemon-reload
systemctl enable --now command-center-caddy.service
sleep 2
systemctl is-active --quiet command-center-caddy.service ||
  die "command-center-caddy failed to start; check journalctl -u command-center-caddy"

if systemctl is-active --quiet colony-agent-proxy.socket 2>/dev/null; then
  systemctl disable --now colony-agent-proxy.socket 2>/dev/null || true
  systemctl stop colony-agent-proxy.service 2>/dev/null || true
  log "disabled colony-agent-proxy (superseded by command center)"
fi

log "command center installed at http://192.168.1.171:3000"
log "credentials: user=$cc_user (password from agent.env or generated above)"
