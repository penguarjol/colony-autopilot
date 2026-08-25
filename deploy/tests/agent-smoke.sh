#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

required_files=(
  deploy/config/opencode.json
  deploy/tests/agent-canary.sh
  deploy/scripts/colony-agent-cli
  deploy/scripts/init-agent-workspace.sh
  deploy/scripts/install-agent.sh
  deploy/scripts/run-colony-agent
  deploy/scripts/uninstall-agent.sh
  deploy/systemd/colony-agent.service
)

for relative_path in "${required_files[@]}"; do
  [[ -f "$repo_root/$relative_path" ]] || {
    printf 'missing required agent file: %s\n' "$relative_path" >&2
    exit 1
  }
done

for script in \
  deploy/tests/agent-canary.sh \
  deploy/scripts/colony-agent-cli \
  deploy/scripts/init-agent-workspace.sh \
  deploy/scripts/install-agent.sh \
  deploy/scripts/run-colony-agent \
  deploy/scripts/uninstall-agent.sh; do
  bash -n "$repo_root/$script"
done

grep -Fq 'qwen3.5:4b' "$repo_root/deploy/config/opencode.json"
grep -Fq 'http://127.0.0.1:11434/v1' \
  "$repo_root/deploy/config/opencode.json"
grep -Fq '"reasoningEffort": "none"' \
  "$repo_root/deploy/config/opencode.json"
grep -Fq '"external_directory": "deny"' \
  "$repo_root/deploy/config/opencode.json"
grep -Fq 'User=colony-agent' \
  "$repo_root/deploy/systemd/colony-agent.service"
grep -Fq 'NoNewPrivileges=true' \
  "$repo_root/deploy/systemd/colony-agent.service"
grep -Fq 'IPAddressDeny=any' \
  "$repo_root/deploy/systemd/colony-agent.service"
grep -Fq 'OPENCODE_SERVER_PASSWORD' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq '23eca6a892c6b53c0f9ba2333b6906bdc31902634631d54cf17500e7e8cbfa20' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq 'zz-colony-autopilot.conf' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq 'Ollama did not become ready' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq 'git -C "$verification_repository" bundle verify' \
  "$repo_root/deploy/scripts/init-agent-workspace.sh"
grep -Fq 'repository_root="/srv/colony-agent/repositories"' \
  "$repo_root/deploy/scripts/init-agent-workspace.sh"
grep -Fq 'rmdir --ignore-fail-on-non-empty "$workspace"' \
  "$repo_root/deploy/scripts/init-agent-workspace.sh"
grep -Fq 'runuser -u colony-agent -- git' \
  "$repo_root/deploy/scripts/init-agent-workspace.sh"
chown_line="$(grep -n 'chown -R colony-agent:colony-agent' \
  "$repo_root/deploy/scripts/init-agent-workspace.sh" | cut -d: -f1)"
fetch_line="$(grep -n 'bare_git fetch' \
  "$repo_root/deploy/scripts/init-agent-workspace.sh" | cut -d: -f1)"
if ((chown_line >= fetch_line)); then
  printf 'workspace ownership must be repaired before fetching\n' >&2
  exit 1
fi
grep -Fq 'cd /srv/colony-agent/workspaces/colony-autopilot' \
  "$repo_root/deploy/scripts/colony-agent-cli"
grep -Fq '"http://$COLONY_AGENT_HOST:$COLONY_AGENT_PORT"' \
  "$repo_root/deploy/scripts/colony-agent-cli"
grep -Fq 'OPENCODE_CONFIG=/etc/colony-agent/opencode.json' \
  "$repo_root/deploy/tests/agent-canary.sh"
if grep -Fq -- '--attach' "$repo_root/deploy/tests/agent-canary.sh"; then
  printf 'capability canary must not use broken remote-directory attach\n' >&2
  exit 1
fi

printf 'local coding agent artifact smoke tests passed\n'
