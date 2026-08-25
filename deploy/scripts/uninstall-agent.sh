#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

purge=false
remove_model=false
confirmation=""

usage() {
  cat <<'EOF'
Usage: uninstall-agent.sh [options]

Stops and removes the local coding agent. The workspace, credentials, model,
state, and backups are preserved unless explicitly purged.

Options:
  --purge                    Archive and remove workspace, state, and credentials
  --confirm-purge TOKEN      Required token: colony-agent
  --remove-model             Remove qwen3.5:4b from Ollama
  --apply                    Perform the uninstall
  --help                     Show this help
EOF
}

while (($#)); do
  case "$1" in
    --purge)
      purge=true
      shift
      ;;
    --confirm-purge)
      (($# >= 2)) || die "--confirm-purge requires a token"
      confirmation="$2"
      shift 2
      ;;
    --remove-model)
      remove_model=true
      shift
      ;;
    --apply)
      APPLY=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

[[ "$purge" != true || "$confirmation" == colony-agent ]] ||
  die "--purge requires --confirm-purge colony-agent"
require_apply_root

managed_paths=(
  /etc/systemd/system/colony-agent.service
  /etc/systemd/system/colony-agent-operation.socket
  /etc/systemd/system/colony-agent-operation@.service
  /etc/sudoers.d/colony-agent
  /usr/local/lib/colony-agent/run-colony-agent
  /usr/local/lib/colony-agent/operation-dispatch
  /usr/local/lib/colony-agent/agent-operations.sh
  /usr/local/lib/colony-agent/ops/lib/common.sh
  /usr/local/lib/colony-agent/ops/scripts/backup.sh
  /usr/local/lib/colony-agent/ops/scripts/install-bridge.sh
  /usr/local/lib/colony-agent/ops/tests/agent-canary.sh
  /usr/local/bin/colony-agent-op
  /usr/local/sbin/authorize-agent-deploy
  /usr/local/sbin/colony-agent-cli
  /usr/local/sbin/colony-agent-deploy
  /usr/local/sbin/colony-agent-restart
  /usr/local/sbin/colony-agent-rollback
  /usr/local/sbin/colony-agent-status
  /opt/colony-agent/bin/opencode
)

if [[ "$APPLY" != true ]]; then
  quote_command systemctl disable --now colony-agent.service
  quote_command systemctl disable --now colony-agent-operation.socket
  for path in "${managed_paths[@]}"; do
    quote_command rm -f -- "$path"
  done
  quote_command rm -rf -- /opt/colony-agent/gradle
  quote_command rm -rf -- /opt/colony-agent/java
  quote_command ufw delete allow from 192.168.1.0/24 \
    to any port 4096 proto tcp
  [[ "$remove_model" == true ]] && quote_command ollama rm qwen3.5:4b
  if [[ "$purge" == true ]]; then
    quote_command tar -czf \
      "$DEFAULT_BACKUP_ROOT/colony-agent-purge-$(timestamp_utc).tar.gz" \
      /etc/colony-agent /var/lib/colony-agent /srv/colony-agent
    quote_command rm -rf -- /etc/colony-agent /var/lib/colony-agent \
      /var/lib/colony-agent-ops /var/log/colony-agent /srv/colony-agent
    quote_command userdel colony-agent
  fi
  exit 0
fi

systemctl disable --now colony-agent.service 2>/dev/null || true
systemctl disable --now colony-agent-operation.socket 2>/dev/null || true
rm -f -- "${managed_paths[@]}"
rm -rf -- /opt/colony-agent/gradle
rm -rf -- /opt/colony-agent/java
if command -v ufw >/dev/null 2>&1; then
  ufw delete allow from 192.168.1.0/24 to any port 4096 proto tcp \
    >/dev/null 2>&1 || true
fi
[[ "$remove_model" != true ]] || ollama rm qwen3.5:4b

if [[ "$purge" == true ]]; then
  archive="$DEFAULT_BACKUP_ROOT/colony-agent-purge-$(timestamp_utc).tar.gz"
  tar -czf "$archive" --ignore-failed-read \
    /etc/colony-agent /var/lib/colony-agent /srv/colony-agent
  rm -rf -- /etc/colony-agent /var/lib/colony-agent \
    /var/lib/colony-agent-ops /var/log/colony-agent /srv/colony-agent
  id colony-agent >/dev/null 2>&1 && userdel colony-agent
fi

systemctl daemon-reload
log "local coding agent uninstalled"
