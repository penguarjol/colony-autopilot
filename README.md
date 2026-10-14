# Colony Autopilot

An automated colony management system for [MineColonies](https://github.com/ldtteam/minecolonies) on Minecraft Forge 1.20.1. It observes, plans, and optionally acts on a colony while the owner is offline, handling builder assignment, construction deadlines, resource grants, and offline chunk loading.

## Components

```
bridge/       Forge mod (Java 17) — in-game integration with MineColonies
controller/   Python 3.11+ service — planning, IPC, and action dispatch
deploy/       Systemd units, install scripts, config, and tests
```

**Bridge** is a server-only, client-optional Forge mod. It exposes colony state through a file spool IPC, accelerates builders, manages offline chunk loading with FakePlayer leases, and enforces construction deadlines. It starts disabled in observer mode and never opens a network port.

**Controller** is a standard-library Python service that polls the bridge every 30 seconds, plans the next action (assign builder, grant resources, upgrade building), and dispatches it through the spool. It persists actions and progression in SQLite. Observer mode is the default and never mutates the colony.

**Deploy** contains everything needed to install the system on a Linux host running a Pterodactyl-managed Minecraft server: systemd units, an Ollama-backed local coding agent, a hard-error triage subsystem, a command center dashboard, and test suites.

## Requirements

- Minecraft Forge 1.20.1 (`47.4.x`)
- MineColonies 1.20.1 (tested with `1.1.1278-snapshot`)
- JDK 17 and Gradle 8.x (bridge build)
- Python 3.11+ (controller, standard library only)
- Linux host with systemd (deployment)
- Pterodactyl Wings (container management, optional but assumed by deploy scripts)

## Quick start

### 1. Build the bridge mod

```bash
cd bridge
gradle clean test build
ls build/libs/colonyautobridge-0.1.0.jar
```

Drop the jar into your server's `mods/` directory. The mod is client-optional and loads on both sides without a network channel.

### 2. Configure the bridge

On first load, the mod creates `serverconfig/colonyautobridge-server.toml`. Key settings:

| Setting | Default | Description |
|---|---|---|
| `enabled` | `false` | Master switch |
| `mode` | `OBSERVER` | `ACTIVE` enables mutations |
| `dailyActionBudget` | `0` | Mutating actions per Minecraft day |
| `offlineProgressEnabled` | `false` | Keep the colony active while the owner is offline |
| `builderSpeedMultiplier` | `1` | Builder AI speed multiplier |
| `offlineChunkRadius` | `1` | Chunks around each anchor to force-load |
| `offlineMaxTickingChunks` | `32` | Hard cap on forced ticking chunks |
| `constructionDeadlineEnabled` | `true` | Accelerate and reconcile construction |

Start with everything disabled. Enable after the IPC spool initializes and the controller is connected.

### 3. Install the controller

```bash
sudo deploy/scripts/install.sh          # dry run
sudo deploy/scripts/install.sh --apply  # install
```

The installer copies the Python controller to `/opt/colony-autopilot`, installs the systemd unit, and generates a runtime-user drop-in matching the spool's file ownership. It does not start the service unless `--start` is passed. Configuration is not overwritten on reinstall unless `--replace-config` is used.

### 4. Review and start

```bash
# validate the config
/opt/colony-autopilot/bin/colony-autopilot --config /etc/colony-autopilot/controller.toml validate-config

# check the spool is accessible
autopilotctl status

# start in observer mode (safe, read-only)
sudo autopilotctl start --apply
autopilotctl logs
```

Keep `controller.mode = "observer"` and `planner.enabled = false` until you have reviewed the bridge config and run the acceptance benchmark.

## Adapting to another modpack

The system is not tied to one server installation. To set up on a different modpack:

1. **Build the bridge jar** and add it to your `mods/` folder. The only mod dependency is MineColonies on Forge 1.20.1.

2. **Update `deploy/config/controller.toml`** with your server's paths:
   ```toml
   [bridge]
   spool_path = "/path/to/your/minecraft/server/.colony-autopilot"
   ```

3. **If using Pterodactyl**, update the volume UUID in `controller.toml` and `deploy/config/triage-watch.conf` to match your container.

4. **If using ServerCore** (or any entity tick optimizer), exclude MineColonies entities from activation range checks:
   ```yaml
   # config/servercore/config.yml
   activation-range:
     excluded-entity-types:
       - 'minecolonies:citizen'
       - 'minecolonies:visitor'
       - 'minecolonies:mercenary'
   ```
   Without this, citizens appear loaded but never tick while no real player is nearby.

5. **Run the install and test scripts** — they validate configuration coherence (context limits match between OpenCode and Ollama, spool ownership is consistent, systemd units parse cleanly).

## Offline progress

The bridge can keep a colony active while the owner is offline by injecting a FakePlayer into MineColonies' important-players set and force-loading chunks around the colony.

Key constraints discovered during development:

- MineColonies requires **more than 40 colony-claimed loaded chunks** to reach `ACTIVE` state when the only subscriber is an important player (not a close subscriber). Set `offlineMaxTickingChunks` above 40.
- ServerCore and similar tick optimizers suppress entity AI near non-real players. Citizens appear alive in the level but never tick. Exclude `minecolonies:citizen` from activation range.
- `forceLoadTimer` in MineColonies does not persist across restarts and only starts from a close subscriber with colony-manager rank. The bridge's own chunk loading is independent of this timer.

## Testing

```bash
# bridge unit tests
cd bridge && gradle test

# controller unit tests
cd controller && python3 -m pytest tests/ -v

# deployment smoke tests (runs on macOS and Linux)
bash deploy/tests/smoke.sh

# agent and triage subsystem tests
bash deploy/tests/agent-smoke.sh
bash deploy/tests/triage-smoke.sh
```

## Project structure

```
bridge/
  src/main/java/com/colonyautopilot/bridge/
    ColonyAutopilotBridge.java    Mod entry, tick handler, ForgeChunkManager callback
    config/                       Forge server config spec
    colony/                       Snapshot service, liveness diagnostics
    runtime/                      Offline activator, deadline controller, builder speed
    action/                       Action handlers (assign, upgrade, grant, pause)
    ipc/                          File spool and socket transport, JSON codec
    mixin/                        BuildingStructureHandler accelerator
  src/test/java/                  JUnit 5 tests

controller/
    cli.py                        Entry point and main loop
    service.py                    Controller orchestration
    colony_planner.py             Action planning (assign, bootstrap, grant, upgrade)
    ipc.py                        Bridge spool client
    governor.py                   TPS/tick/RAM/load gates
    progression.py                Owner tier and grant policy
    storage.py                    SQLite persistence
    tests/                        Unit tests

deploy/
    config/                       controller.toml, opencode.json, triage-watch.conf
    systemd/                      Service units, timers, socket proxy
    scripts/                      Install, uninstall, backup, benchmark, bridge install
    tests/                        Smoke, agent, triage, command center tests
    command-center/               Caddy, Homarr, Uptime Kuma, SSO stack

docs/
    OFFLINE_PROGRESS.md           Investigation notes on offline colony behavior
    COMMAND_CENTER.md             Dashboard architecture and SSO setup
```

## License

All rights reserved. This code is published for reference and personal use.
