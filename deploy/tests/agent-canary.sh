#!/usr/bin/env bash
set -euo pipefail

apply=false
model=""
timeout_seconds=900

usage() {
  cat <<'EOF'
usage: agent-canary.sh [--apply] [--model NAME] [--timeout SECONDS]

Runs the seeded repair task against a candidate model and reports the result
and elapsed time so candidates can be compared. The default is a dry run and
the default model is the one configured for the agent.
EOF
}

while (($#)); do
  case "$1" in
    --apply)
      apply=true
      shift
      ;;
    --model)
      (($# >= 2)) || {
        printf -- '--model requires a name\n' >&2
        exit 64
      }
      model="$2"
      shift 2
      ;;
    --timeout)
      (($# >= 2)) || {
        printf -- '--timeout requires a value in seconds\n' >&2
        exit 64
      }
      timeout_seconds="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'unknown option: %s\n' "$1" >&2
      exit 64
      ;;
  esac
done

[[ "$model" =~ ^[A-Za-z0-9._:/-]*$ ]] || {
  printf 'unsafe model name: %s\n' "$model" >&2
  exit 64
}
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] && ((timeout_seconds > 0)) || {
  printf 'timeout must be a positive whole number of seconds\n' >&2
  exit 64
}

if [[ "$apply" != true ]]; then
  printf 'dry-run: create an isolated seeded Python repair task\n'
  printf 'dry-run: run %s through the OpenCode maintenance agent\n' \
    "${model:-the configured model}"
  printf 'dry-run: bound the run at %s seconds and report elapsed_seconds\n' \
    "$timeout_seconds"
  printf 'dry-run: verify tests, path isolation, permissions, and memory ceilings\n'
  exit 0
fi

[[ "$(id -un)" == colony-agent ]] || {
  printf 'run capability canary as colony-agent\n' >&2
  exit 77
}
[[ ! -r /etc/shadow ]]
[[ ! -w /var/lib/pterodactyl/volumes ]]
if id -nG | tr ' ' '\n' | grep -Eq '^(docker|pterodactyl)$'; then
  printf 'colony-agent belongs to a privileged host group\n' >&2
  exit 1
fi

source /etc/colony-agent/agent.env
export HOME=/var/lib/colony-agent
# colony-agent's login shell is /usr/sbin/nologin, and OpenCode's bash tool
# inherits SHELL. Left unset, every command returns "This account is currently
# not available" and the model is scored on a broken tool surface.
export SHELL=/bin/bash
[[ -x "$SHELL" ]] || {
  printf 'agent bash tool has no usable shell: %s\n' "$SHELL" >&2
  exit 78
}
export XDG_CACHE_HOME="$HOME/cache"
export XDG_CONFIG_HOME="$HOME/config"
export XDG_DATA_HOME="$HOME/data"
export OPENCODE_CONFIG=/etc/colony-agent/opencode.json
if [[ -z "$model" ]]; then
  model="$(
    python3 -c \
      'import json, os; print(json.load(open(os.environ["OPENCODE_CONFIG"]))["model"])'
  )"
fi
canary_root="$(mktemp -d /srv/colony-agent/workspaces/.capability.XXXXXXXX)"
run_log="/var/lib/colony-agent/canary-${model//[^A-Za-z0-9]/-}-$(
  date -u '+%Y%m%dT%H%M%SZ'
).json"
trap 'rm -rf -- "$canary_root"' EXIT

cat >"$canary_root/calculator.py" <<'PY'
def average(values: list[float]) -> float:
    return sum(values) / len(values)
PY
cat >"$canary_root/README.md" <<'EOF'
The average of an empty collection is defined as 0.0.
EOF

git -C "$canary_root" init --quiet
git -C "$canary_root" config user.name "Capability Canary"
git -C "$canary_root" config user.email "canary@localhost"
git -C "$canary_root" add calculator.py README.md
git -C "$canary_root" commit --quiet -m "Seed average defect"

prompt='Diagnose the defect in this repository. Follow strict TDD: first create
test_calculator.py using only the Python standard library unittest module, with
coverage for ordinary and empty inputs. Run it with "python3 -m unittest
discover -p test_calculator.py" and confirm the empty-input test fails, then
make the minimum fix in calculator.py and rerun all tests. pytest and pip are
not installed. Do not edit README.md or access any path outside this
repository. Do not commit the change.'

started_at=$SECONDS
run_status=0
timeout "$timeout_seconds" /opt/colony-agent/bin/opencode run \
  --dir "$canary_root" \
  --model "$model" \
  --agent maintenance \
  --auto \
  --format json \
  "$prompt" >"$run_log" || run_status=$?
elapsed_seconds=$((SECONDS - started_at))

ollama_memory="$(systemctl show ollama.service --property=MemoryCurrent --value)"
agent_memory="$(systemctl show colony-agent.service --property=MemoryCurrent --value)"

report() {
  printf 'model=%s result=%s reason=%s elapsed_seconds=%s ollama_memory_bytes=%s agent_memory_bytes=%s\n' \
    "$model" "$1" "$2" "$elapsed_seconds" "$ollama_memory" "$agent_memory"
}

failure=""
if ((run_status == 124)); then
  failure="timeout_after_${timeout_seconds}s"
elif ((run_status != 0)); then
  failure="opencode_exit_$run_status"
elif [[ ! -f "$canary_root/test_calculator.py" ]]; then
  failure="no_test_written"
elif ! python3 -m unittest discover -s "$canary_root" -p 'test_*.py' \
  >/dev/null 2>&1; then
  failure="tests_failed"
elif [[ "$(PYTHONPATH="$canary_root" python3 -c \
  'from calculator import average; print(average([]))' \
  2>/dev/null)" != "0.0" ]]; then
  failure="defect_not_repaired"
elif ((ollama_memory > 6442450944 || agent_memory > 1073741824)); then
  failure="memory_ceiling_exceeded"
fi

if [[ -z "$failure" ]]; then
  changed_paths="$(
    git -C "$canary_root" status --porcelain |
      awk '{sub(/^.../, ""); print}'
  )"
  while IFS= read -r changed_path; do
    [[ -z "$changed_path" ||
      "$changed_path" == calculator.py ||
      "$changed_path" == test_calculator.py ||
      "$changed_path" == __pycache__/* ]] || {
      failure="unauthorized_path:$changed_path"
      break
    }
  done <<<"$changed_paths"
fi

if [[ -n "$failure" ]]; then
  report fail "$failure" >&2
  exit 1
fi

report pass none
printf 'local coding agent capability canary passed\n'
