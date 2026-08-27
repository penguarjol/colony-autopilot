#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

ollama_url="http://127.0.0.1:11434"
max_seconds=300
min_prompt_eval_rate=24
min_generate_rate=2.2
prompt_tokens_target=3500

usage() {
  cat <<'EOF'
Usage: benchmark.sh [options]

Checks the active Ollama limits and scores each configured model against a
realistic agent prompt. The default is a dry run.

Agentic tool loops are dominated by evaluating a multi-thousand-token
conversation prefix on every step, so acceptance scores prompt-evaluation and
generation throughput rather than the latency of a trivial request. The default
rates are the floor for the background CPU profile; the burst profile clears
them by roughly a factor of two.

Options:
  --ollama-url URL             Ollama API root (localhost only)
  --max-seconds N              Per-model wall-clock bound (default: 300)
  --min-prompt-eval-rate N     Minimum prompt eval tokens/sec (default: 24)
  --min-generate-rate N        Minimum generated tokens/sec (default: 2.2)
  --prompt-tokens N            Approximate prompt size to score (default: 3500)
  --apply                      Run the acceptance benchmark
  --help                       Show this help
EOF
}

while (($#)); do
  case "$1" in
    --ollama-url)
      (($# >= 2)) || die "--ollama-url requires a URL"
      ollama_url="$2"
      shift 2
      ;;
    --max-seconds)
      (($# >= 2)) || die "--max-seconds requires a value"
      max_seconds="$2"
      shift 2
      ;;
    --min-prompt-eval-rate)
      (($# >= 2)) || die "--min-prompt-eval-rate requires a value"
      min_prompt_eval_rate="$2"
      shift 2
      ;;
    --min-generate-rate)
      (($# >= 2)) || die "--min-generate-rate requires a value"
      min_generate_rate="$2"
      shift 2
      ;;
    --prompt-tokens)
      (($# >= 2)) || die "--prompt-tokens requires a value"
      prompt_tokens_target="$2"
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
for numeric in "$max_seconds" "$min_prompt_eval_rate" "$min_generate_rate"; do
  [[ "$numeric" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
    die "acceptance limits must be numeric"
done
[[ "$prompt_tokens_target" =~ ^[0-9]+$ ]] && ((prompt_tokens_target >= 500)) ||
  die "--prompt-tokens must be a whole number of at least 500"

if [[ "$APPLY" != true ]]; then
  quote_command systemctl show ollama.service \
    --property=CPUQuotaPerSecUSec,MemoryMax,Nice,IOSchedulingClass
  quote_command curl --fail --silent --show-error "$ollama_url/api/tags"
  for model in phi3:mini qwen3.5:4b; do
    quote_command curl --fail --silent --show-error \
      "$ollama_url/api/generate" \
      --data "{\"model\":\"$model\",\"stream\":false,\"keep_alive\":0,\"options\":{\"num_predict\":48},\"prompt\":\"<${prompt_tokens_target}-token repository excerpt>\"}"
  done
  log "dry-run: requests run sequentially and keep_alive is zero"
  log "dry-run: score prompt eval >= $min_prompt_eval_rate tok/s and generation >= $min_generate_rate tok/s"
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
grep -Eq '^CPUQuotaPerSecUSec=(4|8)([.]000000)?s$' <<<"$service_properties" ||
  die "Ollama CPUQuota is not a supported profile (400% or 800%)"
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

request_body="$work_dir/request.json"
python3 - "$request_body" "$prompt_tokens_target" <<'PY'
import json
import sys

target_tokens = int(sys.argv[2])
# A repetitive source-like block keeps the token count predictable while
# exercising the same tokenizer path as a real repository excerpt.
unit = (
    "def transform(record, index):\n"
    "    value = record.get('value', 0)\n"
    "    return {'id': index, 'scaled': value * 1.5, 'label': str(record)}\n"
)
tokens_per_unit = 43
body = {
    "prompt": "# module\n" + unit * max(1, target_tokens // tokens_per_unit)
    + "\nSummarize what this file does in one sentence.",
    "stream": False,
    "keep_alive": 0,
    # Reasoning models otherwise spend the whole output budget inside a thinking
    # block and return empty content, which is not the configuration the agent
    # runs (opencode.json sets reasoningEffort none).
    "think": False,
    "options": {"num_predict": 48},
}
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump(body, handle)
PY

benchmark_model() {
  local model="$1"
  local result_file="$work_dir/${model//[:\/]/_}.json"
  local model_body="$work_dir/${model//[:\/]/_}.request.json"
  local elapsed

  python3 - "$request_body" "$model_body" "$model" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    body = json.load(handle)
body["model"] = sys.argv[3]
with open(sys.argv[2], "w", encoding="utf-8") as handle:
    json.dump(body, handle)
PY

  elapsed="$(
    curl --fail --silent --show-error \
      --max-time "$max_seconds" \
      --output "$result_file" \
      --write-out '%{time_total}' \
      "$ollama_url/api/generate" \
      --data "@$model_body"
  )" || die "$model did not complete within ${max_seconds}s"

  local measurement
  measurement="$(
    python3 - "$result_file" "$min_prompt_eval_rate" "$min_generate_rate" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if not payload.get("done") or not payload.get("response", "").strip():
    raise SystemExit("Ollama returned an incomplete response")

min_prompt_rate = float(sys.argv[2])
min_generate_rate = float(sys.argv[3])


def rate(count_key, duration_key):
    count = payload.get(count_key)
    duration = payload.get(duration_key)
    if not count or not duration:
        raise SystemExit(f"Ollama response is missing {count_key}")
    return count / (duration / 1e9)


prompt_rate = rate("prompt_eval_count", "prompt_eval_duration")
generate_rate = rate("eval_count", "eval_duration")
load_seconds = payload.get("load_duration", 0) / 1e9

print(
    f"prompt_tokens={payload['prompt_eval_count']} "
    f"prompt_eval_rate={prompt_rate:.1f} "
    f"generate_rate={generate_rate:.1f} "
    f"load_seconds={load_seconds:.1f}"
)

if prompt_rate < min_prompt_rate:
    raise SystemExit(
        f"prompt eval {prompt_rate:.1f} tok/s is below {min_prompt_rate} tok/s"
    )
if generate_rate < min_generate_rate:
    raise SystemExit(
        f"generation {generate_rate:.1f} tok/s is below {min_generate_rate} tok/s"
    )
PY
  )" || die "$model failed throughput acceptance"

  printf '%s %s elapsed_seconds=%s result=pass\n' "$model" "$measurement" \
    "$elapsed"
}

benchmark_model phi3:mini
benchmark_model qwen3.5:4b
log "performance acceptance benchmark passed"
