# Session State: 2026-09-18

**Objective:** Fix three reported faults — broken tiles on mobile, lost desktop
board customizations, and repeated sign-in prompts across services.

**Status:** BLOCKED — cannot execute remote deploys

## Blocking issue

**What:** Every mutating command against `192.168.1.171` is refused by the
local tool-approval layer. The approval card fails to render with
`Could not find bubble for toolCallId`, so there is no way to grant it.

**Not a server problem.** Read-only SSH works; the host answers with 22
containers running and 4712 MB available.

**Attempted:** four deploy invocations, three explicit approval retries.

## Root causes found

### 1. Lost desktop customizations (caused by this project's own tooling)

`deploy/scripts/place-board-items.py` called `board.deleteBoard` and recreated
the board from scratch on every run. Each rebuild during the previous session
discarded whatever arrangement had been made in the UI. The previous layouts
are not recoverable — Homarr has no board history.

Fixed in `4ed7ddb`: placement is additive and exits early when the board
exists. A rebuild now requires an explicit `--reset`.

### 2. Repeated sign-in prompts

HTTP basic auth realms are scoped to an origin, and an origin includes the
port. With each service published on its own Caddy port, the browser treats
every one as a distinct realm and challenges separately. No amount of Caddy
configuration makes basic auth span ports; it is a property of the scheme.

Fixed in `4ed7ddb` by routing `cc_auth` through tinyauth via `forward_auth`.
Its session cookie is scoped to the host and ignores the port, so one login
covers every service. Port 3010 serves the login UI and must stay outside
`cc_auth` or the redirect loops.

Two startup constraints, both found by reading the container's own failure
rather than the docs:

- `LOG_LEVEL` is parsed as `int8`. The string `warn` aborts startup.
- `SECRET` must be exactly 32 characters. `openssl rand -hex 32` yields 64.

### 3. Broken tiles on mobile

Not yet diagnosed. Deferred behind the two above and never reached.

## Current state

- Branch: `main`, last commit `4ed7ddb`, working tree clean.
- Local verification passes: `deploy/tests/command-center-smoke.sh`.
- Server is running the previous configuration. tinyauth exists there in a
  crash loop from the first attempt, with the bad `LOG_LEVEL`. It is
  unreferenced by the live Caddyfile, so it affects nothing.

## To resume

Run from the repo root. This pushes the fixed script and compose file, brings
up the provider, and reloads Caddy onto forward_auth:

```
./deploy/scripts/ccssh --put deploy/scripts/cc-deploy \
  deploy/command-center/services/sso.yml serveradmin@192.168.1.171:/tmp/
./deploy/scripts/ccssh 'install -m 0755 /tmp/cc-deploy /usr/local/sbin/cc-deploy
  cp /tmp/sso.yml /opt/command-center/services/
  cc-deploy sso'
./deploy/scripts/ccssh --put deploy/command-center/Caddyfile \
  serveradmin@192.168.1.171:/tmp/
./deploy/scripts/ccssh 'cp /tmp/Caddyfile /opt/command-center/Caddyfile
  caddy validate --config /opt/command-center/Caddyfile --adapter caddyfile
  systemctl reload command-center-caddy'
```

Then verify, in order:

1. `docker logs tinyauth` is clean — no `Failed to parse config`.
2. `http://192.168.1.171:3010/` serves the login page.
3. A fresh private window at `http://192.168.1.171:3000` redirects to that
   login, accepts the shared credential, and returns to the dashboard.
4. Opening a service on another port, say `:9091`, does **not** prompt again.
   This is the actual fix; if it still prompts, the cookie is being scoped
   with the port and `APP_URL` in `sso.yml` is the thing to look at.

## Risk to weigh before applying

The Caddyfile change swaps the auth mechanism for every proxied service at
once. If tinyauth is unhealthy when Caddy reloads, `forward_auth` fails closed
and the whole dashboard locks out. Confirm step 1 before step 3. Rollback is
`git checkout HEAD~1 -- deploy/command-center/Caddyfile` and reload.

## Still open

Broken mobile tiles. Needs the board inspected at the mobile breakpoint to see
which specific tiles fail and whether it is geometry overflow from
`fit-mobile-layout.py` or the tiles erroring individually.
