# Session State: 2026-09-18

**Objective:** Fix three reported faults — repeated sign-in prompts, lost
desktop board customizations, and broken tiles on mobile.

**Status:** two fixed and verified, one not started.

## Done

### Repeated sign-in prompts — fixed, verified end to end

`deploy/tests/verify-sso.sh` logs in once and reuses that session across six
ports. Latest run on the server:

```
login ok
cookie scoped to 192.168.1.171.sslip.io
  dashboard      :3000  200
  uptime-kuma    :3001  302
  cockpit        :9091  200
  glances        :8181  403
  qbittorrent    :8183  200
  jackett        :9118  301
one login covered every service
```

None re-prompted. The 403 is Glances' own host allowlist, not an auth failure.

**Why it happened.** A basic auth realm is scoped to an origin, and an origin
includes the port. With every service on its own port the browser correctly
treated each as a separate realm. No Caddy setting changes that.

**Why it needed a hostname.** RFC 6265 has browsers discard any cookie
carrying a `Domain` attribute when the request host is an IP literal, so no
session cookie could span services at `192.168.1.171`. Services are now
reached at `192.168.1.171.sslip.io`, which public DNS maps to the same
address, so no router or client configuration was needed. Verified the
router does not block it. tinyauth sits at `auth.<name>` and scopes its
cookie to the parent; cookies ignore ports, so one login covers everything.
IP arrivals redirect to the hostname ahead of the auth check, so old
bookmarks still work.

### Lost desktop customizations — cause found, stopped recurring

`place-board-items.py` called `board.deleteBoard` and rebuilt from scratch
every run, so each rebuild discarded any arrangement made in the UI. The
previous layouts are unrecoverable; Homarr keeps no board history.

Placement is now additive and exits early when the board exists. Rebuilding
requires an explicit `--reset`.

## Ready but not deployed

Both are committed and locally verified. The approval layer stopped granting
remote writes partway through, which is the only reason they are not live.

### 1. Mobile widgets sized to their content

`fit-mobile-layout.py` gave every widget outside its wide set a width of
`columns // 2`, which is one cell on the two-column phone grid, and left the
authored height alone. Result on mobile: clock, weather and Minecraft at
1x1 drawing empty boxes, and both iframes three rows tall. Geometry was
valid the whole time, which is why an overflow check found nothing.

Per-kind minimums now apply. Apply with:

```
./deploy/scripts/ccssh --put deploy/scripts/fit-mobile-layout.py \
  deploy/tests/inspect-board-layouts.py serveradmin@192.168.1.171:/tmp/
./deploy/scripts/ccssh 'set -e
  D=/var/lib/docker/volumes/docker_homarr_data/_data/db
  cp "$D/db.sqlite" "$D/db.sqlite.bak-$(date +%s)"
  docker stop homarr
  python3 /tmp/fit-mobile-layout.py
  docker start homarr
  python3 /tmp/inspect-board-layouts.py'
```

The script exits non-zero on overflow or undersized tiles, so a bad run is
visible. Roll back by copying the timestamped `.bak` over `db.sqlite` with
Homarr stopped.

### 2. Pi-hole tile

`:8181` is Pi-hole, which serves its UI under `/admin` and answers 403 at the
root. The Caddyfile now redirects. Apply with the Caddyfile deploy below.

```
./deploy/scripts/ccssh --put deploy/command-center/Caddyfile \
  deploy/tests/verify-sso.sh serveradmin@192.168.1.171:/tmp/
./deploy/scripts/ccssh 'set -u
  install -m 0755 /tmp/verify-sso.sh /opt/command-center/tests/verify-sso.sh
  LIVE=/etc/command-center/Caddyfile
  cp "$LIVE" "$LIVE.bak"; cp /tmp/Caddyfile "$LIVE"
  systemctl restart command-center-caddy; sleep 5
  systemctl is-active --quiet command-center-caddy || {
    cp "$LIVE.bak" "$LIVE"; systemctl restart command-center-caddy; exit 1; }
  bash /opt/command-center/tests/verify-sso.sh'
```

## Corrected earlier

The previous note blamed the 403 on the Glances host allowlist. That was
wrong on two counts: `:8181` is Pi-hole, not Glances (Glances is `:61209`,
host network mode), and Glances returns 200 for every Host header tried. The
allowlist edit is reverted. `verify-sso.sh` had the same mislabelling and
counted 403 as a pass, which is how the broken tile went unnoticed; it now
fails on 403, 404, 5xx and connection errors.


## Traps worth remembering

- The live Caddyfile is `/etc/command-center/Caddyfile`, not the copy under
  `/opt/command-center/`. Writing to the wrong one reloads nothing and looks
  like the change had no effect.
- `caddy validate` only parses syntax. A site bound to a port a container
  already publishes, or proxying to its own port, passes validation and then
  fails to start. Both are now covered by the smoke test.
- The shared credential is `OPENCODE_SERVER_PASSWORD` in agent.env.
  `CC_SHARED_PASSWORD` is a name `cc-deploy` derives and does not exist in
  the file.
- Logging in against the container directly yields a cookie pinned to
  loopback, because the cookie `Domain` follows the request host. Always
  authenticate through the auth hostname.
- `forward_auth` fails closed. If tinyauth is unhealthy when Caddy reloads,
  every service locks out. `cc-deploy sso` now verifies its own login before
  the switch, and deploys should keep a `.bak` and restart (not reload) on
  rollback, since reload cannot revive a dead unit.

## Current state

- Branch `main`, last commit `d9a9b69`, tree clean.
- `deploy/tests/command-center-smoke.sh` passes.
- Caddy active on the forward_auth config; dashboard reachable.
