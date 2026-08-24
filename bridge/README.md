# Colony Autopilot Bridge

Client-optional, server-only Forge 1.20.1 integration for MineColonies. The
bridge starts disabled in `OBSERVER` mode and does not register a network
channel or listen on a TCP/UDP port.

## Build

Requirements:

- JDK 17
- Gradle 8.x (or add a project-approved Gradle wrapper)
- Forge `1.20.1-47.4.13`
- MineColonies `1.20.1-1.1.1274` from Curse Maven file `8562856`

Run:

```text
gradle clean test build
```

Set `MINECOLONIES_JAR` to an absolute local jar path for reproducible or
rate-limit-independent builds:

```text
MINECOLONIES_JAR=/tmp/minecolonies-1.20.1-1.1.1278-snapshot.jar \
  gradle clean test build
```

When the variable is absent, Gradle falls back to Curse Maven file `8562856`.
An override pointing to a missing or non-file path fails configuration instead
of silently using a different artifact.

The MineColonies dependency is `compileOnly`; the server modpack must install
MineColonies and its required runtime dependencies separately.

## Server configuration

Forge writes the server config to the world's standard `serverconfig`
directory. No custom world registry or saved-data format is used.

Safe defaults:

- `enabled=false`
- `mode=OBSERVER`
- `dailyActionBudget=0`
- one IPC request per tick gate

Commands require the configured permission level:

```text
/colonyauto on
/colonyauto off
/colonyauto status
/colonyauto mode [observer|active]
/colonyauto budget [actions]
/colonyauto pause [ticks]
/colonyauto next
/colonyauto explain
```

`on` does not switch the mode or grant a budget. A mutating action only runs
when all three gates are explicitly configured.

## Local IPC

The intended native endpoint is:

```text
/home/container/.colony-autopilot/bridge.sock
```

The scaffold uses a file spool under the same private directory:

```text
/home/container/.colony-autopilot/requests
/home/container/.colony-autopilot/responses
/home/container/.colony-autopilot/rejected
```

Java 17 supports Unix-domain sockets, but a production socket needs framing,
partial-read handling, connection lifecycle management, peer-credential
verification, and a background I/O executor. Implementing that directly in
the Forge lifecycle would make startup and shutdown more fragile. The spool
keeps all I/O local, performs no network bind, uses atomic moves, rejects
symlinks and oversized requests, and enforces POSIX owner-only permissions.
The server reads files only at the configured tick gate, so MineColonies API
access and actions remain on the server thread.

Writers must create request files with mode `0600`, write them completely to a
temporary file on the same filesystem, then atomically rename them to
`requests/<idempotency_key>.json`. Every v1 envelope has exactly these fields;
unknown or missing fields are rejected. `request_id` and `idempotency_key`
must match, `nonce` must be a UUID, and `rationale` must be non-empty.

Status:

```json
{
  "type": "controller.action.v1",
  "request_id": "status-20260821-01",
  "idempotency_key": "status-20260821-01",
  "nonce": "046583ec-522b-45d2-8655-3cd7c83e8f42",
  "issued_at_epoch_ms": 1787342400000,
  "action": "status",
  "payload": {},
  "rationale": "periodic health poll"
}
```

Snapshot:

```json
{
  "type": "controller.action.v1",
  "request_id": "snapshot-20260821-01",
  "idempotency_key": "snapshot-20260821-01",
  "nonce": "b38fbcef-e5e4-4b57-a6a7-556a7e5406df",
  "issued_at_epoch_ms": 1787342400000,
  "action": "snapshot",
  "payload": {"colony_id": 7, "dimension": "minecraft:overworld"},
  "rationale": "refresh colony evidence"
}
```

Use an empty snapshot payload to read all colonies in loaded levels.

Assign an existing unclaimed work order to an idle existing builder:

