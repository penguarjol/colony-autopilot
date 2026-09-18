# Command Center

One bookmark, one login: **http://192.168.1.171:3000**

Sign in with `FooRebel` / `NewLifestyle2026!`. The same credential works on
every port below, so the browser only prompts once per port and then
remembers.

---

## What each panel is for

### Clock
Local time and date. No integration, nothing to configure.

### Weather
Current conditions and forecast. Set your location in the widget's settings
(click the pencil icon in board edit mode) — it defaults to a placeholder.

### DNS Hole Summary
Live Pi-hole statistics: queries today, how many were blocked, the block
percentage, and how many domains are on your blocklists. This is the panel
that tells you ad-blocking is actually working. Backed by both Pi-hole
instances, so the numbers are combined.

### DNS Hole Controls
Enable and disable blocking without opening Pi-hole. Use this when a site
breaks and you want to test whether Pi-hole is the cause — disable for five
minutes, reload, re-enable. **This toggles blocking, not the container**, so
it can never take your DNS down.

### Health Monitoring
Live host metrics from Glances: CPU utilisation, memory used, uptime, disk
and network. This is your first stop when the server feels slow. Currently
reporting around 7% CPU.

### Docker Containers
Every container with its state, and start/stop/restart controls inline. This
is where you turn services on and off. Note that Caddy is deliberately *not*
here — it is a systemd unit, because stopping it would make the dashboard
itself unreachable.

### Minecraft Server Status
Player count and online state for the MineColonies server on port 25565.

### RSS Feed
Headlines from Phoronix by default. Edit the widget to point at any feeds you
prefer.

### OpenCode (embedded panel)
The AI coding agent, running inside the dashboard. Create and manage agent
sessions here without opening another tab. It works on
`/srv/colony-agent/workspaces/colony-autopilot` and all its tool permissions
are auto-accepted, so it will not interrupt you for approval.

### App tiles (22)
Each tile links to a service and shows a live status dot from its ping check.

---

## Service reference

| Service | URL | What it does |
|---|---|---|
| Homarr | `:3000` | This dashboard |
| Uptime Kuma | `:3001` | Uptime history and alerting for 23 monitors |
| OpenCode | `:4096` | Local AI coding agent |
| Cockpit | `:9091` | System administration: services, logs, storage, terminal |
| Glances | `:61209` | Detailed live system metrics |
| Pi-hole | `:8181/admin` | Primary DNS filter, also the host's resolver |
| Pi-hole Backup | `:8182/admin` | Secondary DNS filter |
| qBittorrent | `:8183` | Torrent downloads |
| Jackett | `:9118` | Indexer proxy feeding Radarr and Sonarr |
| Radarr | `:7879` | Movie library automation |
| Sonarr | `:8990` | TV library automation |
| Jellyfin | `:8097` | Media streaming, hardware transcoding enabled |
| Bookstack | `:6876` | Documentation wiki |
| Vaultwarden | `:8184` | Password manager, Bitwarden-compatible |
| Stirling PDF | `:8185` | PDF merge, split, convert, OCR |
| Web Terminal | `:7682` | Browser shell on the host |
| Portainer | `:9443` | Docker management |
| Pterodactyl | `:8080` | Game server panel |
| Nginx Proxy Manager | `:81` | Reverse proxy and certificates |

---

## How the pieces fit together

The *arr stack is a pipeline: **Jackett** provides indexers to **Radarr**
(movies) and **Sonarr** (TV), which send downloads to **qBittorrent**, which
writes into `/srv/media`, which **Jellyfin** serves. Each piece is useful
alone, but they are designed to chain.

**Pi-hole** is your DNS resolver, so it filters every device that uses the
server for DNS. The host keeps 1.1.1.1 and 8.8.8.8 behind it, which means
stopping Pi-hole degrades to unfiltered DNS rather than no DNS. That
behaviour is covered by an automated test.

**Uptime Kuma** watches all 23 services through the authenticated proxy, so a
green monitor proves the same path your browser takes actually works.

**Glances** feeds the health panel. It runs with host networking to see real
host metrics rather than a container's view.

---

## The agent, in both directions

The agent can read and control the dashboard through Homarr's MCP server (83
tools). Useful requests:

- "What services are down right now?"
- "Restart qBittorrent"
- "Disable Pi-hole blocking for five minutes"
- "Show me CPU and memory"
- "Add an app tile for X"

It can also read and edit code, run tests, and use git inside its workspace.

---

## Turning things off

Use the Docker Containers panel, Portainer, or ask the agent. All containers
use `restart: unless-stopped`, so a manual stop survives reboots while a crash
still self-heals.

Three dependencies are not obvious:

- **Pi-hole** is the DNS resolver. Safe to stop thanks to the fallback
  resolvers, but prefer the DNS Hole Controls toggle for temporary changes.
- **Caddy** is the front door on 3000, 3001, 4096 and the service ports.
  Stopping it makes everything unreachable. It is a systemd unit, not a
  container.
- **Bookstack** needs its MariaDB. Stopping the database breaks the wiki and
  nothing else.

---

## Verifying

```bash
sudo bash deploy/tests/command-center-integration.sh   # 34 live checks
sudo python3 deploy/tests/verify-board-panels.py       # panels return data
```

---

## Not installed

Immich, Tianji and code-server were skipped: they need roughly 2 GB more than
this host has spare, and attempting them took the server offline once. The
compose files are in `deploy/command-center/services/` for a future RAM
upgrade. Proxmox, Synology DSM, yunohost and the 3D printer panel from the
reference dashboard require different hardware or a different host OS.
