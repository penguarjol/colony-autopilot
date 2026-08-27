#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

required_files=(
  deploy/config/opencode.json
  deploy/tests/agent-canary.sh
  deploy/scripts/colony-agent-cli
  deploy/scripts/colony-agent-mode
  deploy/scripts/init-agent-workspace.sh
  deploy/scripts/install-agent.sh
  deploy/scripts/run-colony-agent
  deploy/scripts/uninstall-agent.sh
  deploy/systemd/colony-agent-proxy.service
  deploy/systemd/colony-agent-proxy.socket
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
  deploy/scripts/colony-agent-mode \
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
grep -Fq '"default_agent": "maintenance"' \
  "$repo_root/deploy/config/opencode.json"
grep -Fq '"task": "deny"' \
  "$repo_root/deploy/config/opencode.json"
grep -Fq '"external_directory": "deny"' \
  "$repo_root/deploy/config/opencode.json"
grep -Fq 'User=colony-agent' \
  "$repo_root/deploy/systemd/colony-agent.service"
grep -Fq 'NoNewPrivileges=true' \
  "$repo_root/deploy/systemd/colony-agent.service"
grep -Fq 'IPAddressDeny=any' \
  "$repo_root/deploy/systemd/colony-agent.service"
if grep -Fq 'IPAddressAllow=192.168.1.0/24' \
  "$repo_root/deploy/systemd/colony-agent.service"; then
  printf 'agent service must not have direct LAN access\n' >&2
  exit 1
fi
grep -Fq 'ListenStream=192.168.1.171:4096' \
  "$repo_root/deploy/systemd/colony-agent-proxy.socket"
# Binding a specific address races DHCP at boot: systemd reaches the socket
# unit before the interface owns the address and fails with "Cannot assign
# requested address" without retrying. FreeBind lets the bind succeed early.
# Widening ListenStream to a wildcard would also "fix" the race, at the cost of
# exposing the agent on every interface, so assert the address stays specific.
grep -Fq 'FreeBind=true' \
  "$repo_root/deploy/systemd/colony-agent-proxy.socket" || {
  printf 'proxy socket binds a specific address and needs FreeBind=true\n' >&2
  exit 1
}
if grep -Eq '^ListenStream=(0\.0\.0\.0|\[?::\]?|):' \
  "$repo_root/deploy/systemd/colony-agent-proxy.socket"; then
  printf 'proxy socket must not listen on a wildcard address\n' >&2
  exit 1
fi
grep -Fq 'systemd-socket-proxyd 127.0.0.1:4097' \
  "$repo_root/deploy/systemd/colony-agent-proxy.service"
grep -Fq 'COLONY_AGENT_BACKEND_HOST' \
  "$repo_root/deploy/scripts/run-colony-agent"
grep -Fq 'OPENCODE_SERVER_PASSWORD' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq '23eca6a892c6b53c0f9ba2333b6906bdc31902634631d54cf17500e7e8cbfa20' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq 'a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq '3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq 'zz-colony-autopilot.conf' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq 'Ollama did not become ready' \
  "$repo_root/deploy/scripts/install-agent.sh"
grep -Fq -- '--no-daemon clean test build' \
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
grep -Fq '"http://$COLONY_AGENT_BACKEND_HOST:$COLONY_AGENT_BACKEND_PORT"' \
  "$repo_root/deploy/scripts/colony-agent-cli"
grep -Fq 'OPENCODE_CONFIG=/etc/colony-agent/opencode.json' \
  "$repo_root/deploy/tests/agent-canary.sh"
grep -Fq -- '--agent maintenance' \
  "$repo_root/deploy/tests/agent-canary.sh"
if grep -Fq -- '--model ollama/qwen3.5:4b' \
  "$repo_root/deploy/tests/agent-canary.sh"; then
  printf 'capability canary must not hardcode a candidate model\n' >&2
  exit 1
fi
grep -Fq 'elapsed_seconds=' "$repo_root/deploy/tests/agent-canary.sh"

# The canary grades with "python3 -m unittest", and pytest is deliberately not
# installed, so the prompt must name the runner it will be scored against.
grep -Fq 'unittest' "$repo_root/deploy/tests/agent-canary.sh"
canary_prompt="$(
  sed -n "/^prompt='/,/'\$/p" "$repo_root/deploy/tests/agent-canary.sh"
)"
grep -Fq 'unittest' <<<"$canary_prompt" || {
  printf 'canary prompt must name the unittest runner it is graded with\n' >&2
  exit 1
}
if grep -Eq '(-m|using|with|via) pytest' <<<"$canary_prompt"; then
  printf 'canary prompt must not direct the model to an uninstalled runner\n' >&2
  exit 1
