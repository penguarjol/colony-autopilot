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

## Correction: saturation never proved anything

An earlier revision of this document treated bit-identical saturation as proof
that citizens were not ticking. That inference is invalid.
`CitizenData.decreaseSaturation` is a no-op whenever `colony.isActive()` is
false, so frozen saturation is equally consistent with a perfectly healthy
ticking citizen in a colony that is not active. `decreaseIdleSaturation` also
no-ops at night and while asleep.

## The gate that actually decides whether anything is built

`Colony.updateState` picks one of three states, and only `ACTIVE` registers
work order assignment:

```java
if (!closeSubscribers.isEmpty()
    || (loadedChunks.size() > 40 && !importantColonyPlayers.isEmpty())) return ACTIVE;
if (!importantColonyPlayers.isEmpty() || forceLoadTimer > 0)  return UNLOADED;
return INACTIVE;
```

A close subscriber is a real player standing in the colony. With only the
injected FakePlayer in `importantColonyPlayers`, the colony additionally needs
more than 40 claimed loaded chunks. Below that it reaches `UNLOADED`, where
work orders are never assigned, so builders report `canGoIdle()` true, the
worker AI is never ticked, and it stays at `INIT` forever. That is a closed
loop and it matches every symptom.

Two consequences worth recording:

- MineColonies explicitly refuses fake players in `addImportantColonyPlayer`
  and `addCloseSubscriber`. The bridge bypasses that by mutating the live set
  returned from the getter. It works, but it is a deliberate rejection being
  routed around.
- `forceLoadTimer` is unserialized and only ever set from a close subscriber
  holding colony-manager rank, so after a restart with nobody online
  MineColonies force-loads nothing regardless of `forceloadcolony` or
  `loadtime`.

The snapshot now reports `close_subscriber_count`, `important_player_count`
and the derived `activation_state` so this is a measurement rather than an
argument.

## ServerCore was suppressing the entity tick

`servercore-forge-1.5.2` deactivates entities that are not near a real player.
That is exactly "the entity is in the level, its chunk is entity ticking, and
it never runs". Adding `minecolonies:citizen`, `minecolonies:visitor` and
`minecolonies:mercenary` to `activation-range.excluded-entity-types` in
`config/servercore/config.yml` fixed it: across a 21 hour window with nobody
online, saturation decayed on all four citizens (citizen 2: 37.47 -> 33.95,
citizen 4: 53.28 -> 49.61). `decreaseIdleSaturation` runs from the entity tick,
so that is direct evidence the entities now run.

Note for future measurements: saturation moves roughly 0.1 per hour, so a
sample shorter than a few hours cannot distinguish ticking from frozen, and it
must be compared at full precision rather than rounded.

## Still open: builders never leave INIT

Every precondition is now measured and satisfied:

```
colony activation_state = ACTIVE   (close_subscribers 0, important_players 1,
                                    loaded_chunks 95, threshold is 40)
hut assigned_citizen_count = 1
hut has_work_order         = true
citizen liveness           = live
```

and the builders still report `ai_state = INIT`, `job_status = IDLE`, with
`actions_done` unchanged at 16 and 3 across 21 hours.

`CitizenAI` only calls `workerAI.tick()` from its WORK branch, and that branch
has conditions beyond `canGoIdle()`. Since `canGoIdle()` is false here (the hut
holds a work order), one of the other conditions in that predicate is what
keeps selecting IDLE. Reading the full condition list in `CitizenAI` for the
installed tag and instrumenting whichever term fails is the next step.

Two observations that may or may not be related: both builder huts report
`pending_construction = true` and are themselves the targets of unclaimed
upgrade orders, and two citizens report `asleep = true` while the world clock
reads early morning, which looks like stale persisted state.

## Superseded: citizens do not tick

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
