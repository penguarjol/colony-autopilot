#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(cd "$script_dir/.." && pwd)"
repo_root="$(cd "$deploy_dir/.." && pwd)"
controller_source="$repo_root/controller"
source "$deploy_dir/lib/common.sh"

spool_root="$DEFAULT_VOLUME_ROOT/.colony-autopilot"
start_service=false
restart_ollama=false
replace_config=false

usage() {
  cat <<'EOF'
Usage: install.sh [options]

Installs the Python controller, host configuration, spool access, and service
controls. It does not start services by default. The default is a dry run.

Options:
  --replace-config   Replace existing config after backing it up
  --restart-ollama   Restart Ollama after installing its override
  --start            Enable and start colony-autopilot
  --apply            Perform the installation
  --help             Show this help
EOF
}

while (($#)); do
  case "$1" in
    --replace-config)
      replace_config=true
      shift
      ;;
    --restart-ollama)
      restart_ollama=true
      shift
      ;;
    --start)
      start_service=true
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

require_apply_root

rollback_root="$DEFAULT_BACKUP_ROOT/install-$(timestamp_utc)"
ollama_override="/etc/systemd/system/ollama.service.d/zz-colony-autopilot.conf"
legacy_ollama_override="/etc/systemd/system/ollama.service.d/90-colony-autopilot.conf"
changed=false

backup_existing() {
  local destination="$1"
  local backup_path="$rollback_root$destination"

  [[ -e "$destination" ]] || return 0
  install -d -m 0750 "$(dirname "$backup_path")"
  cp -a -- "$destination" "$backup_path"
}

install_managed() {
  local source_path="$1"
  local destination="$2"
  local mode="$3"

  if [[ -f "$destination" ]] && cmp -s "$source_path" "$destination"; then
    log "unchanged: $destination"
    return
  fi

  backup_existing "$destination"
  install -D -m "$mode" "$source_path" "$destination"
  changed=true
}

install_config() {
  local source_path="$1"
  local destination="$2"
  local mode="$3"

  if [[ -e "$destination" && "$replace_config" != true ]]; then
    log "preserved existing config: $destination"
    return
  fi
  install_managed "$source_path" "$destination" "$mode"
}

read_spool_identity() {
  local expected_owner=""
  local directory
  local owner
  local permissions
  local required_directories=(
    "$spool_root"
    "$spool_root/requests"
    "$spool_root/responses"
    "$spool_root/rejected"
  )

  for directory in "${required_directories[@]}"; do
    [[ -d "$directory" && ! -L "$directory" ]] ||
      die "bridge spool directory is missing or unsafe: $directory"
    owner="$(stat -c '%u:%g' "$directory")"
    permissions="$(stat -c '%a' "$directory")"
    [[ "$permissions" == 700 ]] ||
      die "bridge spool directory must be owner-only (0700): $directory"
    if [[ -z "$expected_owner" ]]; then
      expected_owner="$owner"
    elif [[ "$owner" != "$expected_owner" ]]; then
      die "bridge spool directories do not share one owner"
    fi
  done

  spool_uid="${expected_owner%%:*}"
  spool_gid="${expected_owner##*:}"
}

