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

## Not started

**Broken tiles on mobile.** Never diagnosed. Needs the board inspected at the
mobile breakpoint to tell whether it is geometry overflow from
`fit-mobile-layout.py` or individual tiles erroring.

## One step left over

`deploy/command-center/services/glances.conf` has the new hostname added to
`webui_allowed_hosts`, committed but not copied to the server. Until then the
Glances panel 403s. To apply:

```
./deploy/scripts/ccssh --put deploy/command-center/services/glances.conf \
  serveradmin@192.168.1.171:/tmp/
./deploy/scripts/ccssh 'cp /tmp/glances.conf /opt/command-center/services/
  docker restart glances'
```

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
