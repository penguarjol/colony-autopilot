#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

purge=false
purge_confirmation=""

usage() {
  cat <<'EOF'
Usage: uninstall.sh [options]

Stops and removes service artifacts while preserving config, state, logs,
backups, the application, and the Minecraft volume. The default is a dry run.

Options:
  --purge                         Also archive then remove config/state/logs
  --confirm-purge colony-autopilot  Required with --purge
  --apply                         Perform the uninstall
  --help                          Show this help
EOF
}

while (($#)); do
  case "$1" in
    --purge)
      purge=true
      shift
      ;;
    --confirm-purge)
      (($# >= 2)) || die "--confirm-purge requires a value"
      purge_confirmation="$2"
      shift 2
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

if [[ "$purge" == true && "$purge_confirmation" != colony-autopilot ]]; then
  die "--purge requires --confirm-purge colony-autopilot"
fi

require_apply_root

managed_paths=(
  /etc/systemd/system/colony-autopilot.service
  /etc/systemd/system/colony-autopilot.service.d/runtime-user.conf
  /etc/systemd/system/ollama.service.d/90-colony-autopilot.conf
  /etc/systemd/system/ollama.service.d/zz-colony-autopilot.conf
  /usr/local/lib/colony-autopilot/run-autopilot
  /usr/local/sbin/autopilotctl
)

if [[ "$APPLY" != true ]]; then
  quote_command systemctl disable --now colony-autopilot.service
  for path in "${managed_paths[@]}"; do
    quote_command rm -f -- "$path"
  done
  if [[ "$purge" == true ]]; then
    quote_command tar -czf \
      "$DEFAULT_BACKUP_ROOT/uninstall-config-$(timestamp_utc).tar.gz" \
      /etc/default/colony-autopilot /etc/colony-autopilot \
      /var/lib/colony-autopilot /var/log/colony-autopilot
    quote_command rm -rf -- /etc/default/colony-autopilot \
      /etc/colony-autopilot /var/lib/colony-autopilot \
      /var/log/colony-autopilot
  fi
  quote_command systemctl daemon-reload
  exit 0
fi

systemctl disable --now colony-autopilot.service 2>/dev/null || true
rm -f -- "${managed_paths[@]}"

if [[ "$purge" == true ]]; then
  require_command tar
  require_command sha256sum
  install -d -m 0750 "$DEFAULT_BACKUP_ROOT"
  archive="$DEFAULT_BACKUP_ROOT/uninstall-config-$(timestamp_utc).tar.gz"
  existing_paths=()
  for path in /etc/default/colony-autopilot /etc/colony-autopilot \
    /var/lib/colony-autopilot /var/log/colony-autopilot; do
    [[ -e "$path" ]] && existing_paths+=("${path#/}")
  done

  if ((${#existing_paths[@]})); then
    tar -czf "$archive" -C / "${existing_paths[@]}"
    (
      cd "$DEFAULT_BACKUP_ROOT"
      sha256sum "$(basename "$archive")" >"$(basename "$archive").sha256"
      sha256sum -c "$(basename "$archive").sha256"
    )
    rm -rf -- /etc/default/colony-autopilot /etc/colony-autopilot \
      /var/lib/colony-autopilot /var/log/colony-autopilot
  fi

  userdel colony-autopilot 2>/dev/null || true
fi

systemctl daemon-reload
log "uninstall complete; application and Minecraft data were not modified"
