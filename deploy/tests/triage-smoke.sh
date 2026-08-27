#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

required_files=(
  deploy/scripts/colony-triage
  deploy/systemd/colony-triage-capture@.service
  deploy/systemd/colony-triage-scan.service
  deploy/systemd/colony-triage-scan.timer
  deploy/config/triage-watch.conf
)

for relative_path in "${required_files[@]}"; do
  [[ -f "$repo_root/$relative_path" ]] || {
    printf 'missing required triage file: %s\n' "$relative_path" >&2
    exit 1
  }
done

bash -n "$repo_root/deploy/scripts/colony-triage"

triage="$repo_root/deploy/scripts/colony-triage"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
queue="$work/queue"

printf 'failed to bind socket: Cannot assign requested address\n' \
  >"$work/evidence-a.txt"
printf 'NullPointerException in tick handler\n' >"$work/evidence-b.txt"

# A capture must not require systemd, so an operator (or a test) can replay an
# incident from saved evidence on any host.
"$triage" capture --unit colony-agent-proxy.socket \
  --evidence "$work/evidence-a.txt" --queue-dir "$queue" >/dev/null

incident_count() {
  find "$queue" -name '*.json' -type f 2>/dev/null | wc -l | tr -d ' '
}

[[ "$(incident_count)" == 1 ]] || {
  printf 'capture did not queue exactly one incident\n' >&2
  exit 1
}

incident_field() {
  python3 -c 'import glob, json, sys
paths = sorted(glob.glob(sys.argv[1] + "/*.json"))
print(json.load(open(paths[int(sys.argv[3])]))[sys.argv[2]])' \
    "$queue" "$1" "${2:-0}"
}

[[ "$(incident_field unit)" == colony-agent-proxy.socket ]] || {
  printf 'incident lost the failing unit name\n' >&2
  exit 1
}
[[ "$(incident_field occurrences)" == 1 ]] || {
  printf 'first capture should record one occurrence\n' >&2
  exit 1
}
grep -Fq 'Cannot assign requested address' \
  <<<"$(incident_field evidence)" || {
  printf 'incident did not retain its evidence\n' >&2
  exit 1
}

# The bridge incident emitted 1202 identical errors in ten minutes. Repeated
# captures of the same signature must collapse into one incident, or a trigger
# becomes its own denial of service.
"$triage" capture --unit colony-agent-proxy.socket \
  --evidence "$work/evidence-a.txt" --queue-dir "$queue" >/dev/null
"$triage" capture --unit colony-agent-proxy.socket \
  --evidence "$work/evidence-a.txt" --queue-dir "$queue" >/dev/null

[[ "$(incident_count)" == 1 ]] || {
  printf 'repeated identical captures must deduplicate\n' >&2
  exit 1
}
[[ "$(incident_field occurrences)" == 3 ]] || {
  printf 'deduplicated incident must count occurrences\n' >&2
  exit 1
}

first_seen="$(incident_field first_seen)"
last_seen="$(incident_field last_seen)"
[[ -n "$first_seen" && -n "$last_seen" ]] || {
  printf 'incident must record first and last seen timestamps\n' >&2
  exit 1
}

# A genuinely different failure must not be folded into the existing incident.
"$triage" capture --unit colony-autopilot.service \
  --evidence "$work/evidence-b.txt" --queue-dir "$queue" >/dev/null
[[ "$(incident_count)" == 2 ]] || {
  printf 'distinct failures must queue separately\n' >&2
  exit 1
}

# Scanning covers services that stay active while emitting hard errors, which
# is the case OnFailure= cannot see.
cat >"$work/mc.log" <<'LOG'
[Server thread/INFO]: Preparing spawn area: 0%
[Server thread/ERROR] [co.co.br.ColonyAutopilotBridge/]: Deadline verification failed for colony 1 work order 16
[Server thread/INFO]: Done (4.797s)!
LOG
scan_queue="$work/scan-queue"
"$triage" scan --unit minecraft --from-file "$work/mc.log" \
  --queue-dir "$scan_queue" >/dev/null
[[ "$(find "$scan_queue" -name '*.json' -type f | wc -l | tr -d ' ')" == 1 ]] || {
  printf 'scan did not queue the hard error it found\n' >&2
  exit 1
}

cat >"$work/clean.log" <<'LOG'
[Server thread/INFO]: Preparing spawn area: 0%
[Server thread/WARN]: Radium Class Analysis Error: class cannot be analysed
[Server thread/INFO]: Done (4.797s)!
LOG
clean_queue="$work/clean-queue"
"$triage" scan --unit minecraft --from-file "$work/clean.log" \
  --queue-dir "$clean_queue" >/dev/null
