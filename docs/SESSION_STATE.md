# Session State: 2026-09-18

**Objective:** Fix three reported faults — repeated sign-in prompts, lost desktop board customizations, and broken tiles on mobile.
desktop board customizations, and broken tiles on mobile.

**Status:** all three fixed and verified.

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

## Deployed and verified

Latest `verify-sso.sh` run against the server:

```
login ok
cookie scoped to 192.168.1.171.sslip.io
  dashboard      :3000  200      pihole         :8181  302
  uptime-kuma    :3001  302      glances        :61209 200
  cockpit        :9091  200      qbittorrent    :8183  200
                                 jackett        :9118  301
one login covered every service
```

Board geometry, all three layouts: 32 items placed, zero overflow, zero
zero-size, zero overlaps. Pi-hole `/admin/` returns 200 through a session.

### 1. Widget sizing

`fit-mobile-layout.py` gave every widget outside its wide set a width of
`columns // 2`, which is one cell on the two-column phone grid, and left the
authored height alone. Result on mobile: clock, weather and Minecraft at
1x1 drawing empty boxes, and both iframes three rows tall. Geometry was
valid the whole time, which is why an overflow check found nothing.

The script exits non-zero on overflow or undersized tiles, so a bad run is
visible. Roll back by copying the timestamped `.bak` over `db.sqlite` with
Homarr stopped.

### 2. Pi-hole tile

`:8181` is Pi-hole, which serves its UI under `/admin` and answers 403 at the
root. The Caddyfile now redirects. Apply with the Caddyfile deploy below.

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