```json
{
  "type": "controller.action.v1",
  "request_id": "assign-20260821-01",
  "idempotency_key": "assign-20260821-01",
  "nonce": "eec20843-a508-447d-9f18-d322e7d55ad6",
  "issued_at_epoch_ms": 1787342400000,
  "action": "assign_builder",
  "payload": {
    "colony_id": 7,
    "dimension": "minecraft:overworld",
    "work_order_id": 42,
    "builder_pos": {"x": 100, "y": 64, "z": 200},
    "actor_uuid": "591e02e4-5297-4b20-9105-690748338427"
  },
  "rationale": "assign the compatible idle builder"
}
```

Request an existing building upgrade:

```json
{
  "type": "controller.action.v1",
  "request_id": "upgrade-20260821-01",
  "idempotency_key": "upgrade-20260821-01",
  "nonce": "1d0fb8a3-ec96-4dcb-ac28-66375fd7ea08",
  "issued_at_epoch_ms": 1787342400000,
  "action": "request_upgrade",
  "payload": {
    "colony_id": 7,
    "dimension": "minecraft:overworld",
    "building_pos": {"x": 120, "y": 64, "z": 210},
    "builder_pos": {"x": 100, "y": 64, "z": 200},
    "actor_uuid": "591e02e4-5297-4b20-9105-690748338427"
  },
  "rationale": "upgrade an idle existing building"
}
```

Grant only against a specific existing MineColonies deliverable request owned
by a specific existing building:

```json
{
  "type": "controller.action.v1",
  "request_id": "grant-20260821-01",
  "idempotency_key": "grant-20260821-01",
  "nonce": "a1c153a4-8d89-4635-8d8c-a1a9f4330ce4",
  "issued_at_epoch_ms": 1787342400000,
  "action": "grant",
  "payload": {
    "colony_id": 7,
    "dimension": "minecraft:overworld",
    "building_pos": {"x": 120, "y": 64, "z": 210},
    "minecolonies_request_id": "1d6514f4-17b9-4f06-ad27-d3c199e9bf55",
    "item": "minecraft:oak_log",
    "quantity": 8,
    "actor_uuid": "591e02e4-5297-4b20-9105-690748338427"
  },
  "rationale": "fulfill one observed open request"
}
```

Pause mutations, or resume with `ticks: 0`:

```json
{
  "type": "controller.action.v1",
  "request_id": "pause-20260821-01",
  "idempotency_key": "pause-20260821-01",
  "nonce": "50b35c46-c28b-47e1-a46e-e8ff54c3ec6e",
  "issued_at_epoch_ms": 1787342400000,
  "action": "pause",
  "payload": {
    "ticks": 1200,
    "actor_uuid": "591e02e4-5297-4b20-9105-690748338427"
  },
  "rationale": "operator maintenance window"
}
```

`place_building` is typed so callers receive a deterministic result, but it is
always rejected:

```json
{
  "type": "controller.action.v1",
  "request_id": "place-20260821-01",
  "idempotency_key": "place-20260821-01",
  "nonce": "e79ec9cd-38ba-4b1f-81dd-7737ca695283",
  "issued_at_epoch_ms": 1787342400000,
  "action": "place_building",
  "payload": {
    "colony_id": 7,
    "dimension": "minecraft:overworld",
    "building_type": "minecolonies:builder",
    "position": {"x": 140, "y": 64, "z": 220},
    "actor_uuid": "591e02e4-5297-4b20-9105-690748338427"
  },
  "rationale": "example only; placement is disabled"
}
```

The bridge atomically writes terminal results to
`responses/<idempotency_key>.json`:

```json
{
  "type": "bridge.result.v1",
  "request_id": "grant-20260821-01",
  "status": "completed",
  "payload": {
    "colony_id": 7,
    "building": {"x": 120, "y": 64, "z": 210},
    "minecolonies_request_id": "1d6514f4-17b9-4f06-ad27-d3c199e9bf55",
    "item": "minecraft:oak_log",
    "granted": 8
  }
}
```

