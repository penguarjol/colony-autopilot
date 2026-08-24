#!/usr/bin/env bash

readonly DEFAULT_VOLUME_ROOT="/var/lib/pterodactyl/volumes/fea5b15c-2aa0-473f-b361-3885b286cd7e"
readonly DEFAULT_BACKUP_ROOT="/var/backups/colony-autopilot"

APPLY=false

log() {
  printf '%s\n' "$*" >&2
}

die() {
  log "error: $*"
  exit 1
}

quote_command() {
  printf 'dry-run:'
  printf ' %q' "$@"
  printf '\n'
}

run() {
  if [[ "$APPLY" == true ]]; then
    "$@"
  else
    quote_command "$@"
  fi
}

require_apply_root() {
  if [[ "$APPLY" == true && "${EUID:-$(id -u)}" -ne 0 ]]; then
    die "--apply must be run as root"
  fi
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

canonical_path() {
  realpath -m -- "$1"
}

assert_not_root_path() {
  local path
  path="$(canonical_path "$1")"
  [[ "$path" != "/" && "$path" != "/var" && "$path" != "/var/lib" ]] ||
    die "refusing unsafe path: $path"
}

assert_volume_source() {
  local path
  path="$(canonical_path "$1")"
  assert_not_root_path "$path"
  [[ -d "$path" ]] || die "volume source does not exist: $path"
}

timestamp_utc() {
  date -u '+%Y%m%dT%H%M%SZ'
}

ensure_volume_idle() {
  local volume_root="$1"
  local allow_live="$2"
  local container_id
  local mount_source
  local mounts

  [[ "$allow_live" == true ]] && return
  require_command docker
  volume_root="$(canonical_path "$volume_root")"

  while IFS= read -r container_id; do
    [[ -n "$container_id" ]] || continue
    mounts="$(
      docker inspect \
        --format '{{range .Mounts}}{{println .Source}}{{end}}' \
        "$container_id"
    )" || die "could not inspect running container: $container_id"

    while IFS= read -r mount_source; do
      [[ -n "$mount_source" ]] || continue
      mount_source="$(canonical_path "$mount_source")"
      if [[ "$mount_source" == "$volume_root" ||
        "$mount_source" == "$volume_root/"* ]]; then
        die "volume is in use by container $container_id"
      fi
    done <<<"$mounts"
  done < <(docker ps -q)
}
