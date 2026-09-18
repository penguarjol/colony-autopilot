# Colony Autopilot deployment

This repository contains the host-side deployment contract for Colony
Autopilot. The supplied scripts are dry-run by default, install the service in
observer mode, and do not start or restart anything unless explicitly asked.
They never rewrite Minecraft world data. Backups and clones copy the volume as
opaque files and verify the result.

## Architecture

The Minecraft server runs in its Pterodactyl container with
`/home/container` as its container root. On the Ubuntu 24.04 host, that data is
the volume:

```text
/var/lib/pterodactyl/volumes/fea5b15c-2aa0-473f-b361-3885b286cd7e
```

Colony Autopilot runs under the numeric UID and GID that own the bridge's
`.colony-autopilot` spool. Both implementations enforce owner-only directories
and files, so a separate service user or a group ACL would break the IPC
contract. Systemd grants that runtime identity read-write access only to the
shared spool, `/var/lib/colony-autopilot`, and
`/var/log/colony-autopilot`. Controller stdout and stderr go to journald; the
log directory is reserved for benchmark reports and operator exports.

Ollama remains a separate system service and listens only on
`127.0.0.1:11434`. Its override permits one request and one loaded model at a
time, and applies `MemoryMax=6G`, a 16,384-token context ceiling, a five-minute
model residency, `Nice=10`, and idle I/O scheduling. The coding agent uses
`qwen3.5:4b`; `phi3:mini` remains available for the existing planner.

The context ceiling and model residency are both load-bearing. A 65,536-token
context saturates `MemoryMax=6G` for a 4B model and forces the cgroup into
continuous reclaim, and unloading between requests discards the prompt cache so
every tool step re-evaluates the whole conversation prefix instead of the new
tokens. The ceiling must also match `limit.context` in `opencode.json`: OpenCode
packs prompts up to its own limit and Ollama silently truncates anything beyond
`num_ctx`, so a mismatch corrupts tool loops mid-conversation.

CPU allocation has two profiles, switched with `colony-agent-mode`:

```text
background  CPUQuota=400%   coexists with the Minecraft server, for triage
burst       CPUQuota=800%   full inference speed, for dedicated agent work
```

Generation throughput saturates host memory bandwidth at 800%, so a higher
quota is unusable: Ollama runs one thread per physical core and the extra quota
goes unspent. Making a model generate faster than that requires touching fewer
weight bytes per token, which means a smaller or mixture-of-experts model, not
more CPU.

```text
Minecraft container (/home/container)
          |
          | bridge-local events/control
          v
colony-autopilot.service (observer by default)
          |
          | localhost HTTP, sequential
          v
ollama.service (127.0.0.1:11434)
```

No deployment artifact parses or edits NBT, region, or player data. Operational
copies are made only while the volume is idle unless an operator explicitly
accepts a crash-consistent live copy with `--allow-live`.

## Files and installed paths

- `deploy/config/autopilot.env` installs to
  `/etc/default/colony-autopilot`.
- `deploy/config/controller.toml` installs to
  `/etc/colony-autopilot/controller.toml`.
- The Python controller installs under `/opt/colony-autopilot/controller` and
  its executable installs to `/opt/colony-autopilot/bin/colony-autopilot`.
- `deploy/systemd/colony-autopilot.service` installs to
  `/etc/systemd/system/colony-autopilot.service`.
- The detected spool identity installs to
  `/etc/systemd/system/colony-autopilot.service.d/runtime-user.conf`.
- The Ollama drop-in installs to
  `/etc/systemd/system/ollama.service.d/zz-colony-autopilot.conf`.
- `autopilotctl` installs to `/usr/local/sbin/autopilotctl`.
- Verified backups default to `/var/backups/colony-autopilot`.

The installer copies the standard-library Python controller from this checkout
into `/opt/colony-autopilot`. Its command-line contract is:

```text
colony-autopilot --config PATH validate-config
colony-autopilot --config PATH start
```

## Install and service operations

Inspect the installation without changing the host:

