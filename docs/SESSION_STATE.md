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

## In progress: one login for Homarr too

**Update.** The command center now answers on `apengu-home.duckdns.org`
(DuckDNS, wildcards included) and the OIDC issuer holds a real Let's Encrypt
certificate at `https://auth.apengu-home.duckdns.org:3443`. Caddy was
rebuilt with the DuckDNS DNS module; the stock binary carries no DNS
providers and the TXT challenge is the only one usable for a host that is
not publicly reachable. Nothing is exposed to the internet: the A record
points a public name at a LAN address, and validation never contacts the
server.

Every certificate workaround is gone. Homarr fetches discovery with no CA
plumbing, verified, and no root certificate needs installing on any device.
The OIDC handshake itself runs correctly: Homarr redirects to /authorize
with PKCE, the right client and the right redirect URI, and the issuer
accepts it.

**What is left is one change.** Two tinyauth instances are running: v3 gates
the perimeter on 3011, v5 issues tokens on 3012. Separate instances mean
separate sessions, so signing in at the gate does not satisfy the provider
and it asks again. Point Caddy at v5 for both and retire v3:

  - in the Caddyfile, `forward_auth 127.0.0.1:3011` becomes `3012`
  - the `:3010` site's `reverse_proxy 127.0.0.1:3011` becomes `3012`
  - `/api/auth/caddy` exists on v5, confirmed, so the path does not change

**Verify v5 accepts the shared password before doing this.** It becomes the
only gate, so if it rejects the credential every service locks out at once.
v5 writes its users file from the same hash and its login page renders, but
a successful sign-in has not been observed. Keep the previous Caddyfile as
`.bak` and restart rather than reload on rollback: reload cannot revive a
unit that failed to start.

The DNS API token is on the server only, at `/etc/colony-agent/duckdns.token`
and `/etc/command-center/duckdns.env`. It is not in this repository.



Forward auth guards the perimeter but issues no token, so Homarr shows its
own login behind the gate and the credential is asked for twice. tinyauth v5
is an OpenID provider, which Homarr accepts.

**Working already:** v5 runs on 3012, Caddy serves its issuer over TLS at
`https://auth.192.168.1.171.sslip.io:3443` using the internal CA, and it
serves discovery and JWKS with the `homarr` client registered. v3 still
handles forward auth for everything, untouched.

**Next, in order:**

1. Apply the Homarr override and confirm it can fetch discovery:

```
./deploy/scripts/ccssh --put deploy/scripts/configure-homarr-oidc.sh \
  serveradmin@192.168.1.171:/tmp/
./deploy/scripts/ccssh 'bash /tmp/configure-homarr-oidc.sh'
```

2. Trust Caddy's CA on each device, or the browser refuses the issuer.
   Export it with:

```
./deploy/scripts/ccssh 'cat /var/lib/caddy/caddy/pki/authorities/local/root.crt' \
  > caddy-root.crt
```

   macOS: open it in Keychain Access, System keychain, set to Always Trust.
   iOS: mail or AirDrop it, install the profile, then enable it under
   Settings, General, About, Certificate Trust Settings.

3. Verify in a browser, not with curl: `node deploy/tests/render-board.mjs`.
   It logs in and reports whether the panels painted. A status code cannot
   see a refused frame or a blank single-page app.

4. Only once step 3 passes, set `AUTH_OIDC_AUTO_LOGIN=true` so Homarr goes
   straight to the provider instead of showing a chooser, then move Caddy's
   forward auth from 3011 to 3012 and retire v3.

**Do not skip the credential check at cutover.** It was removed from the
staging step because v5 changed its login payload and v5 gates nothing yet.
Once it becomes the gate, a provider that rejects the shared password locks
out every service at once. The endpoint is `/api/auth/login`; the v3 body
shape returns 400, so the payload needs to be read off the login page first.

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