if [[ "$APPLY" != true ]]; then
  quote_command useradd --system --home-dir /var/lib/colony-autopilot \
    --shell /usr/sbin/nologin colony-autopilot
  quote_command install -d -m 0755 /opt/colony-autopilot/controller \
    /opt/colony-autopilot/bin
  for source_path in "$controller_source"/*.py; do
    quote_command install -m 0644 "$source_path" \
      "/opt/colony-autopilot/controller/$(basename "$source_path")"
  done
  quote_command install -m 0755 "$deploy_dir/scripts/colony-autopilot" \
    /opt/colony-autopilot/bin/colony-autopilot
  quote_command install -D -m 0644 \
    "$deploy_dir/systemd/colony-autopilot.service" \
    /etc/systemd/system/colony-autopilot.service
  quote_command install -D -m 0644 \
    "$deploy_dir/systemd/ollama.service.d/colony-autopilot.conf" \
    "$ollama_override"
  quote_command rm -f -- "$legacy_ollama_override"
  quote_command install -D -m 0644 \
    "$deploy_dir/config/autopilot.env" /etc/default/colony-autopilot
  quote_command install -D -m 0644 \
    "$deploy_dir/config/controller.toml" \
    /etc/colony-autopilot/controller.toml
  quote_command install -D -m 0755 \
    "$deploy_dir/scripts/run-autopilot" \
    /usr/local/lib/colony-autopilot/run-autopilot
  quote_command install -D -m 0755 \
    "$deploy_dir/scripts/autopilotctl" /usr/local/sbin/autopilotctl
  quote_command stat -c '%u:%g %a' "$spool_root" \
    "$spool_root/requests" "$spool_root/responses" "$spool_root/rejected"
  log "dry-run: install runtime-user.conf with the owner UID/GID above"
  quote_command systemctl daemon-reload
  [[ "$restart_ollama" == true ]] &&
    quote_command systemctl restart ollama.service
  [[ "$start_service" == true ]] &&
    quote_command systemctl enable --now colony-autopilot.service
  exit 0
fi

[[ -d "$controller_source" ]] ||
  die "controller source is missing: $controller_source"
require_command python3
PYTHONPATH="$repo_root" python3 -m controller \
  --config "$deploy_dir/config/controller.toml" validate-config
read_spool_identity

if ! id colony-autopilot >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/colony-autopilot \
    --shell /usr/sbin/nologin colony-autopilot
fi

install -d -m 0750 -o "$spool_uid" -g "$spool_gid" \
  /var/lib/colony-autopilot /var/log/colony-autopilot
install -d -m 0750 "$DEFAULT_BACKUP_ROOT"
install -d -m 0755 /opt/colony-autopilot/controller \
  /opt/colony-autopilot/bin

for source_path in "$controller_source"/*.py; do
  install_managed "$source_path" \
    "/opt/colony-autopilot/controller/$(basename "$source_path")" 0644
done
install_managed "$deploy_dir/scripts/colony-autopilot" \
  /opt/colony-autopilot/bin/colony-autopilot 0755

install_managed "$deploy_dir/systemd/colony-autopilot.service" \
  /etc/systemd/system/colony-autopilot.service 0644
install_managed "$deploy_dir/systemd/ollama.service.d/colony-autopilot.conf" \
  "$ollama_override" 0644
if [[ -e "$legacy_ollama_override" ]]; then
  backup_existing "$legacy_ollama_override"
  rm -f -- "$legacy_ollama_override"
  changed=true
fi
install_config "$deploy_dir/config/autopilot.env" \
  /etc/default/colony-autopilot 0644
install_config "$deploy_dir/config/controller.toml" \
  /etc/colony-autopilot/controller.toml 0644
install_managed "$deploy_dir/scripts/run-autopilot" \
  /usr/local/lib/colony-autopilot/run-autopilot 0755
install_managed "$deploy_dir/scripts/autopilotctl" \
  /usr/local/sbin/autopilotctl 0755

runtime_override="$(mktemp)"
trap 'rm -f -- "$runtime_override"' EXIT
printf '[Service]\nUser=%s\nGroup=%s\n' "$spool_uid" "$spool_gid" \
  >"$runtime_override"
install_managed "$runtime_override" \
  /etc/systemd/system/colony-autopilot.service.d/runtime-user.conf 0644
rm -f -- "$runtime_override"
trap - EXIT

chown root:root /etc/default/colony-autopilot
chown root:root /etc/colony-autopilot/controller.toml

systemctl daemon-reload

if [[ "$restart_ollama" == true ]]; then
  systemctl restart ollama.service
fi

if [[ "$start_service" == true ]]; then
  executable="$(
    awk -F= '$1 == "AUTOPILOT_EXECUTABLE" { print $2 }' \
      /etc/default/colony-autopilot
  )"
  [[ -n "$executable" && -x "$executable" ]] ||
    die "configured executable is unavailable: ${executable:-unset}"
  "$executable" --config /etc/colony-autopilot/controller.toml validate-config
  systemctl enable --now colony-autopilot.service
fi

if [[ "$changed" == true ]]; then
  log "installation complete; replaced files were saved under $rollback_root"
else
  rmdir --ignore-fail-on-non-empty "$rollback_root" 2>/dev/null || true
  log "installation already current"
fi