```bash
sudo deploy/scripts/install.sh
```

Install the files, preserving existing config:

```bash
sudo deploy/scripts/install.sh --apply
sudo systemd-analyze verify /etc/systemd/system/colony-autopilot.service
systemctl cat colony-autopilot.service
```

Run the Minecraft bridge once before applying the installer. Installation
refuses to guess an identity: the spool plus its `requests`, `responses`, and
`rejected` directories must already exist, use mode `0700`, and have one
numeric owner. The generated systemd drop-in uses that UID and GID. Neither the
installer nor systemd changes bridge ownership or permissions.

Review the Ollama override before activating it. Restarting Ollama unloads
current models and briefly interrupts requests:

```bash
systemctl cat ollama.service
sudo deploy/scripts/install.sh --restart-ollama --apply
curl --fail http://127.0.0.1:11434/api/tags
```

The installer does not start Autopilot unless `--start` is passed. Routine
control is:

```bash
autopilotctl status
sudo autopilotctl start --apply
sudo autopilotctl stop --apply
autopilotctl logs
```

Keep `controller.mode = "observer"` and `planner.enabled = false` until the
bridge, permissions, backups, and acceptance benchmark have been reviewed.
Existing configuration is not overwritten unless
`install.sh --replace-config --apply` is used; replaced managed files are
copied below
`/var/backups/colony-autopilot/install-TIMESTAMP/`.

## Command center

All server services are accessible from a single URL:

```text
http://192.168.1.171:3000
```

The dashboard is Homarr. Its board, integrations, and app tiles are built by
[`deploy/scripts/build-homarr-board.py`](deploy/scripts/build-homarr-board.py)
and [`deploy/scripts/place-board-items.py`](deploy/scripts/place-board-items.py)
through Homarr's own API. Do not edit Homarr's SQLite file directly: raw
inserts skip validation, never create integration records, and produce tiles
that render but go nowhere.

Live health for every service is in Uptime Kuma, seeded by
[`deploy/scripts/configure-kuma.py`](deploy/scripts/configure-kuma.py). Monitors
target the Caddy-published ports with the shared credential, so a passing
monitor proves the same path a browser takes.

The agent is wired to the dashboard in both directions. Homarr's MCP server is
registered in [`deploy/config/opencode.json`](deploy/config/opencode.json), so
the agent can query and control apps, boards, Docker containers, and DNS
blocking in natural language; an iframe widget on the board embeds the agent
so sessions are created and managed without leaving the dashboard.

Verify the whole system with:

```bash
sudo bash deploy/tests/command-center-integration.sh
```

A Caddy reverse proxy on port 3000 provides unified basic authentication.
The browser caches credentials for the origin, so a single sign-in covers
every path. The same username and password from `/etc/colony-agent/agent.env`
are used everywhere.

| Path | Service |
|---|---|
| `/` | Homepage dashboard (service tiles, status, bookmarks) |
| `/opencode/` | Local coding agent web UI |
| `/status/` | Uptime Kuma monitoring (uptime history, alerts) |
| `/ollama/` | Ollama API (direct model queries) |

Install the command center after the coding agent is running:

```bash
sudo deploy/scripts/install-command-center.sh --apply
```

Remove it without losing agent or Autopilot state:

```bash
sudo deploy/scripts/uninstall-command-center.sh --apply
```

Homepage and Uptime Kuma run as Docker containers bound to localhost. Caddy
runs as a native systemd service. The standalone systemd socket proxy on
port 4096 is disabled when the command center is installed; Caddy replaces it.

## Local coding agent

The local agent runs OpenCode 1.18.22 as the dedicated `colony-agent` account
and uses `ollama/qwen3.5:4b`. Its writable source tree is
`/srv/colony-agent/workspaces/colony-autopilot`; the live application and
Minecraft volume are not agent-writable.

OpenCode listens on `127.0.0.1:4097`. The command center's Caddy proxy
publishes it at `/opencode/` on port 3000, so the agent process has no direct
LAN access. Ollama remains reachable only through localhost.

