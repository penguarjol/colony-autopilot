#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

required_files=(
  README.md
  deploy/config/autopilot.env
  deploy/config/controller.toml
  deploy/config/opencode.json
  deploy/integration/server-service-map.snippet.sh
  deploy/lib/common.sh
  deploy/scripts/autopilotctl
  deploy/scripts/backup.sh
  deploy/scripts/benchmark.sh
  deploy/scripts/clone-world.sh
  deploy/scripts/colony-autopilot
  deploy/scripts/install-bridge.sh
  deploy/scripts/install-agent.sh
  deploy/scripts/install-triage.sh
  deploy/scripts/install.sh
  deploy/scripts/run-autopilot
  deploy/scripts/run-colony-agent
  deploy/scripts/colony-agent-cli
  deploy/scripts/uninstall-agent.sh
  deploy/scripts/uninstall.sh
  deploy/systemd/colony-autopilot.service
  deploy/systemd/colony-agent.service
  deploy/systemd/ollama.service.d/colony-autopilot.conf
)

for relative_path in "${required_files[@]}"; do
  [[ -f "$repo_root/$relative_path" ]] || {
    printf 'missing required file: %s\n' "$relative_path" >&2
    exit 1
  }
done

[[ ! -e "$repo_root/deploy/config/autopilot.yaml" ]]
[[ ! -e "$repo_root/deploy/integration/server-wrapper.snippet.sh" ]]
[[ ! -e "$repo_root/deploy/scripts/grant-spool-access" ]]

shell_scripts=(
  deploy/integration/server-service-map.snippet.sh
  deploy/lib/common.sh
  deploy/scripts/autopilotctl
  deploy/scripts/backup.sh
  deploy/scripts/benchmark.sh
  deploy/scripts/clone-world.sh
  deploy/scripts/colony-autopilot
  deploy/scripts/install-bridge.sh
  deploy/scripts/install-agent.sh
  deploy/scripts/install-triage.sh
  deploy/scripts/install.sh
  deploy/scripts/run-autopilot
  deploy/scripts/run-colony-agent
  deploy/scripts/colony-agent-cli
  deploy/scripts/uninstall-agent.sh
  deploy/scripts/uninstall.sh
  deploy/tests/smoke.sh
)
for script in "${shell_scripts[@]}"; do
  bash -n "$repo_root/$script"
done

for script in install.sh install-bridge.sh install-agent.sh install-triage.sh \
  uninstall.sh uninstall-agent.sh backup.sh clone-world.sh benchmark.sh; do
  output="$(bash "$repo_root/deploy/scripts/$script" --help)"
  [[ "$output" == *"--apply"* ]] || {
    printf '%s does not document --apply\n' "$script" >&2
    exit 1
  }
done

bridge_installer="$repo_root/deploy/scripts/install-bridge.sh"
grep -Fq -- '--confirm-live-volume' "$bridge_installer"
grep -Fq -- '--rollback' "$bridge_installer"
grep -Fq -- '--remove' "$bridge_installer"
grep -Fq 'sha256sum' "$bridge_installer"
grep -Fq 'unzip' "$bridge_installer"
grep -Fq 'colonyautobridge.mixins.json' "$bridge_installer"
grep -Fq 'BuildingStructureHandlerMixin.class' "$bridge_installer"
grep -Fq 'StructureAiAccessor.class' "$bridge_installer"
grep -Fq 'target_checksum="$volume_path/.colonyautobridge.sha256"' \
  "$bridge_installer"
if "$bridge_installer" \
  --volume /var/lib/pterodactyl/volumes/fea5b15c-2aa0-473f-b361-3885b286cd7e \
  --jar /tmp/missing-bridge.jar \
  --sha256 0000000000000000000000000000000000000000000000000000000000000000 \
  >/dev/null 2>&1; then
  printf 'bridge installer accepted the live volume without confirmation\n' >&2
  exit 1
fi

python3 -m controller \
  --config "$repo_root/deploy/config/controller.toml" validate-config
grep -Fq 'mode = "observer"' "$repo_root/deploy/config/controller.toml"
grep -Fq 'interval_seconds = 30.0' "$repo_root/deploy/config/controller.toml"
grep -Fq 'cadence_seconds = 1200.0' "$repo_root/deploy/config/controller.toml"
grep -Fq 'database_path = "/var/lib/colony-autopilot/controller.db"' \
  "$repo_root/deploy/config/controller.toml"
grep -Fq 'spool_path = "/var/lib/pterodactyl/volumes/fea5b15c-2aa0-473f-b361-3885b286cd7e/.colony-autopilot"' \
  "$repo_root/deploy/config/controller.toml"
grep -Fq 'start' "$repo_root/deploy/scripts/run-autopilot"
if grep -Fq -- '--mode' "$repo_root/deploy/scripts/run-autopilot"; then
  printf 'run-autopilot must preserve observer mode from TOML\n' >&2
  exit 1
fi
grep -Fq '/opt/colony-autopilot/controller' \
  "$repo_root/deploy/scripts/install.sh"
grep -Fq 'zz-colony-autopilot.conf' "$repo_root/deploy/scripts/install.sh"
grep -Fq 'zz-colony-autopilot.conf' "$repo_root/deploy/scripts/uninstall.sh"
grep -Fq "stat -c '%u:%g'" "$repo_root/deploy/scripts/install.sh"
grep -Fq 'runtime-user.conf' "$repo_root/deploy/scripts/install.sh"
if grep -Rq --exclude='smoke.sh' 'setfacl' "$repo_root/deploy"; then
  printf 'ACL access conflicts with the owner-only bridge contract\n' >&2
  exit 1
