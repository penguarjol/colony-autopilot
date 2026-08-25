#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

ollama_url="http://127.0.0.1:11434"
phi_limit=60
coder_limit=90

usage() {
  cat <<'EOF'
Usage: benchmark.sh [options]

Checks the active Ollama limits and runs one bounded, sequential request per
configured model. The default is a dry run.

Options:
  --ollama-url URL       Ollama API root (localhost only)
  --phi-max-seconds N    Acceptance limit for phi3:mini (default: 60)
  --coder-max-seconds N  Acceptance limit for qwen2.5-coder:3b (default: 90)
  --apply                Run the acceptance benchmark
  --help                 Show this help
EOF
}

while (($#)); do
  case "$1" in
    --ollama-url)
      (($# >= 2)) || die "--ollama-url requires a URL"
      ollama_url="$2"
      shift 2
      ;;
    --phi-max-seconds)
      (($# >= 2)) || die "--phi-max-seconds requires a value"
      phi_limit="$2"
      shift 2
      ;;
    --coder-max-seconds)
      (($# >= 2)) || die "--coder-max-seconds requires a value"
      coder_limit="$2"
      shift 2
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

[[ "$ollama_url" =~ ^http://(127\.0\.0\.1|localhost):[0-9]+$ ]] ||
  die "benchmark only permits a localhost Ollama URL"
[[ "$phi_limit" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
  die "--phi-max-seconds must be numeric"
[[ "$coder_limit" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
  die "--coder-max-seconds must be numeric"

if [[ "$APPLY" != true ]]; then
  quote_command systemctl show ollama.service \
    --property=CPUQuotaPerSecUSec,MemoryMax,Nice,IOSchedulingClass
  quote_command curl --fail --silent --show-error "$ollama_url/api/tags"
  quote_command curl --fail --silent --show-error \
    "$ollama_url/api/generate" \
    --data '{"model":"phi3:mini","stream":false,"keep_alive":0,"options":{"num_predict":8},"prompt":"Reply with exactly OK."}'
  quote_command curl --fail --silent --show-error \
    "$ollama_url/api/generate" \
    --data '{"model":"qwen3.5:4b","stream":false,"keep_alive":0,"options":{"num_predict":8},"prompt":"Reply with exactly OK."}'
  log "dry-run: requests run sequentially and keep_alive is zero"
  exit 0
fi

require_command curl
require_command python3
require_command ss
require_command systemctl

if ! ss -ltn | awk '$4 == "127.0.0.1:11434" { found=1 } END { exit !found }'; then
  die "Ollama is not listening on 127.0.0.1:11434"
fi

service_properties="$(
  systemctl show ollama.service \
    --property=CPUQuotaPerSecUSec,MemoryMax,Nice,IOSchedulingClass
)"
printf '%s\n' "$service_properties"
grep -Eq '^CPUQuotaPerSecUSec=1[.]5(00000)?s$' <<<"$service_properties" ||
  die "Ollama CPUQuota is not 150%"
grep -Fqx 'MemoryMax=6442450944' <<<"$service_properties" ||
  die "Ollama MemoryMax is not 6G"
grep -Fqx 'Nice=10' <<<"$service_properties" ||
  die "Ollama Nice is not 10"
grep -Fqx 'IOSchedulingClass=3' <<<"$service_properties" ||
  die "Ollama I/O scheduling class is not idle"

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

curl --fail --silent --show-error "$ollama_url/api/tags" \
  --output "$work_dir/tags.json"
python3 - "$work_dir/tags.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    names = {model["name"] for model in json.load(handle).get("models", [])}

required = {"phi3:mini", "qwen3.5:4b"}
missing = required - names
if missing:
    raise SystemExit(f"missing required Ollama models: {', '.join(sorted(missing))}")
PY

benchmark_model() {
  local model="$1"
  local limit="$2"
  local result_file="$work_dir/${model//[:\/]/_}.json"
  local elapsed

  elapsed="$(
    curl --fail --silent --show-error \
      --output "$result_file" \
      --write-out '%{time_total}' \
      "$ollama_url/api/generate" \
      --data "{\"model\":\"$model\",\"stream\":false,\"keep_alive\":0,\"options\":{\"num_predict\":8},\"prompt\":\"Reply with exactly OK.\"}"
  )"

  python3 - "$result_file" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if not payload.get("done") or not payload.get("response", "").strip():
    raise SystemExit("Ollama returned an incomplete response")
PY

  awk -v elapsed="$elapsed" -v limit="$limit" \
    'BEGIN { if (elapsed > limit) exit 1 }' ||
    die "$model exceeded ${limit}s acceptance limit (${elapsed}s)"
  printf '%s latency_seconds=%s limit_seconds=%s result=pass\n' \
    "$model" "$elapsed" "$limit"
}

benchmark_model phi3:mini "$phi_limit"
benchmark_model qwen3.5:4b "$coder_limit"
log "performance acceptance benchmark passed"