[[ "$(find "$clean_queue" -name '*.json' -type f 2>/dev/null | wc -l | tr -d ' ')" == 0 ]] || {
  printf 'scan must not queue warnings or informational lines\n' >&2
  exit 1
}

# A scan must key its signature on the error line, not on the surrounding
# chatter, so the same fault at a different tick or position deduplicates.
drift_queue="$work/drift-queue"
cat >"$work/drift-a.log" <<'LOG'
[10:01:02] [Server thread/ERROR]: Deadline verification failed for colony 1 work order 16 at BlockPos{x=-1394, y=71, z=511}
LOG
cat >"$work/drift-b.log" <<'LOG'
[11:44:59] [Server thread/ERROR]: Deadline verification failed for colony 1 work order 16 at BlockPos{x=-1401, y=68, z=498}
LOG
"$triage" scan --unit minecraft --from-file "$work/drift-a.log" \
  --queue-dir "$drift_queue" >/dev/null
"$triage" scan --unit minecraft --from-file "$work/drift-b.log" \
  --queue-dir "$drift_queue" >/dev/null
[[ "$(find "$drift_queue" -name '*.json' -type f | wc -l | tr -d ' ')" == 1 ]] || {
  printf 'same fault at a different position must deduplicate\n' >&2
  exit 1
}

# capture and scan answer different questions. systemd marking a unit failed is
# itself the incident, so capture must queue evidence that carries no severity
# token; scan has no such external signal and must require one.
keywordless_queue="$work/keywordless-queue"
"$triage" capture --unit colony-agent-proxy.socket \
  --evidence "$work/evidence-a.txt" --queue-dir "$keywordless_queue" >/dev/null
grep -aqE '(^|[^A-Za-z])(ERROR|FATAL|SEVERE|CRITICAL)([^A-Za-z]|$)' \
  "$work/evidence-a.txt" && {
  printf 'fixture no longer tests the keywordless path\n' >&2
  exit 1
}
[[ "$(find "$keywordless_queue" -name '*.json' -type f | wc -l | tr -d ' ')" == 1 ]] || {
  printf 'capture must queue a failed unit even without a severity keyword\n' >&2
  exit 1
}

# Real Minecraft logs are colorized. Escape codes stored verbatim make the
# evidence unreadable and waste the model's context on terminal control bytes.
ansi_queue="$work/ansi-queue"
printf '\033[m\033[1;31m[21:32:02] [main/ERROR]: something broke\033[m\n' \
  >"$work/ansi.log"
"$triage" scan --unit minecraft --from-file "$work/ansi.log" \
  --queue-dir "$ansi_queue" >/dev/null
ansi_evidence="$(python3 -c 'import glob, json, sys
paths = sorted(glob.glob(sys.argv[1] + "/*.json"))
print(json.load(open(paths[0]))["evidence"])' "$ansi_queue")"
[[ "$ansi_evidence" != *$'\033'* ]] || {
  printf 'stored evidence must not contain ANSI escape codes\n' >&2
  exit 1
}
grep -Fq 'something broke' <<<"$ansi_evidence" || {
  printf 'stripping escapes must not destroy the message\n' >&2
  exit 1
}

# A modpack logs the same benign startup error dozens of times per boot. Storing
# every copy pushes the actual fault out of the evidence window.
repeat_queue="$work/repeat-queue"
{
  for _ in $(seq 1 30); do
    printf '[10:00:0%d] [Server thread/ERROR]: Attempted to load class for invalid dist DEDICATED_SERVER\n' 1
  done
  printf '[10:00:31] [Server thread/ERROR]: Deadline verification failed for colony 1\n'
} >"$work/repeat.log"
"$triage" scan --unit minecraft --from-file "$work/repeat.log" \
  --queue-dir "$repeat_queue" >/dev/null
repeat_evidence="$(python3 -c 'import glob, json, sys
paths = sorted(glob.glob(sys.argv[1] + "/*.json"))
print(json.load(open(paths[0]))["evidence"])' "$repeat_queue")"
grep -Fq 'Deadline verification failed' <<<"$repeat_evidence" || {
  printf 'repeated noise must not evict the distinct fault from evidence\n' >&2
  exit 1
}
[[ "$(grep -Fc 'invalid dist' <<<"$repeat_evidence")" -le 2 ]] || {
  printf 'evidence must collapse repeated identical lines\n' >&2
  exit 1
}