fi
grep -Fq 'ReadWritePaths=' \
  "$repo_root/deploy/systemd/colony-autopilot.service"
if grep -Fq 'ProcSubset=pid' \
  "$repo_root/deploy/systemd/colony-autopilot.service"; then
  printf 'ProcSubset=pid hides /proc/meminfo required by the governor\n' >&2
  exit 1
fi
grep -Fq '/var/lib/pterodactyl/volumes/fea5b15c-2aa0-473f-b361-3885b286cd7e/.colony-autopilot' \
  "$repo_root/deploy/systemd/colony-autopilot.service"
grep -Fq 'SERVICE_MAP' \
  "$repo_root/deploy/integration/server-service-map.snippet.sh"
grep -Eq 'autopilot.*colony-autopilot|colony-autopilot.*autopilot' \
  "$repo_root/deploy/integration/server-service-map.snippet.sh"
grep -Fq 'OLLAMA_HOST=127.0.0.1:11434' \
  "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"
grep -Fq 'OLLAMA_NUM_PARALLEL=1' \
  "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"
grep -Fq 'OLLAMA_MAX_LOADED_MODELS=1' \
  "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"
grep -Fq 'OLLAMA_CONTEXT_LENGTH=16384' \
  "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"
grep -Fq 'OLLAMA_KEEP_ALIVE=5m' \
  "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"
grep -Fq 'CPUQuota=400%' "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"
grep -Fq 'MemoryMax=6G' "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"

# A 64K context against MemoryMax=6G saturates the cgroup for a 4B model and
# forces continuous reclaim, so the ceiling must stay well under it.
ollama_context="$(
  sed -n 's/^Environment="OLLAMA_CONTEXT_LENGTH=\([0-9]*\)"$/\1/p' \
    "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"
)"
if ((ollama_context > 32768)); then
  printf 'OLLAMA_CONTEXT_LENGTH=%s risks exhausting MemoryMax=6G\n' \
    "$ollama_context" >&2
  exit 1
fi
# keep_alive=0 unloads between every tool step, discarding the prompt cache and
# re-evaluating the whole conversation prefix on each step.
if grep -Fq 'OLLAMA_KEEP_ALIVE=0' \
  "$repo_root/deploy/systemd/ollama.service.d/colony-autopilot.conf"; then
  printf 'OLLAMA_KEEP_ALIVE=0 discards the prompt cache between tool steps\n' >&2
  exit 1
fi

benchmark="$repo_root/deploy/scripts/benchmark.sh"
# A trivial prompt measures model load time only. Agentic tool loops are
# dominated by prompt evaluation of a multi-thousand-token conversation prefix,
# so acceptance must score throughput at a realistic prompt size.
grep -Fq 'prompt_eval_count' "$benchmark"
grep -Fq 'prompt_eval_duration' "$benchmark"
grep -Fq -- '--min-prompt-eval-rate' "$benchmark"
if grep -Eq 'CPUQuotaPerSecUSec=1\[\.\]5' "$benchmark"; then
  printf 'benchmark must accept every supported CPU profile\n' >&2
  exit 1
fi

if grep -R -E '(level\.dat|\.mca|NBT)' "$repo_root/deploy/scripts" >/dev/null; then
  printf 'deployment scripts must not read or edit Minecraft world formats\n' >&2
  exit 1
fi
if grep -R -E '\|\| return$' "$repo_root/deploy/scripts" >/dev/null; then
  printf 'bare return after a failed guard exits set -e callers\n' >&2
  exit 1
fi

idle_test_root="$(mktemp -d)"
trap 'rm -rf "$idle_test_root"' EXIT
mkdir "$idle_test_root/volume"
cat >"$idle_test_root/docker" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == ps ]]; then
  printf 'running-container\n'
elif [[ "$1" == inspect ]]; then
  printf '%s\n' "$TEST_DOCKER_MOUNT"
fi
EOF
chmod +x "$idle_test_root/docker"
cat >"$idle_test_root/realpath" <<'EOF'
#!/usr/bin/env bash
[[ "${1:-}" == -m ]] && shift
[[ "${1:-}" == -- ]] && shift
python3 -c 'import os, sys; print(os.path.abspath(sys.argv[1]))' "$1"
EOF
chmod +x "$idle_test_root/realpath"

if PATH="$idle_test_root:$PATH" TEST_DOCKER_MOUNT="$idle_test_root/volume" \
  bash -c 'source "$1"; ensure_volume_idle "$2" false' \
  _ "$repo_root/deploy/lib/common.sh" "$idle_test_root/volume" \
  >/dev/null 2>&1; then
  printf 'volume idle check missed a running container mount\n' >&2
  exit 1
fi

PATH="$idle_test_root:$PATH" TEST_DOCKER_MOUNT="$idle_test_root/other" \
  bash -c 'source "$1"; ensure_volume_idle "$2" false' \
  _ "$repo_root/deploy/lib/common.sh" "$idle_test_root/volume"

bash "$repo_root/deploy/tests/agent-smoke.sh"
bash "$repo_root/deploy/tests/triage-smoke.sh"
bash "$repo_root/deploy/tests/agent-security.sh"

printf 'deployment artifact smoke tests passed\n'
