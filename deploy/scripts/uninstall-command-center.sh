#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

readonly cc_config_root="/etc/command-center"
readonly cc_compose_root="/opt/command-center"

purge=false

usage() {
  cat <<'EOF'
Usage: uninstall-command-center.sh [options]

Stops and removes the command center stack. Configuration and monitoring data
are preserved unless --purge is passed.

Options:
  --purge   Remove all config and persistent data
  --apply   Perform the uninstall
  --help    Show this help
EOF
}

while (($#)); do
  case "$1" in
    --purge) purge=true; shift ;;
    --apply) APPLY=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

require_apply_root

if [[ "$APPLY" != true ]]; then
  quote_command systemctl disable --now command-center-caddy.service
  quote_command docker compose -f "$cc_compose_root/docker-compose.yml" down
  quote_command rm -f /etc/systemd/system/command-center-caddy.service
  if [[ "$purge" == true ]]; then
    quote_command rm -rf "$cc_config_root" "$cc_compose_root"
    quote_command docker volume rm command-center_uptime-kuma-data
  fi
  exit 0
fi

systemctl disable --now command-center-caddy.service 2>/dev/null || true
if [[ -f "$cc_compose_root/docker-compose.yml" ]]; then
  docker compose -f "$cc_compose_root/docker-compose.yml" down || true
fi
rm -f /etc/systemd/system/command-center-caddy.service
systemctl daemon-reload

if [[ "$purge" == true ]]; then
  rm -rf "$cc_config_root" "$cc_compose_root"
  docker volume rm command-center_uptime-kuma-data 2>/dev/null || true
  log "command center purged"
else
  log "command center stopped; config preserved in $cc_config_root"
fi
