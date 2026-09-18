#!/usr/bin/env bash
# Bring each application's own login in line with the shared credential.
#
# Caddy gates the port, but most of these apps keep a separate user store
# behind it. Left alone they sit on vendor defaults or an unfinished setup
# wizard, so "logged into the dashboard" does not mean "logged into the app".
#
# Where an app supports trusting the proxy instead of holding its own
# password, that is preferred: one fewer credential to drift.
set -uo pipefail

source /etc/colony-agent/agent.env
U="${OPENCODE_SERVER_USERNAME}"
P="${OPENCODE_SERVER_PASSWORD}"
B="http://172.17.0.1"

ok()   { printf '  ok    %s\n' "$*"; }
warn() { printf '  warn  %s\n' "$*"; }

# --- qBittorrent ---------------------------------------------------------
# Caddy has already authenticated anyone who reaches it, so rather than
# maintaining a second password, tell qBittorrent to trust the proxy's
# subnets. Its config uses backslash-separated keys that sed mangles, so
# rewrite the file in Python.
QBIT_PATCH=/tmp/qbit-patch.py

write_qbit_patch() {
  cat >"$QBIT_PATCH" <<'PY'
import pathlib

CONF = pathlib.Path("/config/qBittorrent/qBittorrent.conf")
SETTINGS = {
    "WebUI\\LocalHostAuth": "false",
    "WebUI\\AuthSubnetWhitelistEnabled": "true",
    "WebUI\\AuthSubnetWhitelist": "172.16.0.0/12, 127.0.0.0/8",
    "WebUI\\CSRFProtection": "false",
    "WebUI\\HostHeaderValidation": "false",
}

text = CONF.read_text() if CONF.exists() else "[Preferences]\n"
if "[Preferences]" not in text:
    text += "\n[Preferences]\n"

kept = [
    line for line in text.splitlines()
    if not any(line.startswith(key + "=") for key in SETTINGS)
]
out = []
for line in kept:
    out.append(line)
    if line.strip() == "[Preferences]":
        out.extend(f"{key}={value}" for key, value in SETTINGS.items())
CONF.write_text("\n".join(out) + "\n")
print("written")
PY
}

fix_qbittorrent() {
  write_qbit_patch
  # qBittorrent rewrites its config from memory when it exits, so editing a
  # running container and then restarting silently discards the change. Edit
  # the file on the host volume while the process is stopped.
  local vol
  vol="$(docker volume inspect cc-phase-a_qbittorrent-config \
    --format '{{.Mountpoint}}' 2>/dev/null)"
  if [[ -z "$vol" ]]; then
    warn "qBittorrent: config volume not found"
    return 1
  fi
  docker stop qbittorrent >/dev/null 2>&1
  sleep 3
  if ! CONF_DIR="$vol" python3 - "$vol/qBittorrent/qBittorrent.conf" <<'PY'
import pathlib, sys
CONF = pathlib.Path(sys.argv[1])
SETTINGS = {
    "WebUI\\LocalHostAuth": "false",
    "WebUI\\AuthSubnetWhitelistEnabled": "true",
    "WebUI\\AuthSubnetWhitelist": "172.16.0.0/12, 127.0.0.0/8",
    "WebUI\\CSRFProtection": "false",
    "WebUI\\HostHeaderValidation": "false",
}
CONF.parent.mkdir(parents=True, exist_ok=True)
text = CONF.read_text() if CONF.exists() else "[Preferences]\n"
if "[Preferences]" not in text:
    text += "\n[Preferences]\n"
kept = [l for l in text.splitlines()
        if not any(l.startswith(k + "=") for k in SETTINGS)]
out = []
for line in kept:
    out.append(line)
    if line.strip() == "[Preferences]":
        out.extend(f"{k}={v}" for k, v in SETTINGS.items())
CONF.write_text("\n".join(out) + "\n")
PY
  then
    warn "qBittorrent: could not rewrite config"
    docker start qbittorrent >/dev/null 2>&1
    return 1
  fi
  docker start qbittorrent >/dev/null 2>&1
  sleep 12
  if curl -sS -o /dev/null -w '%{http_code}' --max-time 8 \
      "$B:8083/api/v2/app/version" 2>/dev/null | grep -q 200; then
    ok "qBittorrent trusts the proxy; no second login"
  else
    warn "qBittorrent still requires its own login"
  fi
}

# --- Jellyfin ------------------------------------------------------------
# Ships with an unfinished setup wizard, so it has no admin account at all
# until this runs.
fix_jellyfin() {
  local info
  info="$(curl -sS --max-time 10 "$B:8096/System/Info/Public" 2>/dev/null)"
  if grep -q '"StartupWizardCompleted":true' <<<"$info"; then
    ok "Jellyfin wizard already complete"
    return 0
  fi
  curl -sS --max-time 15 -X POST -H 'Content-Type: application/json' \
    -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}' \
    "$B:8096/Startup/Configuration" >/dev/null 2>&1
  curl -sS --max-time 15 "$B:8096/Startup/User" >/dev/null 2>&1
  curl -sS --max-time 15 -X POST -H 'Content-Type: application/json' \
    -d "{\"Name\":\"$U\",\"Password\":\"$P\"}" \
    "$B:8096/Startup/User" >/dev/null 2>&1
  curl -sS --max-time 15 -X POST -H 'Content-Type: application/json' \
    -d '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}' \
    "$B:8096/Startup/RemoteAccess" >/dev/null 2>&1
  curl -sS --max-time 15 -X POST -H 'Content-Type: application/json' \
    -d '{}' "$B:8096/Startup/Complete" >/dev/null 2>&1
  sleep 3
  if curl -sS --max-time 10 "$B:8096/System/Info/Public" 2>/dev/null |
      grep -q '"StartupWizardCompleted":true'; then
    ok "Jellyfin wizard completed with admin $U"
  else
    warn "Jellyfin wizard still incomplete; finish it in the browser"
  fi
}

# --- Bookstack -----------------------------------------------------------
# Ships with admin@admin.com / password.
fix_bookstack() {
  if docker exec bookstack php /app/www/artisan bookstack:create-admin \
      --email="$U@localhost" --name="$U" --password="$P" >/dev/null 2>&1; then
    ok "Bookstack admin created: $U@localhost"
  else
    # Already exists: reset the password on the default account instead.
    if docker exec bookstack php /app/www/artisan bookstack:reset-password \
        1 "$P" >/dev/null 2>&1; then
      ok "Bookstack admin password reset to the shared credential"
    else
      warn "Bookstack: set the admin password manually (default admin@admin.com / password)"
    fi
  fi
}

# --- Jackett -------------------------------------------------------------
# Caddy already gates it; a second password adds drift without adding safety.
fix_jackett() {
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 \
    "$B:9117/UI/Dashboard" 2>/dev/null)"
  if [[ "$code" =~ ^(200|30[0-9])$ ]]; then
    ok "Jackett reachable behind Caddy (no separate password set)"
  else
    warn "Jackett returned $code"
  fi
}

printf 'Aligning application logins with the shared credential\n\n'
fix_qbittorrent
fix_jellyfin
fix_bookstack
fix_jackett
printf '\n'
