#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_dir="$(cd "$script_dir/.." && pwd)"
source "$deploy_dir/lib/common.sh"

readonly opencode_version="1.18.22"
readonly opencode_url="https://github.com/anomalyco/opencode/releases/download/v${opencode_version}/opencode-linux-x64.tar.gz"
readonly opencode_sha256="23eca6a892c6b53c0f9ba2333b6906bdc31902634631d54cf17500e7e8cbfa20"
readonly gradle_version="8.8"
readonly gradle_url="https://services.gradle.org/distributions/gradle-${gradle_version}-bin.zip"
readonly gradle_sha256="a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612"
readonly agent_home="/var/lib/colony-agent"
readonly workspace_root="/srv/colony-agent/workspaces"
readonly agent_workspace="$workspace_root/colony-autopilot"

start_service=false
replace_config=false

usage() {
  cat <<'EOF'
Usage: install-agent.sh [options]

Installs the pinned OpenCode local coding agent and qwen3.5:4b. The default is
a dry run. This does not initialize the coding repository or grant deployment
authority.

Options:
  --replace-config   Replace OpenCode configuration after backing it up
  --start            Enable and start colony-agent after installation
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

if [[ "$APPLY" != true ]]; then
  quote_command useradd --system --home-dir "$agent_home" \
    --shell /usr/sbin/nologin colony-agent
  quote_command install -d -m 0750 -o colony-agent -g colony-agent \
    "$agent_home" "$agent_home/cache" "$agent_home/config" "$agent_home/data" \
    /var/log/colony-agent "$workspace_root"
  quote_command curl -fsSL "$opencode_url" -o /tmp/opencode.tar.gz
  quote_command sha256sum /tmp/opencode.tar.gz
  quote_command install -m 0755 opencode /opt/colony-agent/bin/opencode
  quote_command curl -fsSL "$gradle_url" -o /tmp/gradle.zip
  quote_command sha256sum /tmp/gradle.zip
  quote_command unzip -q /tmp/gradle.zip -d /opt/colony-agent/gradle
  quote_command ollama pull qwen3.5:4b
  quote_command install -m 0640 "$deploy_dir/config/opencode.json" \
    /etc/colony-agent/opencode.json
  quote_command install -m 0644 "$deploy_dir/systemd/colony-agent.service" \
    /etc/systemd/system/colony-agent.service
  quote_command install -m 0644 \
    "$deploy_dir/systemd/ollama.service.d/colony-autopilot.conf" \
    /etc/systemd/system/ollama.service.d/zz-colony-autopilot.conf
  quote_command install -m 0440 "$deploy_dir/sudoers/colony-agent" \
    /etc/sudoers.d/colony-agent
  quote_command install -m 0755 "$deploy_dir/scripts/colony-agent-op" \
    /usr/local/bin/colony-agent-op
  quote_command install -m 0755 \
    "$deploy_dir/scripts/authorize-agent-deploy.sh" \
    /usr/local/sbin/authorize-agent-deploy
  quote_command systemctl daemon-reload
  quote_command systemctl enable --now colony-agent-operation.socket
  quote_command systemctl restart ollama.service
  [[ "$start_service" == true ]] &&
    quote_command systemctl enable --now colony-agent.service
  exit 0
fi

require_command curl
require_command openssl
require_command sha256sum
require_command tar
require_command unzip
require_command ollama
require_command visudo

[[ -f "$deploy_dir/config/opencode.json" ]] ||
  die "missing OpenCode config"
[[ -f "$deploy_dir/systemd/colony-agent.service" ]] ||
  die "missing colony-agent systemd unit"
visudo -c -f "$deploy_dir/sudoers/colony-agent"

if ! id colony-agent >/dev/null 2>&1; then
  useradd --system --home-dir "$agent_home" \
    --shell /usr/sbin/nologin colony-agent
fi

install -d -m 0750 -o colony-agent -g colony-agent \
  "$agent_home" "$agent_home/cache" "$agent_home/config" "$agent_home/data" \
  /var/log/colony-agent "$workspace_root"
install -d -m 0755 /opt/colony-agent/bin /usr/local/lib/colony-agent
install -d -m 0755 /usr/local/lib/colony-agent/ops/scripts \
  /usr/local/lib/colony-agent/ops/lib /usr/local/lib/colony-agent/ops/tests
install -d -m 0750 -o root -g colony-agent /etc/colony-agent
install -d -m 0750 -o root -g colony-agent /var/lib/colony-agent-ops
rm -f -- /var/lib/colony-agent-ops/deploy-enabled

staging="$(mktemp -d)"
trap 'rm -rf -- "$staging"' EXIT
archive="$staging/opencode.tar.gz"
curl -fsSL "$opencode_url" -o "$archive"
printf '%s  %s\n' "$opencode_sha256" "$archive" | sha256sum -c -
tar -xzf "$archive" -C "$staging"
shopt -s nullglob
candidates=("$staging/opencode" "$staging"/*/opencode)
shopt -u nullglob
((${#candidates[@]} == 1)) ||
  die "OpenCode archive did not contain one opencode binary"
install -D -m 0755 "${candidates[0]}" /opt/colony-agent/bin/opencode

gradle_archive="$staging/gradle.zip"
curl -fsSL "$gradle_url" -o "$gradle_archive"
printf '%s  %s\n' "$gradle_sha256" "$gradle_archive" | sha256sum -c -
unzip -q "$gradle_archive" -d "$staging"
install -d -m 0755 /opt/colony-agent/gradle
rm -rf -- "/opt/colony-agent/gradle/gradle-$gradle_version"
cp -a "$staging/gradle-$gradle_version" /opt/colony-agent/gradle/

backup_root="$DEFAULT_BACKUP_ROOT/agent-install-$(timestamp_utc)"
install -d -m 0750 "$backup_root"
for path in \
  /etc/colony-agent/opencode.json \
  /etc/systemd/system/colony-agent.service \
  /etc/systemd/system/ollama.service.d/zz-colony-autopilot.conf \
  /usr/local/lib/colony-agent/run-colony-agent \
  /usr/local/sbin/colony-agent-cli; do
  if [[ -e "$path" ]]; then
    install -D -m 0640 "$path" "$backup_root$path"
  fi
done

if [[ ! -e /etc/colony-agent/opencode.json ||
  "$replace_config" == true ]]; then
  install -m 0640 -o root -g colony-agent \
    "$deploy_dir/config/opencode.json" /etc/colony-agent/opencode.json
fi
install -m 0644 "$deploy_dir/systemd/colony-agent.service" \
  /etc/systemd/system/colony-agent.service
install -D -m 0644 \
  "$deploy_dir/systemd/ollama.service.d/colony-autopilot.conf" \
  /etc/systemd/system/ollama.service.d/zz-colony-autopilot.conf
install -m 0755 "$deploy_dir/scripts/run-colony-agent" \
  /usr/local/lib/colony-agent/run-colony-agent
install -m 0755 "$deploy_dir/scripts/colony-agent-cli" \
  /usr/local/sbin/colony-agent-cli
install -m 0755 "$deploy_dir/scripts/colony-agent-op" \
  /usr/local/bin/colony-agent-op
install -m 0755 "$deploy_dir/scripts/colony-agent-operation-dispatch" \
  /usr/local/lib/colony-agent/operation-dispatch
install -m 0755 "$deploy_dir/lib/agent-operations.sh" \
  /usr/local/lib/colony-agent/agent-operations.sh
install -m 0755 "$deploy_dir/lib/common.sh" \
  /usr/local/lib/colony-agent/ops/lib/common.sh
install -m 0755 "$deploy_dir/scripts/backup.sh" \
  /usr/local/lib/colony-agent/ops/scripts/backup.sh
install -m 0755 "$deploy_dir/scripts/install-bridge.sh" \
  /usr/local/lib/colony-agent/ops/scripts/install-bridge.sh
install -m 0755 "$deploy_dir/tests/agent-canary.sh" \
  /usr/local/lib/colony-agent/ops/tests/agent-canary.sh
install -m 0755 "$deploy_dir/scripts/authorize-agent-deploy.sh" \
  /usr/local/sbin/authorize-agent-deploy
for operation in deploy restart rollback status; do
  install -m 0755 "$deploy_dir/scripts/colony-agent-$operation" \
    "/usr/local/sbin/colony-agent-$operation"
done
install -m 0440 "$deploy_dir/sudoers/colony-agent" \
  /etc/sudoers.d/colony-agent
install -m 0644 "$deploy_dir/systemd/colony-agent-operation.socket" \
  /etc/systemd/system/colony-agent-operation.socket
install -m 0644 "$deploy_dir/systemd/colony-agent-operation@.service" \
  /etc/systemd/system/colony-agent-operation@.service

if [[ ! -e /etc/colony-agent/agent.env ]]; then
  password="$(openssl rand -hex 24)"
  cat >/etc/colony-agent/agent.env <<EOF
COLONY_AGENT_HOST=192.168.1.171
COLONY_AGENT_PORT=4096
OPENCODE_SERVER_USERNAME=opencode
OPENCODE_SERVER_PASSWORD=$password
EOF
fi
chown root:colony-agent /etc/colony-agent/agent.env
chmod 0640 /etc/colony-agent/agent.env

systemctl daemon-reload
systemctl restart ollama.service
ollama_ready=false
for _ in {1..30}; do
  if curl --fail --silent http://127.0.0.1:11434/api/tags >/dev/null; then
    ollama_ready=true
    break
  fi
  sleep 1
done
[[ "$ollama_ready" == true ]] || die "Ollama did not become ready"
ollama pull qwen3.5:4b
if [[ -d "$agent_workspace/.git" ]]; then
  runuser -u colony-agent -- env HOME="$agent_home" \
    "/opt/colony-agent/gradle/gradle-$gradle_version/bin/gradle" \
    -p "$agent_workspace/bridge" --no-daemon testClasses
fi
systemctl enable --now colony-agent-operation.socket
if command -v ufw >/dev/null 2>&1 &&
  ufw status | awk 'NR == 1 && $2 == "active" { found=1 } END { exit !found }'; then
  ufw allow from 192.168.1.0/24 to any port 4096 proto tcp \
    comment 'colony-agent LAN'
fi

if [[ "$start_service" == true ]]; then
  [[ -d "$agent_workspace/.git" ]] ||
    die "agent workspace is not initialized: $agent_workspace"
  systemctl enable --now colony-agent.service
fi

log "installed OpenCode $opencode_version; credentials are in /etc/colony-agent/agent.env"

