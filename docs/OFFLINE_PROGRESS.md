# Offline colony progress

## Symptom

The colony makes no progress while the owner is offline. Work resumes when a
player logs in, so achievements appear to "wait" for a login.

## Confirmed and fixed

### Chunk plan returned nothing once the budget was exceeded

`OfflineChunkPlan.around` returned an empty list as soon as the anchors needed
more chunks than `offlineMaxTickingChunks`, and `updateTickingChunks` reads an
empty plan as "nothing to load". With `offlineChunkRadius = 1` each anchor
contributes nine chunks, and the anchors are the colony center plus every
citizen's last position and work building, so any real colony exceeded the
32-chunk budget immediately and force-loaded nothing.

Anchors are ordered by importance with the colony center first and insertion
order is preserved, so the plan is now truncated at the budget instead.

Measured before and after on the live server: `offline_ticking_chunks` 0 -> 32,
tick time 0.71 ms -> 1.53 ms.

### Status could not distinguish leased from working

The payload reported `offline colony progression active` while force-loading
zero chunks, which is why a frozen colony looked healthy for days. Holding a
lease and making progress are now separate states.

### Spool faults found while measuring

- `FileSpoolTransport.poll` threw out of the loop when a request could not be
  read, abandoning every request behind it in filename order on every tick.
  Unreadable requests are now quarantined individually.
- Tombstones were retained forever: 37,030 files and a 3 MB directory listing.
  They now expire an hour after they can no longer suppress a replay, along
  with responses no client collected. Verified 37,030 -> 246 and 603 -> 0
  uncollected payloads.

## Still open: citizens do not tick

Chunk loading is no longer the blocker, and the snapshot now proves it. All
four citizens report:

```
liveness             = live
entity_present       = true
entity_in_level      = true
chunk_loaded         = true
chunk_entity_ticking = true
```

and yet `ai_state` stays at `INIT`, `saturation` is bit-identical across
minutes and across a server restart, and builder `actions_done` does not move.
Simulating the plan against the live anchors confirms all four citizens and
both active work order sites fall inside the forced chunks.

So the entity sits in a chunk whose ticket level reports entity ticking, is
retrievable from the level by UUID, is alive, and still never ticks.

### Leading hypothesis

An entity is only ticked if it is in `ServerLevel`'s entity tick list, which is
populated when the entity is added to a chunk that is already entity-ticking.
Entities loaded before the ticket was applied can therefore sit in the level,
in a now-ticking chunk, without ever being promoted into the tick list.

`OfflineCitizenSpawnPolicy.shouldSpawn` refuses to act when `entityPresent` is
true, so nothing ever re-adds them. Re-adding the citizen while the chunk is
entity-ticking would test this directly.

### Not yet ruled out

MineColonies gating its worker AI on a real subscriber rather than on the
`FakePlayer` added to `getImportantColonyPlayers()`.

## Unrelated faults observed

- Every `grant` the planner dispatches is rejected, either `INVALID_REQUEST`
  ("token is not registered as a request to this manager") or
  `BUDGET_EXHAUSTED` against `dailyActionBudget = 8`.
- The planner reports `idle` in 2,949 of the last 3,000 audit events.

## Reproducing the measurements

Scripts used live on the server under `/tmp` during the investigation; the
durable ones are worth rebuilding if this is picked up again:

- request a bridge `status` or `snapshot` by writing a request into the spool
  as uid 996 with the filename equal to `<idempotency_key>.json`
- sample `saturation`, `ai_state` and `actions_done` twice a few minutes apart;
  saturation changing is the cheapest proof that an entity ticks at all