The generated username and password are stored in
`/etc/colony-agent/agent.env`, readable by root and the service account. The
same credentials are used by the command center's Caddy proxy. An
administrator can attach the SSH TUI to the same backend and shared sessions:

```bash
ssh serveradmin@192.168.1.171
sudo colony-agent-cli
```

Install the runtime without enabling autonomous deployment:

```bash
sudo deploy/scripts/install-agent.sh --apply
sudo deploy/scripts/init-agent-workspace.sh \
  --bundle /path/to/approved-source.bundle \
  --branch session/local-agent-20260824 \
  --apply
sudo systemctl enable --now colony-agent.service
```

Switch the CPU profile around dedicated agent sessions. The quota is applied
with `systemctl set-property --runtime`, so a reboot always returns to the
background profile and burst cannot be left applied by accident:

```bash
colony-agent-mode status
sudo colony-agent-mode burst --apply
sudo colony-agent-mode background --apply
```

The agent can read, edit, test, branch, and commit inside its clone. Host
operations use the fixed `colony-agent-op status|deploy|restart|rollback`
protocol. A root-owned socket broker maps those four words to non-editable
wrappers; arbitrary root commands, Docker, direct systemd control, force
pushes, external paths, and live-volume access remain unavailable.

Deploy, restart, and rollback are disabled until the installed capability
canary succeeds:

```bash
sudo authorize-agent-deploy --apply
sudo authorize-agent-deploy --revoke --apply
```

Each deployment requires a clean pushed `agent/*` commit, runs bridge,
controller, deployment, and security gates as `colony-agent`, verifies a full
backup, records hashes, health-checks Minecraft and Autopilot, and rolls back
automatically on failure. Manual rollback is:

```bash
sudo colony-agent-rollback
```

Deployment state is root-owned under `/var/lib/colony-agent-ops`; audit records
are under `/var/log/colony-agent/deployments`; source and world backups are
under `/var/backups/colony-autopilot`. Stop or remove the agent with:

```bash
sudo systemctl disable --now colony-agent.service
sudo deploy/scripts/uninstall-agent.sh --apply
```

The 4B model is intended for bounded maintenance and seeded repairs. It is not
equivalent to a senior cloud coding agent. If the capability or resource
canary fails, keep deployment authority revoked and use chat, read, edit, and
test functions only.

## Hard-error triggers

Two detection paths queue evidence when a homeserver service breaks, because
neither one alone sees every failure. `OnFailure=` drop-ins fire when systemd
marks a unit failed, which covers a crash or a socket that cannot bind. A
timer-driven log scan covers the opposite case: `colony-agent-proxy.socket` sat
inactive for eleven days and the Minecraft bridge logged 1,202 errors in ten
minutes while its container stayed healthy and `colony-autopilot.service`
stayed active, so nothing was ever marked failed.

```bash
sudo deploy/scripts/install-triage.sh --apply
colony-triage list
colony-triage show <id>
colony-triage clear <id>
```

Watched units, scan targets, and per-unit filters live in
`/etc/colony-triage/watch.conf`. The installed file is authoritative;
`--replace-config` is required to overwrite it, so a unit added on the host
survives a reinstall.

A queue only helps if what lands in it is worth reading. `ignore <unit> <text>`
drops a permanently benign error; `include <unit> <text>` inverts the default
to an allowlist for that unit alone. Minecraft needs the allowlist: a 24-hour
survey found roughly 1,700 error lines, 618 from a library probing Forge jars
for a `fabric.mod.json` and 526 from Forge refusing client-only classes on a
dedicated server, none of it fixable here and all of it recurring every boot.
Scoped to `ColonyAutopilot` plus crash-class markers, the same window yields
one incident with three distinct lines.

Triggers capture evidence and nothing else. They do not restart services and
do not invoke the model, because an 8B model cannot run concurrently with the
game server on this host: waking it automatically would turn a logged error
into a memory-pressure incident. Incidents are deduplicated by unit plus a
timestamp- and coordinate-normalized error signature, tracking occurrence
counts rather than queueing one entry per line, so a hot loop produces one
incident to review in the next burst session.

