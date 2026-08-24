#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

source_root="$DEFAULT_VOLUME_ROOT"
destination_root=""
backup_root="$DEFAULT_BACKUP_ROOT"
allow_live=false

usage() {
  cat <<'EOF'
Usage: clone-world.sh [options]

Backs up, copies, and checksum-verifies a stopped Pterodactyl volume.
The clone must be a new sibling directory. The default is a dry run.

Options:
  --source PATH              Source volume
  --destination PATH         New sibling volume path
  --backup-destination PATH  Backup directory
  --allow-live               Permit copying a volume while in use
  --apply                    Create the backup and clone
  --help                     Show this help
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
      destination_root="$2"
      shift 2
      ;;
    --backup-destination)
      (($# >= 2)) || die "--backup-destination requires a path"
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

source_root="$(canonical_path "$source_root")"
if [[ -z "$destination_root" ]]; then
  destination_root="${source_root}-clone-$(timestamp_utc)"
fi
destination_root="$(canonical_path "$destination_root")"

assert_not_root_path "$source_root"
assert_not_root_path "$destination_root"
[[ "$(dirname "$source_root")" == "$(dirname "$destination_root")" ]] ||
  die "source and destination must be sibling directories"
[[ "$source_root" != "$destination_root" ]] ||
  die "source and destination must differ"
[[ ! -e "$destination_root" ]] ||
  die "destination already exists: $destination_root"

require_apply_root

if [[ "$APPLY" != true ]]; then
  backup_args=(--source "$source_root" --destination "$backup_root" --apply)
  [[ "$allow_live" == true ]] && backup_args+=(--allow-live)
  quote_command "$script_dir/backup.sh" "${backup_args[@]}"
  quote_command rsync -aHAX --numeric-ids --checksum \
    "$source_root/" "$destination_root/"
  log "dry-run: the destination will be published only after checksum comparison"
  exit 0
fi

assert_volume_source "$source_root"
ensure_volume_idle "$source_root" "$allow_live"
require_command rsync

backup_args=(--source "$source_root" --destination "$backup_root" --apply)
[[ "$allow_live" == true ]] && backup_args+=(--allow-live)
"$script_dir/backup.sh" "${backup_args[@]}"

destination_parent="$(dirname "$destination_root")"
staging_root="$(mktemp -d "$destination_parent/.clone.XXXXXXXX")"
trap 'rm -rf -- "$staging_root"' EXIT

rsync -aHAX --numeric-ids --checksum "$source_root/" "$staging_root/"
differences="$(rsync -aHAXnci --numeric-ids "$source_root/" "$staging_root/")"
[[ -z "$differences" ]] || die "clone checksum verification failed: $differences"

mv -- "$staging_root" "$destination_root"
trap - EXIT
log "verified clone: $destination_root"