# Truncating a pipeline with a trailing head closes it early; under pipefail the
# upstream dies of SIGPIPE and the scan aborts with 141, queueing nothing. It
# only bites once output crosses awk's buffer, so a size-based test is flaky and
# the constraint is asserted structurally instead.
if grep -nE '\|[[:space:]]*head( |$)' \
  "$repo_root/deploy/scripts/colony-triage" >/dev/null; then
  printf 'evidence pipelines must cap lines in awk, not with a trailing head\n' >&2
  grep -nE '\|[[:space:]]*head( |$)' "$repo_root/deploy/scripts/colony-triage" >&2
  exit 1
fi

# Evidence gathering must survive more distinct faults than it stores.
overflow_queue="$work/overflow-queue"
python3 -c '
for i in range(500):
    print(f"[10:00:00] [Server thread/ERROR]: distinct fault {i} of kind K{i}")
' >"$work/overflow.log"
"$triage" scan --unit minecraft --from-file "$work/overflow.log" \
  --queue-dir "$overflow_queue" >/dev/null || {
  printf 'scan exited non-zero on a log larger than the evidence limit\n' >&2
  exit 1
}
[[ "$(find "$overflow_queue" -name '*.json' -type f | wc -l | tr -d ' ')" == 1 ]] || {
  printf 'a log with more faults than the evidence limit must still queue\n' >&2
  exit 1
}
overflow_lines="$(python3 -c 'import glob, json, sys
paths = sorted(glob.glob(sys.argv[1] + "/*.json"))
print(len(json.load(open(paths[0]))["evidence"].splitlines()))' "$overflow_queue")"
((overflow_lines > 0 && overflow_lines <= 60)) || {
  printf 'evidence line count out of bounds: %s\n' "$overflow_lines" >&2
  exit 1
}

# Permanently benign errors must be suppressible per unit, or every server boot
# queues an incident nobody will ever act on and the queue stops being a signal.
ignore_conf="$work/watch.conf"
cat >"$ignore_conf" <<'CONF'
scan minecraft
ignore minecraft invalid dist DEDICATED_SERVER
ignore minecraft NoSuchFileException: /fabric.mod.json
CONF
cat >"$work/benign.log" <<'LOG'
[10:00:01] [main/ERROR]: Error for jar:file:///mods/a.jar: java.nio.file.NoSuchFileException: /fabric.mod.json
[10:00:02] [Server thread/ERROR]: Attempted to load class BlockModel for invalid dist DEDICATED_SERVER
LOG
ignore_queue="$work/ignore-queue"
COLONY_TRIAGE_WATCH_CONF="$ignore_conf" "$triage" scan --unit minecraft \
  --from-file "$work/benign.log" --queue-dir "$ignore_queue" >/dev/null
[[ "$(find "$ignore_queue" -name '*.json' -type f 2>/dev/null | wc -l | tr -d ' ')" == 0 ]] || {
  printf 'suppressed patterns must not queue an incident\n' >&2
  exit 1
}

# Suppression must be surgical: a real fault in the same stream still queues.
cat >"$work/mixed.log" <<'LOG'
[10:00:02] [Server thread/ERROR]: Attempted to load class BlockModel for invalid dist DEDICATED_SERVER
[10:00:03] [Server thread/ERROR]: Deadline verification failed for colony 1 work order 16
LOG
mixed_queue="$work/mixed-queue"
COLONY_TRIAGE_WATCH_CONF="$ignore_conf" "$triage" scan --unit minecraft \
  --from-file "$work/mixed.log" --queue-dir "$mixed_queue" >/dev/null
[[ "$(find "$mixed_queue" -name '*.json' -type f | wc -l | tr -d ' ')" == 1 ]] || {
  printf 'suppression must not hide a real fault in the same stream\n' >&2
  exit 1
}
mixed_evidence="$(python3 -c 'import glob, json, sys
paths = sorted(glob.glob(sys.argv[1] + "/*.json"))
print(json.load(open(paths[0]))["evidence"])' "$mixed_queue")"
grep -Fq 'invalid dist' <<<"$mixed_evidence" && {
  printf 'suppressed lines must be excluded from evidence\n' >&2
  exit 1
}

