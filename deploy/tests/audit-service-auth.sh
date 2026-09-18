#!/usr/bin/env bash
# Audit each service's *internal* authentication, separately from the shared
# Caddy credential that gates the port.
#
# Passing Caddy only proves the proxy let you through. Most of these apps keep
# their own user store behind that, and an app whose internal login is still
# on defaults is not actually set up.
set -uo pipefail

source /etc/colony-agent/agent.env
U="${OPENCODE_SERVER_USERNAME}"
P="${OPENCODE_SERVER_PASSWORD}"
L="$(printf '%s' "$U" | tr '[:upper:]' '[:lower:]')"
B="http://172.17.0.1"

row() { printf '  %-18s %-12s %s\n' "$1" "$2" "$3"; }

printf '%-20s %-12s %s\n' "SERVICE" "OWN LOGIN" "STATE"
printf '%s\n' "--------------------------------------------------------------"

# --- no internal auth by design: Caddy is the only gate ------------------
# OpenCode binds loopback only and Glances answers on an API path rather than
# /, so probe each at an address and path it actually serves.
for svc in \
  "OpenCode|http://127.0.0.1:4097/" \
  "Glances|$B:61208/api/4/status" \
  "Web Terminal|$B:7681/" \
  "Stirling PDF|$B:8085/"; do
  name="${svc%%|*}"; url="${svc#*|}"
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 "$url" 2>/dev/null)"
  [[ "$code" =~ ^(200|30[0-9])$ ]] &&
    row "$name" "none" "ok - Caddy is the only gate" ||
    row "$name" "none" "UNREACHABLE ($code)"
done

# --- Pi-hole: API password ----------------------------------------------
for inst in "Pi-hole:8081" "Pi-hole Backup:8082"; do
  name="${inst%%:*}"; port="${inst##*:}"
  if curl -sS --max-time 8 -X POST -H 'Content-Type: application/json' \
      -d "{\"password\":\"$P\"}" "$B:$port/api/auth" 2>/dev/null |
      grep -q '"valid":true'; then
    row "$name" "shared" "ok - accepts shared password"
  else
    row "$name" "shared" "REJECTS shared password"
  fi
done

# --- Cockpit: PAM --------------------------------------------------------
if curl -sS -k --max-time 12 -u "$L:$P" \
    https://127.0.0.1:9090/cockpit/login 2>/dev/null | grep -q csrf-token; then
  row "Cockpit" "PAM" "ok - system account $L"
else
  row "Cockpit" "PAM" "REJECTS shared password"
fi

# --- Homarr: credentials provider ---------------------------------------
jar="$(mktemp)"
tok="$(curl -sS -c "$jar" -b "$jar" --max-time 10 \
  http://127.0.0.1:7575/api/auth/csrf 2>/dev/null |
  sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p')"
curl -sS -c "$jar" -b "$jar" --max-time 12 -o /dev/null \
  -d "name=$L&password=$P&csrfToken=$tok&json=true" \
  http://127.0.0.1:7575/api/auth/callback/credentials 2>/dev/null
if curl -sS -b "$jar" --max-time 10 \
    http://127.0.0.1:7575/api/auth/session 2>/dev/null | grep -q '"user"'; then
  row "Homarr" "own" "ok - accepts shared password"
else
  row "Homarr" "own" "REJECTS shared password"
fi
rm -f "$jar"

# --- qBittorrent ---------------------------------------------------------
resp="$(curl -sS --max-time 10 -H "Referer: $B:8083" \
  -d "username=$U&password=$P" "$B:8083/api/v2/auth/login" 2>/dev/null)"
if [[ "$resp" == "Ok." ]]; then
  row "qBittorrent" "own" "ok - accepts shared password"
elif curl -sS -o /dev/null -w '%{http_code}' --max-time 8 \
    "$B:8083/api/v2/app/version" 2>/dev/null | grep -q 200; then
  row "qBittorrent" "own" "open - no login required on this subnet"
else
  row "qBittorrent" "own" "REJECTS shared password"
fi

# --- Jackett -------------------------------------------------------------
code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 \
  "$B:9117/api/v2.0/server/config" 2>/dev/null)"
[[ "$code" == "200" ]] &&
  row "Jackett" "optional" "open - no admin password set" ||
  row "Jackett" "optional" "gated ($code)"

# --- Radarr / Sonarr: API key + optional UI auth -------------------------
for svc in "Radarr:7878:radarr" "Sonarr:8989:sonarr"; do
  name="${svc%%:*}"; rest="${svc#*:}"; port="${rest%%:*}"; cont="${rest##*:}"
  key="$(docker exec "$cont" sh -c \
    "sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' /config/config.xml" 2>/dev/null)"
  meth="$(docker exec "$cont" sh -c \
    "sed -n 's:.*<AuthenticationMethod>\(.*\)</AuthenticationMethod>.*:\1:p' /config/config.xml" 2>/dev/null)"
  if [[ -n "$key" ]]; then
    row "$name" "${meth:-None}" "ok - API key present for Homarr"
  else
    row "$name" "${meth:-None}" "NO API KEY"
  fi
done

# --- Bookstack: actually attempt the login ------------------------------
jar="$(mktemp)"
tok="$(curl -sS -c "$jar" -b "$jar" --max-time 10 "$B:6875/login" 2>/dev/null |
  sed -n 's/.*name="_token" value="\([^"]*\)".*/\1/p' | head -1)"
code="$(curl -sS -c "$jar" -b "$jar" -o /dev/null -w '%{http_code}' --max-time 12 \
  -X POST -d "email=$U@localhost&password=$P&_token=$tok" \
  "$B:6875/login" 2>/dev/null)"
rm -f "$jar"
[[ "$code" == "302" ]] &&
  row "Bookstack" "own" "ok - accepts $U@localhost" ||
  row "Bookstack" "own" "REJECTS shared password ($code)"

# --- Jellyfin: actually attempt the login -------------------------------
if curl -sS --max-time 12 -X POST -H 'Content-Type: application/json' \
    -H 'X-Emby-Authorization: MediaBrowser Client="audit", Device="cli", DeviceId="audit", Version="1"' \
    -d "{\"Username\":\"$U\",\"Pw\":\"$P\"}" \
    "$B:8096/Users/AuthenticateByName" 2>/dev/null | grep -q '"AccessToken"'; then
  row "Jellyfin" "own" "ok - accepts shared password"
else
  row "Jellyfin" "own" "REJECTS shared password"
fi

# --- Vaultwarden: the web vault needs a secure context ------------------
# WebCrypto is unavailable over plain HTTP on a LAN address, so Caddy serves
# this one over TLS from its internal CA.
code="$(curl -sS -k -o /dev/null -w '%{http_code}' --max-time 10 \
  -u "$U:$P" "https://192.168.1.171:8184/" 2>/dev/null)"
[[ "$code" == "200" ]] &&
  row "Vaultwarden" "own" "ok - served over HTTPS (register first use)" ||
  row "Vaultwarden" "own" "HTTPS not serving ($code)"

printf '\n'
printf 'Note: "own" means the app keeps its own user store behind Caddy.\n'
