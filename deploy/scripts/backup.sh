#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

source_root="$DEFAULT_VOLUME_ROOT"
backup_root="$DEFAULT_BACKUP_ROOT"
allow_live=false

usage() {
  cat <<'EOF'
Usage: backup.sh [options]

Creates and verifies a compressed archive of the Pterodactyl volume.
The default is a dry run.

Options:
  --source PATH       Volume to archive
  --destination PATH  Backup directory
  --allow-live        Permit a crash-consistent backup while in use
  --apply             Create and verify the backup
  --help              Show this help
EOF
}

while (($#)); do
  case "$1" in
    --source)
      (($# >= 2)) || die "--source requires a path"
      source_root="$2"
      shift 2
      ;;
    --destination)
      (($# >= 2)) || die "--destination requires a path"
      backup_root="$2"
      shift 2
      ;;
    --allow-live)
      allow_live=true
      shift
      ;;
    --apply)
      APPLY=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

require_apply_root
assert_not_root_path "$backup_root"

archive_name="minecraft-volume-$(timestamp_utc).tar.gz"

if [[ "$APPLY" != true ]]; then
  quote_command install -d -m 0750 "$backup_root"
  quote_command tar --acls --xattrs --numeric-owner --one-file-system \
    -czf "$backup_root/$archive_name" -C "$source_root" .
  quote_command sha256sum "$backup_root/$archive_name"
  log "dry-run: the source must be idle unless --allow-live is supplied"
  exit 0
fi

assert_volume_source "$source_root"
ensure_volume_idle "$source_root" "$allow_live"
require_command tar
require_command sha256sum

install -d -m 0750 "$backup_root"
staging_dir="$(mktemp -d "$backup_root/.backup.XXXXXXXX")"
trap 'rm -rf -- "$staging_dir"' EXIT

tar --acls --xattrs --numeric-owner --one-file-system \
  -czf "$staging_dir/$archive_name" -C "$source_root" .

(
  cd "$staging_dir"
  sha256sum "$archive_name" >"$archive_name.sha256"
  sha256sum -c "$archive_name.sha256"
)

mv -- "$staging_dir/$archive_name" "$backup_root/$archive_name"
mv -- "$staging_dir/$archive_name.sha256" "$backup_root/$archive_name.sha256"

(
  cd "$backup_root"
  sha256sum -c "$archive_name.sha256"
)

log "verified backup: $backup_root/$archive_name"