# A unit dominated by third-party noise cannot be tuned with ignore rules: the
# Minecraft container logs about 1,700 error lines a day from mods we do not
# own and cannot fix. Such units are scoped with include rules instead, so only
# code we own or a crash-class event can raise an incident.
allow_conf="$work/allow.conf"
cat >"$allow_conf" <<'CONF'
scan minecraft
include minecraft ColonyAutopilotBridge
include minecraft Exception in thread
CONF
cat >"$work/noisy.log" <<'LOG'
[10:00:01] [main/ERROR] [or.be.wo.to.ut.Logger/]: [worlds_together] Error for jar a.jar
[10:00:02] [main/ERROR] [io.gi.ap.or.Origins/]: Removed 1 missing powers from x:y
[10:00:03] [Server thread/ERROR] [ne.mi.fm.lo.RuntimeDistCleaner/]: invalid dist
LOG
allow_queue="$work/allow-queue"
COLONY_TRIAGE_WATCH_CONF="$allow_conf" "$triage" scan --unit minecraft \
  --from-file "$work/noisy.log" --queue-dir "$allow_queue" >/dev/null
[[ "$(find "$allow_queue" -name '*.json' -type f 2>/dev/null | wc -l | tr -d ' ')" == 0 ]] || {
  printf 'include rules must exclude third-party noise\n' >&2
  exit 1
}

cat >"$work/ours.log" <<'LOG'
[10:00:01] [main/ERROR] [or.be.wo.to.ut.Logger/]: [worlds_together] Error for jar a.jar
[10:00:02] [Server thread/ERROR] [co.co.br.ColonyAutopilotBridge/]: Deadline verification failed for colony 1
LOG
ours_queue="$work/ours-queue"
COLONY_TRIAGE_WATCH_CONF="$allow_conf" "$triage" scan --unit minecraft \
  --from-file "$work/ours.log" --queue-dir "$ours_queue" >/dev/null
[[ "$(find "$ours_queue" -name '*.json' -type f | wc -l | tr -d ' ')" == 1 ]] || {
  printf 'include rules must still catch errors from code we own\n' >&2
  exit 1
}
ours_evidence="$(python3 -c 'import glob, json, sys
paths = sorted(glob.glob(sys.argv[1] + "/*.json"))
print(json.load(open(paths[0]))["evidence"])' "$ours_queue")"
grep -Fq 'worlds_together' <<<"$ours_evidence" && {
  printf 'excluded lines must not appear in evidence\n' >&2
  exit 1
}

# A unit with no include rules keeps the default behavior: every hard error
# counts. Include is opt-in per unit, not a global switch.
default_queue="$work/default-queue"
COLONY_TRIAGE_WATCH_CONF="$allow_conf" "$triage" scan --unit ollama.service \
  --from-file "$work/noisy.log" --queue-dir "$default_queue" >/dev/null
[[ "$(find "$default_queue" -name '*.json' -type f | wc -l | tr -d ' ')" == 1 ]] || {
  printf 'a unit without include rules must keep default detection\n' >&2
  exit 1
}

# Suppression is scoped, so a pattern muted for one unit stays visible on
# another that has no ignore rule.
other_queue="$work/other-queue"
COLONY_TRIAGE_WATCH_CONF="$ignore_conf" "$triage" scan --unit ollama.service \
  --from-file "$work/benign.log" --queue-dir "$other_queue" >/dev/null
[[ "$(find "$other_queue" -name '*.json' -type f | wc -l | tr -d ' ')" == 1 ]] || {
  printf 'ignore rules must not leak across units\n' >&2
  exit 1
}

"$triage" list --queue-dir "$queue" | grep -Fq colony-agent-proxy.socket || {
  printf 'list did not report a queued incident\n' >&2
  exit 1
}

"$triage" clear --all --queue-dir "$queue" >/dev/null
[[ "$(incident_count)" == 0 ]] || {
  printf 'clear --all did not empty the queue\n' >&2
  exit 1
}

# Triggers capture evidence for a later burst session; they must never invoke
# the model or change service state themselves.
if grep -qE 'opencode|systemctl (start|stop|restart)|docker (start|stop|restart)' \
  "$repo_root/deploy/scripts/colony-triage"; then
  printf 'triage capture must only queue evidence, not act\n' >&2
  exit 1
fi

grep -Fq 'colony-triage-capture@' \
  "$repo_root/deploy/config/triage-watch.conf" || {
  printf 'watch config must wire units to the capture template\n' >&2
  exit 1
}
grep -Fq '%i' "$repo_root/deploy/systemd/colony-triage-capture@.service" || {
  printf 'capture template must receive the failed unit name\n' >&2
  exit 1
}
grep -Fq 'colony-triage-scan.service' \
  "$repo_root/deploy/systemd/colony-triage-scan.timer" || {
  printf 'scan timer must activate the scan service\n' >&2
  exit 1
}

printf 'triage trigger tests passed\n'
