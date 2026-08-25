#!/usr/bin/env bash
set -euo pipefail

apply=false
while (($#)); do
  case "$1" in
    --apply)
      apply=true
      shift
      ;;
    --help|-h)
      printf 'usage: agent-canary.sh [--apply]\n'
      exit 0
      ;;
    *)
      printf 'unknown option: %s\n' "$1" >&2
      exit 64
      ;;
  esac
done

if [[ "$apply" != true ]]; then
  printf 'dry-run: create an isolated seeded Python repair task\n'
  printf 'dry-run: run qwen3.5:4b through the authenticated OpenCode backend\n'
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
export XDG_CACHE_HOME="$HOME/cache"
export XDG_CONFIG_HOME="$HOME/config"
export XDG_DATA_HOME="$HOME/data"
export OPENCODE_CONFIG=/etc/colony-agent/opencode.json
canary_root="$(mktemp -d /srv/colony-agent/workspaces/.capability.XXXXXXXX)"
run_log="/var/lib/colony-agent/canary-$(date -u '+%Y%m%dT%H%M%SZ').json"
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
test_calculator.py with coverage for ordinary and empty inputs, run it and
confirm the empty-input test fails, then make the minimum fix in calculator.py
and rerun all tests. Do not edit README.md or access any path outside this
repository. Do not commit the change.'

timeout 900 /opt/colony-agent/bin/opencode run \
  --dir "$canary_root" \
  --model ollama/qwen3.5:4b \
  --auto \
  --format json \
  "$prompt" >"$run_log"

python3 -m unittest discover -s "$canary_root" -p 'test_*.py'
[[ -f "$canary_root/test_calculator.py" ]]
[[ "$(PYTHONPATH="$canary_root" python3 -c \
  'from calculator import average; print(average([]))' \
  2>/dev/null)" == "0.0" ]]

changed_paths="$(
  git -C "$canary_root" status --porcelain |
    awk '{sub(/^.../, ""); print}'
)"
while IFS= read -r changed_path; do
  [[ -z "$changed_path" ||
    "$changed_path" == calculator.py ||
    "$changed_path" == test_calculator.py ||
    "$changed_path" == __pycache__/* ]] || {
    printf 'canary changed an unauthorized path: %s\n' "$changed_path" >&2
    exit 1
  }
done <<<"$changed_paths"

ollama_memory="$(systemctl show ollama.service --property=MemoryCurrent --value)"
agent_memory="$(systemctl show colony-agent.service --property=MemoryCurrent --value)"
((ollama_memory <= 6442450944))
((agent_memory <= 1073741824))

printf 'local coding agent capability canary passed\n'