## Existing server integration

`/usr/local/bin/server` is the host service manager, not the Minecraft process.
Add the entry from `deploy/integration/server-service-map.snippet.sh`
immediately after its `SERVICE_MAP` declaration:

```bash
SERVICE_MAP[autopilot]="colony-autopilot.service"
```

This preserves the manager and makes its existing verbs available as
`server autopilot start`, `server autopilot stop`, and
`server autopilot status`. Back up `/usr/local/bin/server` before applying the
one-line host patch.

## Bridge protocol

The controller and bridge use the same protocol-v1 envelopes and
`requests/`, `responses/`, and `rejected/` directories. Requests and responses
are mode `0600`; directories are mode `0700`. The controller obtains status
and snapshots through typed bridge requests rather than reading a standalone
snapshot file. Observer mode remains the deployment default and never submits
a mutating action.

## Backup and clone operations

Stop both Autopilot and the Minecraft server before taking an operational
backup. Start with a dry run:

```bash
sudo deploy/scripts/backup.sh
sudo deploy/scripts/backup.sh --apply
```

The backup is written through a staging directory, gets a SHA-256 sidecar, is
verified before publication, and is verified again at its final path. Restore
testing should extract into a new empty directory, verify the sidecar first,
and then register that directory as a separate Pterodactyl volume.

Cloning always creates a verified backup first. The destination must be a new
sibling of the source and is published only after an rsync checksum comparison:

```bash
sudo deploy/scripts/clone-world.sh \
  --destination /var/lib/pterodactyl/volumes/NEW-UUID
sudo deploy/scripts/clone-world.sh \
  --destination /var/lib/pterodactyl/volumes/NEW-UUID \
  --apply
```

`--allow-live` is an explicit exception for crash-consistent copies. It does
not make a live copy transactionally consistent.

## Bridge jar installation

`deploy/scripts/install-bridge.sh` has no default volume and never permits an
in-use volume. Build and test the bridge, calculate its checksum, and select an
explicit stopped clone:

```bash
cd bridge
gradle clean test build
sha256sum build/libs/colonyautobridge-0.1.0.jar
cd ..

sudo deploy/scripts/install-bridge.sh \
  --volume /var/lib/pterodactyl/volumes/CLONE-UUID \
  --jar bridge/build/libs/colonyautobridge-0.1.0.jar \
  --sha256 EXPECTED_64_CHARACTER_SHA256
```

The first invocation is a dry run. It requires the volume to exist as a direct
child of `/var/lib/pterodactyl/volumes`, confirms `fuser` reports it idle,
verifies the source checksum and archive, and checks the concrete Forge
metadata, manifest vendor, and bridge entrypoint class. Apply only after
reviewing that output:

```bash
sudo deploy/scripts/install-bridge.sh \
  --volume /var/lib/pterodactyl/volumes/CLONE-UUID \
  --jar bridge/build/libs/colonyautobridge-0.1.0.jar \
  --sha256 EXPECTED_64_CHARACTER_SHA256 \
  --apply
```

Existing bridge jars are checksummed and copied to
`/var/backups/colony-autopilot/bridge/CLONE-UUID/TIMESTAMP/` before replacement.
The new jar is staged and checksum-verified inside `mods/`, then published with
an atomic rename as `mods/colonyautobridge.jar`. Its checksum sidecar is stored
at the volume root so mod scanners never interpret it as a mod artifact.

The known live volume is rejected unless the separate confirmation
`--confirm-live-volume INSTALL_BRIDGE_ON_LIVE_VOLUME` is supplied. This token
only confirms the path exception; it does not bypass the stopped-volume check.
Clone-first installation remains the required operational procedure.

Removal and rollback are also dry-run by default and require the same explicit,
stopped volume:

