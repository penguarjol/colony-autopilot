#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

readonly agent_env="/etc/colony-agent/agent.env"
readonly cockpit_conf="/etc/cockpit/cockpit.conf"

usage() {
  cat <<'EOF'
Usage: setup-cockpit-sso.sh [options]

Creates the shared dashboard account as a real Linux user so Cockpit accepts
the same credentials as the rest of the command center, and configures Cockpit
to trust the Caddy origins. The default is a dry run.

Cockpit authenticates through PAM against system accounts; there is no separate
user store, so the account must exist on the host.

Options:
  --apply   Perform the configuration
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

[[ -f "$agent_env" ]] || die "missing $agent_env; run install-agent.sh first"
# shellcheck disable=SC1090
source "$agent_env"
cc_user="${OPENCODE_SERVER_USERNAME:?OPENCODE_SERVER_USERNAME missing}"
cc_pass="${OPENCODE_SERVER_PASSWORD:?OPENCODE_SERVER_PASSWORD missing}"

# Cockpit matches PAM accounts case-sensitively against what the user types,
# and Linux usernames are conventionally lowercase.
login_name="$(printf '%s' "$cc_user" | tr '[:upper:]' '[:lower:]')"

if [[ "$APPLY" != true ]]; then
  quote_command useradd --create-home --shell /bin/bash "$login_name"
  quote_command usermod --append --groups sudo "$login_name"
  quote_command chpasswd
  quote_command install -D -m 0644 /dev/stdin "$cockpit_conf"
  quote_command systemctl restart cockpit.socket
  exit 0
fi

require_command chpasswd
systemctl list-unit-files 2>/dev/null | grep -q '^cockpit\.socket' ||
  die "cockpit is not installed on this host"

if id "$login_name" >/dev/null 2>&1; then
  log "user $login_name already exists; updating password and groups"
else
  useradd --create-home --shell /bin/bash "$login_name"
  log "created user $login_name"
fi

usermod --append --groups sudo "$login_name"
printf '%s:%s\n' "$login_name" "$cc_pass" | chpasswd
passwd --unlock "$login_name" >/dev/null 2>&1 || true

# Caddy terminates the client connection and re-originates to Cockpit, so
# Cockpit must trust those origins or it rejects the websocket upgrade.
install -d -m 0755 /etc/cockpit
cat >"$cockpit_conf" <<EOF
[WebService]
Origins = http://192.168.1.171:3000 http://192.168.1.171:9091 https://192.168.1.171:9090 http://192.168.1.171:7575
ProtocolHeader = X-Forwarded-Proto
AllowUnencrypted = true

[Session]
IdleTimeout = 30
EOF
chmod 0644 "$cockpit_conf"

systemctl restart cockpit.socket
sleep 2
systemctl is-active --quiet cockpit.socket ||
  die "cockpit.socket failed to restart"

log "cockpit accepts $login_name with the shared command center password"
