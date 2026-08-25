#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

required_files=(
  deploy/lib/agent-operations.sh
  deploy/scripts/colony-agent-deploy
  deploy/scripts/colony-agent-op
  deploy/scripts/colony-agent-operation-dispatch
  deploy/scripts/colony-agent-restart
  deploy/scripts/colony-agent-rollback
  deploy/scripts/colony-agent-status
  deploy/scripts/authorize-agent-deploy.sh
  deploy/sudoers/colony-agent
  deploy/systemd/colony-agent-operation.socket
  deploy/systemd/colony-agent-operation@.service
)

for relative_path in "${required_files[@]}"; do
  [[ -f "$repo_root/$relative_path" ]] || {
    printf 'missing agent security file: %s\n' "$relative_path" >&2
    exit 1
  }
done

for script in \
  deploy/lib/agent-operations.sh \
  deploy/scripts/colony-agent-deploy \
  deploy/scripts/colony-agent-op \
  deploy/scripts/colony-agent-operation-dispatch \
  deploy/scripts/colony-agent-restart \
  deploy/scripts/colony-agent-rollback \
  deploy/scripts/colony-agent-status; do
  bash -n "$repo_root/$script"
done

bash -n "$repo_root/deploy/scripts/authorize-agent-deploy.sh"
grep -Fq '/usr/local/lib/colony-agent/ops/lib/common.sh' \
  "$repo_root/deploy/scripts/authorize-agent-deploy.sh"

sudoers="$repo_root/deploy/sudoers/colony-agent"
grep -Fq 'colony-agent-status ""' "$sudoers"
grep -Fq 'colony-agent-deploy ""' "$sudoers"
grep -Fq 'colony-agent-restart ""' "$sudoers"
grep -Fq 'colony-agent-rollback ""' "$sudoers"
if command -v visudo >/dev/null 2>&1; then
  visudo -c -f "$sudoers"
fi

grep -Fq 'SocketMode=0660' \
  "$repo_root/deploy/systemd/colony-agent-operation.socket"
grep -Fq 'SocketGroup=colony-agent' \
  "$repo_root/deploy/systemd/colony-agent-operation.socket"
grep -Fq 'NoNewPrivileges=true' \
  "$repo_root/deploy/systemd/colony-agent-operation@.service"
grep -Fq 'RestrictAddressFamilies=AF_UNIX' \
  "$repo_root/deploy/systemd/colony-agent-operation@.service"

dispatch_output="$(
  printf 'arbitrary-command\n' |
    bash "$repo_root/deploy/scripts/colony-agent-operation-dispatch"
)"
[[ "$dispatch_output" == *"unsupported operation: arbitrary-command"* ]]
[[ "$dispatch_output" == *"__COLONY_AGENT_EXIT__=64"* ]]
if bash "$repo_root/deploy/scripts/colony-agent-op" status extra \
  >/dev/null 2>&1; then
  printf 'agent operation client accepted extra arguments\n' >&2
  exit 1
fi

operations="$repo_root/deploy/lib/agent-operations.sh"
grep -Fq 'flock --exclusive' "$operations"
grep -Fq 'diff --quiet' "$operations"
grep -Fq 'diff --cached --quiet' "$operations"
grep -Fq '"$OPS_ROOT/scripts/backup.sh"' "$operations"
grep -Fq 'sha256sum' "$operations"
grep -Fq 'INSTALL_BRIDGE_ON_LIVE_VOLUME' "$operations"
grep -Fq 'rollback_deployment' "$operations"
grep -Fq 'health_check' "$operations"
grep -Fq 'require_deploy_authority' "$operations"
grep -Fq '/opt/colony-agent/gradle/gradle-8.8/bin/gradle' "$operations"
grep -Fq -- '--offline' "$operations"
if grep -Fq './bridge/gradlew' "$operations"; then
  printf 'agent operations reference a missing Gradle wrapper\n' >&2
  exit 1
fi
grep -Fq 'rm -f -- /var/lib/colony-agent-ops/deploy-enabled' \
  "$repo_root/deploy/scripts/install-agent.sh"
for operation in deploy restart rollback; do
  grep -Fq 'require_deploy_authority' \
    "$repo_root/deploy/scripts/colony-agent-$operation"
done

if grep -R -E 'NOPASSWD:[[:space:]]*(ALL|/bin/(ba)?sh|/usr/bin/env)' \
  "$repo_root/deploy/sudoers" >/dev/null; then
  printf 'agent sudoers grants an unrestricted command\n' >&2
  exit 1
fi

printf 'local coding agent security tests passed\n'