fi
grep -Fq 'not installed' <<<"$canary_prompt" || {
  printf 'canary prompt must state that pytest and pip are unavailable\n' >&2
  exit 1
}
# colony-agent has /usr/sbin/nologin as its login shell, so OpenCode's bash
# tool silently fails unless the canary sets a usable SHELL itself.
grep -Fq 'export SHELL=/bin/bash' "$repo_root/deploy/tests/agent-canary.sh" || {
  printf 'canary must set a usable SHELL for the agent bash tool\n' >&2
  exit 1
}

# Disciplines the 4B model measurably failed in the capability canary: it
# ignored the spec in README.md, copied the function under test into the test
# file, and reported success while the suite was still failing.
maintenance_prompt="$(
  python3 -c 'import json, sys; print(
    json.load(open(sys.argv[1]))["agent"]["maintenance"]["prompt"])' \
    "$repo_root/deploy/config/opencode.json"
)"
for required_discipline in \
  'documentation' \
  'import the module under test' \
  'Never report success'; do
  grep -Fq "$required_discipline" <<<"$maintenance_prompt" || {
    printf 'maintenance prompt missing discipline: %s\n' \
      "$required_discipline" >&2
    exit 1
  }
done

# Read-only discovery tools are strictly weaker than bash, which can already run
# find, grep and ls. Denying them while bash is allowed is not a boundary, it
# just costs one inference round-trip per directory level.
python3 - "$repo_root/deploy/config/opencode.json" <<'PY' || exit 1
import json
import sys

permission = json.load(open(sys.argv[1]))["agent"]["maintenance"]["permission"]
if permission.get("bash") != "allow":
    sys.exit(0)
superseded = [
    tool
    for tool in ("glob", "grep", "list")
    if permission.get(tool) == "deny"
]
if superseded:
    sys.exit(
        "bash is allowed, so denying "
        + ", ".join(superseded)
        + " removes capability without adding a boundary"
    )
PY

agent_steps="$(
  python3 -c 'import json, sys; print(
    json.load(open(sys.argv[1]))["agent"]["maintenance"]["steps"])' \
    "$repo_root/deploy/config/opencode.json"
)"
if ((agent_steps < 24)); then
  printf 'maintenance agent needs headroom for the full TDD loop (steps=%s)\n' \
    "$agent_steps" >&2
  exit 1
fi

canary="$repo_root/deploy/tests/agent-canary.sh"
bash "$canary" --help | grep -Fq -- '--model'
bash "$canary" --help | grep -Fq -- '--timeout'
bash "$canary" --model ollama/candidate:1b --timeout 60 >/dev/null
if bash "$canary" --timeout 0 >/dev/null 2>&1; then
  printf 'capability canary accepted a non-positive timeout\n' >&2
  exit 1
fi
if bash "$canary" --model 'bad model' >/dev/null 2>&1; then
  printf 'capability canary accepted an unsafe model name\n' >&2
  exit 1
fi
if grep -Fq -- '--attach' "$repo_root/deploy/tests/agent-canary.sh"; then
  printf 'capability canary must not use broken remote-directory attach\n' >&2
  exit 1
fi

# OpenCode packs prompts up to limit.context and Ollama silently truncates
# anything beyond num_ctx, so a mismatch corrupts tool loops mid-conversation.
agent_context="$(
  python3 -c 'import json, sys; print(
    json.load(open(sys.argv[1]))["provider"]["ollama"]["models"]["qwen3.5:4b"]["limit"]["context"])' \
    "$repo_root/deploy/config/opencode.json"
)"
ollama_context="$(
  sed -n 's/^Environment="OLLAMA_CONTEXT_LENGTH=\([0-9]*\)"$/\1/p' \
    "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"
)"
if [[ "$agent_context" != "$ollama_context" ]]; then
  printf 'OpenCode context %s does not match Ollama context %s\n' \
    "$agent_context" "$ollama_context" >&2
  exit 1
fi

mode="$repo_root/deploy/scripts/colony-agent-mode"
bash "$mode" --help | grep -Fq 'background'
bash "$mode" --help | grep -Fq 'burst'
bash "$mode" --help | grep -Fq 'status'
grep -Fq 'CPUQuota=400%' "$mode"
grep -Fq 'CPUQuota=800%' "$mode"
# Runtime-only properties guarantee a reboot returns to the background profile,
# so burst can never be left applied by accident.
grep -Fq -- '--runtime' "$mode"
bash "$mode" background | grep -Fq 'dry-run'
bash "$mode" burst | grep -Fq 'dry-run'
if bash "$mode" nonsense >/dev/null 2>&1; then
  printf 'agent mode accepted an unknown profile\n' >&2
  exit 1
fi
if bash "$mode" >/dev/null 2>&1; then
  printf 'agent mode requires an explicit profile\n' >&2
  exit 1
fi
# Above 800% the default 8 inference threads leave the extra quota unused.
if grep -Eq 'CPUQuota=(9[0-9][0-9]|[1-9][0-9]{3,})%' "$mode"; then
  printf 'CPUQuota above 800%% exceeds the usable thread count\n' >&2
  exit 1
fi

printf 'local coding agent artifact smoke tests passed\n'
