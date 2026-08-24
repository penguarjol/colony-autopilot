# Colony controller

This package is a Python 3.11+ observer-first controller. It uses only the
standard library, persists actions and progression in SQLite, and allows at
most one bridge action to be in progress.

## Commands

Run commands from the repository root:

```sh
python3 -m controller --config controller/controller.toml validate-config
python3 -m controller --config controller/controller.toml status
python3 -m controller --config controller/controller.toml once
python3 -m controller --config controller/controller.toml start
```

`observer` is the default mode and never writes an action to the bridge.
`dry-run` audits the proposed action without dispatching it. Set
`controller.mode = "active"` only when the bridge consumer is ready.
Healthy observer cycles still persist owner evidence and return one
deterministic proposal for rollout review. Dispatch requires both controller
mode `active` and bridge status `enabled=true`, `mode=ACTIVE`; either side in
observer mode keeps the proposal audit-only.

## Bridge protocol

The spool transport writes status, snapshot, and mutation requests atomically
under `requests/` and consumes terminal results from `responses/`. Structurally
invalid requests are returned through `rejected/`. The socket transport uses
the same JSON envelopes with newline framing.

Action:

```json
{
  "type": "controller.action.v1",
  "request_id": "upgrade-colony-7-level-2",
  "idempotency_key": "upgrade-colony-7-level-2",
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

Result:

```json
{
  "type": "bridge.result.v1",
  "request_id": "upgrade-colony-7-level-2",
  "status": "completed",
  "payload": {"colony_id": 7}
}
```

The bridge must enforce idempotency using `idempotency_key`. Valid terminal
statuses are `completed`, `failed`, and `rejected`.

Typed builders cover status, snapshot, builder assignment, existing-building
upgrade, a grant tied to one observed open MineColonies request, and pause or
resume. New-building placement is intentionally blocked before dispatch.

## Safety behavior

Dispatch is suspended when TPS is below 19.5, tick time exceeds 35 ms,
available memory is below 4.5 GiB, or normalized host load exceeds its
configured threshold. By default, bootstrap grants are limited to common
resources that already appear in the player's evidence. Setting
`grants.allow_any_requested_item = true` permits any registered item only when
it is an option on a concrete open MineColonies request. Multi-option requests
prefer a matching vanilla item and otherwise use the lowest item ID. Empty,
malformed, non-deliverable, and arbitrary untargeted grants remain blocked.
Builder requests use the claimed work order in their idempotency key, preventing
a regenerated request UUID from repeatedly granting the same item during one
construction job.

If bridge status omits TPS or tick duration, the provider supplies closed
governor values. Observer polling continues, but active mutations remain
suspended until the bridge reports both metrics.

The optional Ollama planner can only propose a typed grant. Its response is
validated by the same deterministic policy before dispatch; malformed or
unsafe output falls back to the deterministic planner. It never sends bridge
commands directly.

The service polls every 30 seconds. Ollama is attempted at most once every
20 minutes; deterministic planning handles intervening cycles. Per-player
grant budgets reset at the UTC day boundary. The deployed unrestricted policy
uses a 512-item daily budget; the bridge's separate action budget still limits
how many mutations can occur. Multi-option tool requests select the highest
vanilla tool tier MineColonies explicitly accepts for that worker.

## Colony snapshot planning contract

The deterministic colony planner consumes the existing snake_case snapshot
fields from `ColonySnapshotService` and requires these additional fields before
it will emit any action:

```json
{
  "owner_uuid": "591e02e4-5297-4b20-9105-690748338427",
  "owner_evidence": {
    "stats": {},
    "advancements": [],
    "inventory": {"minecraft:oak_log": 4},
    "dimensions": ["minecraft:overworld"]
  },
  "loaded_buildings": [{
    "building_type": "minecolonies:builder",
    "is_builder": true,
    "builder_idle": true,
    "compatible_work_order_ids": [42]
  }]
}
```

Every loaded building must include those marker fields in addition to its
existing position, level, built, pending-construction, and open-request data.
Until the bridge adds them, planning fails closed. The bridge must also permit
`request_upgrade` to start an observed level-0, unbuilt, non-pending Builder
Hut or Town Hall; all other new-building placement remains disabled. When both
are level 0, the Builder Hut is selected first so construction capacity exists.

Owner evidence is persisted when present. Later snapshots may omit
`owner_evidence` while that owner is offline; planning then uses the persisted
tier, inventory evidence, and current daily budget. Owners never previously
observed with valid evidence remain fail-closed.

Action priority is fixed: assign one compatible work order, start one
level-0 Builder Hut or Town Hall, fulfill one concrete eligible open request,
then conservatively upgrade one existing building only when no work-order,
open-request, or pending-construction blocker exists.

## Tests

```sh
python3 -m unittest discover -s controller/tests -v
```