```bash
sudo deploy/scripts/install-bridge.sh \
  --volume /var/lib/pterodactyl/volumes/CLONE-UUID \
  --remove

sudo deploy/scripts/install-bridge.sh \
  --volume /var/lib/pterodactyl/volumes/CLONE-UUID \
  --rollback /var/backups/colony-autopilot/bridge/CLONE-UUID/TIMESTAMP
```

Add `--apply` only after reviewing the plan. Removal creates and verifies a
backup first. Rollback accepts only a backup beneath that clone's managed
backup directory, validates its manifest and each bridge jar, and backs up the
current jar set before restoring.

## Performance acceptance

The benchmark is dry-run unless `--apply` is supplied. It verifies the active
systemd CPU, memory, nice, and I/O limits; confirms Ollama is bound to
localhost; confirms both models exist; and sends one bounded request to each
model sequentially with `keep_alive: 0`.

```bash
sudo deploy/scripts/benchmark.sh
sudo deploy/scripts/benchmark.sh --apply \
  | tee "/var/log/colony-autopilot/benchmark-$(date -u +%Y%m%dT%H%M%SZ).log"
```

Acceptance scores throughput against a 3,500-token prompt, not the latency of a
trivial request. It requires at least 30 prompt-eval tokens per second and 3.5
generated tokens per second, which are the floor for the background profile; the
burst profile clears them by roughly a factor of two. Run the benchmark with
Autopilot stopped so its results are reproducible.

Scoring a trivial prompt measures model load time and nothing else. Agent tool
loops spend their time evaluating a multi-thousand-token conversation prefix on
every step, so a short-prompt latency check can pass while real steps take
minutes. Override the rate floors only when a measured hardware baseline has
been reviewed.

## End-to-end acceptance

Run behavioral acceptance against a cloned world before rollout and against
the live spool after deployment:

```bash
python3 -m controller.acceptance --spool /path/to/.colony-autopilot \
  --scenario health
python3 -m controller.acceptance --spool /path/to/.colony-autopilot \
  --scenario deadline --wait-seconds 30
python3 -m controller.acceptance --spool /path/to/.colony-autopilot \
  --scenario request --request-id REQUEST_UUID --wait-seconds 180
```

The request watcher must start while the citizen request is still open and
before the controller dispatches it. It passes only when the initial snapshot
identifies a deliverable citizen owner and a later snapshot no longer contains
that exact MineColonies request UUID. The deadline scenario fails on any
`FAILED` job and requires cursor advancement or completed work.

## Uninstall and rollback

Preview uninstall first:

```bash
sudo deploy/scripts/uninstall.sh
sudo deploy/scripts/uninstall.sh --apply
```

The normal uninstall stops and disables Autopilot and removes only managed
service files. It preserves configuration, state, logs, application files,
backups, and the Minecraft volume. Purging config/state/logs requires both
`--purge` and `--confirm-purge colony-autopilot`; those paths are archived and
checksummed before removal.

Rollback procedure:

1. Stop Autopilot with `sudo autopilotctl stop --apply`.
2. Inspect the newest `/var/backups/colony-autopilot/install-TIMESTAMP/`.
3. Copy each required file from that directory back to its original absolute
   path, preserving owner and mode.
4. If no prior Ollama override existed, remove only
   `/etc/systemd/system/ollama.service.d/zz-colony-autopilot.conf`.
5. Run `sudo systemctl daemon-reload` and `sudo systemctl restart ollama`.
6. Verify `systemctl cat ollama`, its localhost listener, and `/api/tags`.
7. Validate `/etc/colony-autopilot/controller.toml` with the installed
   executable.
8. Confirm `runtime-user.conf` still matches the spool's numeric owner.
9. Start Autopilot only after `systemd-analyze verify`, spool access, and the
   acceptance benchmark pass.

World restore is deliberately separate from service rollback. Verify an
archive with `sha256sum -c`, extract it to a new sibling directory, compare it
to a throwaway clone, and update Pterodactyl only during a maintenance window.
Never extract a backup over the active volume.
