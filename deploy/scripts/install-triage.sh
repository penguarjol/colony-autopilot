#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(cd "$script_dir/.." && pwd)"
source "$deploy_dir/lib/common.sh"

readonly queue_dir="/var/lib/colony-triage/queue"
readonly watch_conf="/etc/colony-triage/watch.conf"
readonly dropin_marker="zz-colony-triage.conf"

replace_config=false

usage() {
  cat <<'EOF'
Usage: install-triage.sh [options]

Installs the hard-error trigger subsystem: OnFailure drop-ins for watched
units, a periodic log scanner, and the incident queue. Triggers only capture
evidence; they never restart services or invoke the agent. The default is a
dry run.

Options:
  --replace-config   Overwrite an existing watch.conf
  --apply            Perform the installation
  --help             Show this help
EOF
}

while (($#)); do
  case "$1" in
    --replace-config) replace_config=true; shift ;;
    --apply) APPLY=true; shift ;;
    --help | -h) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

require_apply_root

source_conf="$deploy_dir/config/triage-watch.conf"
[[ -f "$source_conf" ]] || die "missing triage watch config"

# The installed config is authoritative once present, so an operator can add a
# unit without the repo copy silently reverting it on the next install.
effective_conf="$source_conf"
if [[ -r "$watch_conf" && "$replace_config" != true ]]; then
  effective_conf="$watch_conf"
fi

watched_units() {
  sed -E 's/#.*//' "$effective_conf" \
    | awk '/^[[:space:]]*onfailure[[:space:]]+/ { print $2 }'
}

install_dropin() {
  local unit="$1"
  local dropin_dir="/etc/systemd/system/$unit.d"
  local content
  content="$(
    cat <<EOF
[Unit]
# Installed by install-triage.sh from $watch_conf
OnFailure=colony-triage-capture@$unit.service
EOF
  )"
  if [[ "$APPLY" != true ]]; then
    quote_command install -d -m 0755 "$dropin_dir"
    quote_command install -m 0644 /dev/stdin "$dropin_dir/$dropin_marker"
    return
  fi
  install -d -m 0755 "$dropin_dir"
  printf '%s\n' "$content" >"$dropin_dir/$dropin_marker"
  chmod 0644 "$dropin_dir/$dropin_marker"
}

if [[ "$APPLY" != true ]]; then
  quote_command install -d -m 0750 /var/lib/colony-triage "$queue_dir"
  quote_command install -d -m 0755 /etc/colony-triage
  quote_command install -m 0644 "$source_conf" "$watch_conf"
  quote_command install -m 0755 "$deploy_dir/scripts/colony-triage" \
    /usr/local/sbin/colony-triage
  for unit_file in colony-triage-capture@.service \
    colony-triage-scan.service colony-triage-scan.timer; do
    quote_command install -m 0644 "$deploy_dir/systemd/$unit_file" \
      "/etc/systemd/system/$unit_file"
  done
  while IFS= read -r unit; do
    [[ -n "$unit" ]] || continue
    install_dropin "$unit"
  done < <(watched_units)
  quote_command systemctl daemon-reload
  quote_command systemctl enable --now colony-triage-scan.timer
  exit 0
fi

require_command systemctl
require_command python3

install -d -m 0750 /var/lib/colony-triage "$queue_dir"
install -d -m 0755 /etc/colony-triage
install -m 0755 "$deploy_dir/scripts/colony-triage" \
  /usr/local/sbin/colony-triage
for unit_file in colony-triage-capture@.service \
  colony-triage-scan.service colony-triage-scan.timer; do
  install -m 0644 "$deploy_dir/systemd/$unit_file" \
    "/etc/systemd/system/$unit_file"
done

installed_units="$(watched_units)"
if [[ ! -e "$watch_conf" || "$replace_config" == true ]]; then
  install -m 0644 "$source_conf" "$watch_conf"
fi

while IFS= read -r unit; do
  [[ -n "$unit" ]] || continue
  if ! systemctl cat -- "$unit" >/dev/null 2>&1; then
    log "skipping unknown unit: $unit"
    continue
  fi
  install_dropin "$unit"
done <<<"$installed_units"

systemctl daemon-reload
systemctl enable --now colony-triage-scan.timer

log "triage triggers installed; queue at $queue_dir"
log "review incidents with: colony-triage list"
