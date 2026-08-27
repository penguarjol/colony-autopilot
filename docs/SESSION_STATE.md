# Session State: 2026-08-27

**Objective:** Make the local coding agent usable on the homeserver, and give it
static triggers that wake it on hard service errors so it can triage.

**Status:** COMPLETE — governor and harness work, all three live failures, and
the trigger subsystem landed and verified against the live server.

## Landed and verified

- `7325604` — Ollama resource governors and the canary harness that measured them.
- `be0a8e8` — read-only discovery tools allowed; 8B candidate registered.
- `0e6acd9` — LAN socket proxy adopted; `FreeBind=true` fixes the boot-time bind.
- `13fc6dc` — bridge stops looping on unsatisfiable block states.
- `15862b5` — agent starts with `opencode serve`, ending the `xdg-open` noise.
- `c0b02dd` — hard-error trigger subsystem.

Per-step agent latency went from ~350s to ~7s. The canary went from timing out
at 900s having completed two tool calls, to completing the full task in 129s.

Server matches the committed config: `OLLAMA_CONTEXT_LENGTH=16384`,
`OLLAMA_KEEP_ALIVE=5m`, `MemoryMax=6G`, background profile `CPUQuota=400%`,
default model `ollama/qwen3.5:4b`, deploy authority gate empty (revoked).

## Live failures, now fixed

1. **`colony-agent-proxy.socket` was down since 2026-08-26 08:14 UTC.** Binds
   `192.168.1.171:4096` before DHCP finalizes the address at boot:
   `Cannot assign requested address`, then never retries. LAN access on 4096 is
   dead. Fix is `FreeBind=true` in `[Socket]`. The unit file is untracked in git
   (`deploy/systemd/colony-agent-proxy.socket`) along with its service and the
   related `run-colony-agent` / `colony-agent.service` edits — that work belongs
   to another session and was deliberately left uncommitted. Resolved in
   `0e6acd9`: the whole proxy feature was adopted with `FreeBind=true` and
   assertions that the listener stays on a specific address rather than a
   wildcard.

2. **Bridge verification hot loop**, ~2 errors/second, work orders 13 and 16
   never complete. Two defects:
   - `BlueprintSemanticVerifier` verifies *transient* block states.
     `minecraft:jigsaw` is generation scaffolding that becomes air, and
     `water[level=1]` is flowing water. Neither can ever match. There is a skip
     path for air on the removal branch but no concept of an unverifiable state.
   - `StructureDeadlineReconciler` logs a FAILED verification without calling
     `sessions.remove(order)` or marking the order terminally failed, so the next
     tick re-enters VERIFYING forever. No retry budget, backoff, or dead-letter.

   Resolved in `13fc6dc` via `VerifiableBlockState` and
   `VerificationRetryBudget`, with an `ABANDONED` terminal status. Verified on
   the live server: zero bridge errors since the restart, against ~486 in the
   preceding 24 hours.

3. **`xdg-open` not found** — error-level noise on every agent start. Cosmetic.
   Resolved in `15862b5`: `opencode serve` exposes the same HTTP endpoint
   without trying to open a browser on a headless host.

## Model findings

| Model | Canary | Coexists with Minecraft | Failure mode |
|---|---|---|---|
| `granite4:tiny-h` | fail (97s) | yes | invented a different program; false success claim |
| `qwen3.5:4b` | fail (222s) | yes | never read the spec; omitted the import; misread a NameError as a syntax error for ten steps |
| `granite4.2:8b-16k` | **pass** (228s) | no | correct and truthful, but ~20 min/step on real source files |

No model on this host is both fast enough to run alongside the game server and
capable enough for autonomous triage. `granite4.2:8b` is a dense 8.8B model:
~2.2x slower than the 4B by size, degrading a further ~4x near the memory
ceiling. It needs Minecraft stopped and still took 743s to produce a one-line
unit change.

On the one real task it completed, it diagnosed the root cause correctly ("the
DHCP IP may not be finalized yet") then fixed it by binding `0.0.0.0` — moving
the code-executing agent's endpoint onto every interface — without flagging the
tradeoff. `FreeBind` appears zero times in its transcript.

## Traps found (worth not rediscovering)

- A model-baked `num_ctx` overrides `OLLAMA_CONTEXT_LENGTH`. `granite4.2:8b`
  ships `num_ctx=131072`; the env ceiling was ignored and llama-server grew to
  9 GB anon RSS, causing a **host-wide** OOM. Of the five installed models only
  this one does it. Pin such models with a derived Modelfile.
- Raising `MemoryMax` above what the host can spare converts a safe cgroup kill
  into a global OOM that can take Minecraft with it. Keep the cap protective.
- `colony-agent`'s login shell is `/usr/sbin/nologin`. Under `sudo -u` the agent
  inherits `SHELL=/usr/sbin/nologin` and every bash tool call returns "This
  account is currently not available". `runuser -u` gives `/bin/bash`. The canary
  now sets `SHELL` itself; `authorize-agent-deploy` correctly uses `runuser`.
- The canary memory gate is hardcoded to 6 GiB and measures `MemoryCurrent`,
  which includes reclaimable page cache. It should be profile-aware and measure
  working-set memory.

## Decisions taken

- Triage is **burst-only**: triggers queue incidents with evidence; the 8B runs
  when a dedicated session starts (Minecraft stopped).
- Detection needs **both** systemd `OnFailure=` hooks and a rate-limited
  log-pattern watcher. `OnFailure=` alone would have missed the bridge loop
  entirely — the unit stayed `active` while emitting 2 errors/second.
- Full autonomy through the existing `authorize-agent-deploy` canary gate is
  approved. That gate is currently closed because no available model passes the
  canary except `granite4.2:8b`, which is too slow to be practical.

## Trigger subsystem as built

`colony-triage` with `capture`, `scan`, `list`, `show`, `clear`. `OnFailure=`
drop-ins are generated from `/etc/colony-triage/watch.conf` for five units;
`colony-triage-scan.timer` polls scan targets every ten minutes over a
fifteen-minute window, so a burst straddling a boundary is still seen and
deduplication absorbs the overlap.

Two filters keep the queue readable. Incidents deduplicate on a timestamp- and
coordinate-normalized signature, tracking occurrence counts. Per-unit `include`
rules invert detection to an allowlist for noisy units.

Validated against the live 24-hour window: it caught the pre-fix bridge errors
as one incident with three distinct lines, and rejected ~1,700 lines of
third-party mod noise. Live queue empty, timer active, last scan successful.

Design constraint worth preserving: triggers capture evidence and never act.
Waking the 8B automatically on a host that cannot run it alongside the game
server would convert a logged error into a memory-pressure incident.

## Remaining work

1. Make the canary memory gate profile-aware so a raised cap does not read as a
   model failure.
2. Add a smoke check that the *effective* `n_ctx` matches the configured
   ceiling, so a baked `num_ctx` cannot silently override it again.
3. Open question: local autonomous triage on this host is bounded by memory
   bandwidth, not configuration. If it matters, that is a GPU conversation.
