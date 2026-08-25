#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
installed_common="/usr/local/lib/colony-agent/ops/lib/common.sh"
if [[ -f "$installed_common" ]]; then
  source "$installed_common"
else
  source "$script_dir/../lib/common.sh"
fi

revoke=false

usage() {
  cat <<'EOF'
Usage: authorize-agent-deploy.sh [--revoke] [--apply]

Runs the installed capability and security canary before enabling autonomous
deploy, restart, and rollback operations. The default is a dry run.
EOF
}

while (($#)); do
  case "$1" in
    --revoke)
      revoke=true
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
gate="/var/lib/colony-agent-ops/deploy-enabled"

if [[ "$APPLY" != true ]]; then
  if [[ "$revoke" == true ]]; then
    quote_command rm -f -- "$gate"
  else
    quote_command runuser -u colony-agent -- \
      /usr/local/lib/colony-agent/ops/tests/agent-canary.sh --apply
    quote_command install -m 0640 /dev/stdin "$gate"
  fi
  exit 0
fi

rm -f -- "$gate"
if [[ "$revoke" == true ]]; then
  log "autonomous deployment authority revoked"
  exit 0
fi

systemctl is-active --quiet colony-agent.service
systemctl is-active --quiet colony-agent-operation.socket
[[ "$(systemctl show colony-agent.service \
  --property=NoNewPrivileges --value)" == yes ]] ||
  die "colony-agent service permits privilege escalation"
grep -Fqx 'Environment="OLLAMA_HOST=127.0.0.1:11434"' \
  /etc/systemd/system/ollama.service.d/zz-colony-autopilot.conf

runuser -u colony-agent -- \
  /usr/local/lib/colony-agent/ops/tests/agent-canary.sh --apply

temporary="$(mktemp /var/lib/colony-agent-ops/.deploy-enabled.XXXXXXXX)"
printf 'authorized=true\n' >"$temporary"
chown root:colony-agent "$temporary"
chmod 0640 "$temporary"
mv "$temporary" "$gate"
log "autonomous deployment authority enabled"
