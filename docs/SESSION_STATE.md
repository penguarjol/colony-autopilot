# Session State: 2026-09-17

**Objective:** Rebuild the Homarr dashboard to match the reference screenshot, install the full service stack, and wire OpenCode to Homarr bidirectionally.

**Status:** BLOCKED — server offline

## Blocking issue

**What:** `192.168.1.171` stopped responding to all traffic during the Phase C
image pull. 100% packet loss, ICMP reports "Host is down". This is the host
itself, not a service.

**Why (most likely):** memory exhaustion during container image extraction.
The measurement immediately before showed the host was already at its limit:

```
MemAvailable=3524MB  SwapUsed=1931MB  OllamaResident=6134MB
free -m: total 15403, used 9716, free 190
```

Only 190 MB genuinely free. Docker image extraction is a memory-intensive
burst (decompression plus page cache) on top of that. The Phase C images
(Tianji, Postgres, code-server) are several GB combined.

**My misjudgement:** I gated Phase C on swap *thrashing rate* and Minecraft TPS,
both of which looked healthy at that instant (swap I/O 0–50 kB/s, TPS 20.0,
1.45 ms ticks, 0% I/O wait). Those are steady-state indicators. They do not
predict a transient allocation spike, and `free` was only 190 MB. The gate
should have keyed on absolute free memory before a known memory-heavy
operation, not on whether the system was currently thrashing.

## To resume

1. **Power-cycle the server.** It cannot be recovered remotely.
2. Verify the base stack came back:
   ```bash
   ./deploy/scripts/ccssh 'cc-deploy status'
   ```
   Expect Minecraft, Ollama, Caddy, Homarr, Uptime Kuma, and all Phase A/B
   containers running. `restart: unless-stopped` should bring them back.
3. Confirm DNS survived the reboot — this is the highest-risk item:
   ```bash
   ./deploy/scripts/ccssh 'cat /etc/resolv.conf; getent hosts github.com && echo DNS_OK'
   ```
   If DNS is broken, roll it back:
   ```bash
   ./deploy/scripts/ccssh 'bash /tmp/ccdeploy/scripts/pihole-dns-cutover.sh --rollback --apply'
   ```
4. Re-run the integration suite for a clean baseline:
   ```bash
   ./deploy/scripts/ccssh 'bash /tmp/command-center-integration.sh'
   ```

## Decision needed

Phase C (Immich, Tianji, code-server) does not fit in the remaining memory.
Three options:

- [ ] **Skip Phase C.** Everything else works; drop photo management, analytics,
      and browser VS Code.
- [ ] **Lower Ollama's cap** from 6 GiB to 3 GiB. A 4B model at 16K context needs
      roughly 3–3.5 GiB, so this is plausible but may fail at larger contexts.
      Frees ~3 GiB.
- [ ] **Add RAM.** 15 GiB is the real constraint. 32 GiB would make the whole
      stack comfortable.

Do not retry Phase C as-is; it will reproduce this outage.

## Completed and verified before the outage

- Caddy crash loop fixed (distro `caddy.service` was holding `127.0.0.1:2019`,
  causing 2898 restarts and taking the whole command center down). Admin
  endpoint moved to 2020.
- Nginx Proxy Manager restored (had silently lost its iptables port forwarding,
  most likely from an earlier `ufw` reload flushing Docker's chains).
- Cockpit SSO working: `foorebel` / shared password authenticates through PAM
  and returns a real CSRF token, direct and through Caddy.
- Phase A installed and all eight services returning 200: Glances, Pi-hole
  Main + Backup, qBittorrent, Jackett, Vaultwarden, ttyd, Stirling PDF.
- Pi-hole DNS cutover done, with the fallback guard proven by the script
  stopping Pi-hole mid-run and confirming the host still resolved.
- Phase B installed: Radarr, Sonarr, Bookstack + MariaDB. Reused the host's
  pre-existing Jellyfin rather than duplicating it.
- Integration test suite written; last run was 27/33 passing, with the six
  failures all being not-yet-built Homarr work.

## Not started

Uptime Kuma status page, Homarr API bootstrap, Homarr integrations, board
build, OpenCode MCP wiring, OpenCode iframe embed, final verification.
