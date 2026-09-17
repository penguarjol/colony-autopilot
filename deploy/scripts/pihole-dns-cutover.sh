#!/usr/bin/env bash
# Move host DNS onto Pi-hole, or roll that change back.
#
# This is the one change in the command center that can take the whole box
# offline if it goes wrong: without working name resolution Docker cannot pull,
# the Minecraft server cannot reach Mojang, and apt stops working. So the
# resolver list always keeps a public upstream behind Pi-hole. Stopping the
# Pi-hole container then degrades to unfiltered DNS instead of no DNS.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

readonly RESOLVED_DROPIN=/etc/systemd/resolved.conf.d/zz-pihole.conf
readonly COMPOSE=/opt/command-center/services/phase-a.yml
readonly FALLBACK_1=1.1.1.1
readonly FALLBACK_2=8.8.8.8

rollback=false

usage() {
  cat <<'EOF'
Usage: pihole-dns-cutover.sh [--rollback] [--apply]

Points host DNS at the local Pi-hole while keeping public resolvers as
fallback, so a stopped Pi-hole degrades to unfiltered DNS rather than none.
The default is a dry run.

Options:
  --rollback  Restore stock systemd-resolved behaviour
  --apply     Perform the change
  --help      Show this help
EOF
}

while (($#)); do
  case "$1" in
    --rollback) rollback=true; shift ;;
    --apply) APPLY=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

require_apply_root

resolves() {
  getent hosts "${1:-github.com}" >/dev/null 2>&1
}

do_rollback() {
  rm -f "$RESOLVED_DROPIN"
  ln -sf /run/systemd/resolve/stub-resolv.conf /etc/resolv.conf
  systemctl restart systemd-resolved
  PIHOLE_DNS_PORT=5353 docker compose -f "$COMPOSE" up -d pihole >/dev/null 2>&1 || true
  sleep 3
  resolves || die "DNS still broken after rollback; investigate manually"
  log "rolled back to stock systemd-resolved"
}

if [[ "$APPLY" != true ]]; then
  if [[ "$rollback" == true ]]; then
    quote_command rm -f "$RESOLVED_DROPIN"
    quote_command systemctl restart systemd-resolved
  else
    quote_command install -D -m 0644 /dev/stdin "$RESOLVED_DROPIN"
    quote_command docker compose -f "$COMPOSE" up -d pihole
    quote_command systemctl restart systemd-resolved
  fi
  exit 0
fi

if [[ "$rollback" == true ]]; then
  do_rollback
  exit 0
fi

resolves || die "host DNS is already broken; fix that before the cutover"
[[ -f "$COMPOSE" ]] || die "missing $COMPOSE; run the Phase A install first"

# Pi-hole must own port 53 to serve the host, which means resolved has to give
# up its stub listener first.
install -d -m 0755 /etc/systemd/resolved.conf.d
cat >"$RESOLVED_DROPIN" <<EOF
[Resolve]
DNSStubListener=no
EOF

systemctl restart systemd-resolved
sleep 2

# Republish Pi-hole on the privileged port now that 53 is free.
export CC_SHARED_PASSWORD="${CC_SHARED_PASSWORD:-}"
if [[ -z "$CC_SHARED_PASSWORD" && -f /etc/colony-agent/agent.env ]]; then
  # shellcheck disable=SC1091
  source /etc/colony-agent/agent.env
  export CC_SHARED_PASSWORD="$OPENCODE_SERVER_PASSWORD"
fi

PIHOLE_DNS_PORT=53 docker compose -f "$COMPOSE" up -d pihole
sleep 6

# The safety guard lives here rather than in resolved's FallbackDNS, because
# glibc walks this list in order and moves to the next entry when Pi-hole
# refuses the connection. resolved's fallback would not help once its stub
# listener is gone.
rm -f /etc/resolv.conf
cat >/etc/resolv.conf <<EOF
# Managed by pihole-dns-cutover.sh
# Pi-hole first; public resolvers keep the host online if it is stopped.
nameserver 127.0.0.1
nameserver $FALLBACK_1
nameserver $FALLBACK_2
options timeout:1 attempts:1 rotate
EOF
chmod 0644 /etc/resolv.conf

if ! resolves; then
  log "DNS failed after cutover; rolling back automatically"
  do_rollback
  exit 1
fi

# The whole point of the fallback: prove a stopped Pi-hole cannot black-hole
# the host before declaring this done.
docker stop pihole >/dev/null 2>&1
sleep 3
if resolves; then
  log "verified: DNS survives Pi-hole being stopped"
  docker start pihole >/dev/null 2>&1
else
  log "DNS dies without Pi-hole; that is unsafe, rolling back"
  docker start pihole >/dev/null 2>&1
  do_rollback
  exit 1
fi

sleep 4
resolves || die "DNS did not recover after restarting Pi-hole"
log "host DNS now flows through Pi-hole with $FALLBACK_1/$FALLBACK_2 as fallback"