Valid statuses are `completed`, `rejected`, and `failed`. Rejected and failed
payloads contain exact `code` and `message` fields. Structurally invalid input
is quarantined under `rejected/`.

Request IDs are idempotent and duplicate nonces are rejected. Timestamp age is
bounded by server configuration. Spool
claims are hidden files inside `requests/`; startup recovers an interrupted
claim without introducing another public spool directory. Hidden completion
tombstones in `responses/` preserve idempotency after a controller consumes
the public response and across server restarts.

## Mutation boundary

Mutations are limited to validated MineColonies operations: assign an
existing builder work order, call `IBuilding.requestUpgrade` on an existing
building, or fulfill a specific existing deliverable request with a matching
item stack. Grant fulfillment first verifies full building-storage capacity,
inserts the physical stack, and then overrules the request so the requester can
pick up the delivered item. Validation checks server-thread affinity, active
mode, pause and budget gates, loaded chunks, colony ownership, and the current
MineColonies object state. The colony owner UUID remains authorized while offline; upgrade
requests use a Forge fake player carrying that owner identity when MineColonies
requires a `Player`. Colony-scoped mutations require an exact owner UUID match;
operator status does not bypass that check. Global pause and resume actions
remain restricted to online operators with the configured permission level.

`request_upgrade` also starts the initial build for an observed, unbuilt,
non-pending level-0 `minecolonies:townhall` or `minecolonies:builder`.
All other new-building placement is disabled.

`/colonyauto offline on` enables bounded offline progression. After one minute
of sustained healthy TPS and tick time, the bridge keeps ownerless sessions
active in MineColonies without creating a networked bot. It owns a capped set
of entity-ticking tickets around citizens and their worksites, independent of
FTB chunk claims. It releases those tickets and activity leases immediately
when Autopilot is disabled, paused, switched to observer mode, attacked, an
owner logs in, or TPS/tick time leaves the configured health thresholds.
`/colonyauto offline off` restores MineColonies' normal online-player behavior.

`/colonyauto offline recovery on` also enables bounded builder recovery. If an
offline builder remains in `LOAD_STRUCTURE` without completing an action for
`offlineStallMinutes`, the bridge clears the stale blueprint future and retries
that work order through the builder's existing AI. Progress resets the timer,
and recovery remains disabled during attacks or unhealthy server conditions.

`/colonyauto builder-speed 1..10` adjusts only Builder worker AI. The configured
multiplier is applied while Autopilot is active and TPS/tick-time gates pass;
builders immediately return to normal speed while paused, under attack, or
when server health drops below those thresholds.

`/colonyauto deadline` reports the persisted healthy-runtime clock for every
claimed construction work order. The default contract is 60 healthy minutes.
Normal MineColonies construction runs first; the bridge begins visible
multi-block steps during the final 15 minutes. At the deadline it reconciles
the remaining blueprint positions in bounded batches, verifies each position,
and only then invokes MineColonies' normal completion path. Restarts, attacks,
pauses, and unhealthy TPS do not consume deadline time.

Operators can use `/colonyauto deadline on|off`,
`/colonyauto deadline minutes 5..360`, and
`/colonyauto deadline pause|resume|reset <colony> <work-order>`. Direct
reconciliation intentionally grants the remaining schematic blocks because
the deployed grant policy already supplies every explicit colony request.

Snapshot JSON uses snake_case. Colonies expose `owner_uuid` and include
`owner_evidence` while the owner is online. Evidence contains `stats`,
`advancements`, aggregated `inventory`, and `dimensions`; it is omitted while
the owner is offline so the controller can use previously persisted evidence.
Every loaded building exposes `building_type`, `is_builder`, `builder_idle`,
and `compatible_work_order_ids` in addition to its state and open requests. A
live level-0 unbuilt Builder Hut is idle when it has exactly one assigned
citizen and no work order, allowing its initial build to bootstrap.
