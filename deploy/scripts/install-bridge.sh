#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

readonly volumes_root="/var/lib/pterodactyl/volumes"
readonly live_volume_id="fea5b15c-2aa0-473f-b361-3885b286cd7e"
readonly live_confirmation="INSTALL_BRIDGE_ON_LIVE_VOLUME"
readonly target_name="colonyautobridge.jar"

operation="install"
volume_path=""
source_jar=""
expected_sha256=""
rollback_directory=""
live_token=""

usage() {
  cat <<'EOF'
Usage:
  install-bridge.sh --volume PATH --jar PATH --sha256 HASH [--apply]
  install-bridge.sh --volume PATH --remove [--apply]
  install-bridge.sh --volume PATH --rollback BACKUP_DIRECTORY [--apply]

Installs, removes, or rolls back the bridge only on an explicit, stopped
Pterodactyl volume. All operations are dry-run unless --apply is passed.

Options:
  --volume PATH              Explicit clone volume under the Pterodactyl root
  --jar PATH                 Built Colony Autopilot bridge jar
  --sha256 HASH              Expected SHA-256 of --jar
  --remove                   Back up and remove installed bridge jars
  --rollback DIRECTORY       Restore a verified backup created by this script
  --confirm-live-volume TOKEN
                              Required token for the known live volume:
                              INSTALL_BRIDGE_ON_LIVE_VOLUME
  --apply                    Perform the requested operation
  --help                     Show this help
EOF
}

while (($#)); do
  case "$1" in
    --volume)
      (($# >= 2)) || die "--volume requires a path"
      volume_path="$2"
      shift 2
      ;;
    --jar)
      (($# >= 2)) || die "--jar requires a path"
      source_jar="$2"
      shift 2
      ;;
    --sha256)
      (($# >= 2)) || die "--sha256 requires a hash"
      expected_sha256="${2,,}"
      shift 2
      ;;
    --remove)
      [[ "$operation" == install ]] || die "choose only one operation"
      operation="remove"
      shift
      ;;
    --rollback)
      (($# >= 2)) || die "--rollback requires a backup directory"
      [[ "$operation" == install ]] || die "choose only one operation"
      operation="rollback"
      rollback_directory="$2"
      shift 2
      ;;
    --confirm-live-volume)
      (($# >= 2)) || die "--confirm-live-volume requires a token"
      live_token="$2"
      shift 2
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

[[ -n "$volume_path" ]] || die "--volume is required; there is no default"

trimmed_volume="${volume_path%/}"
known_live_volume="$volumes_root/$live_volume_id"
if [[ "$trimmed_volume" == "$known_live_volume" &&
  "$live_token" != "$live_confirmation" ]]; then
  die "known live volume requires --confirm-live-volume $live_confirmation"
fi

require_apply_root
require_command realpath
require_command sha256sum
require_command unzip

volume_path="$(canonical_path "$volume_path")"
canonical_volumes_root="$(canonical_path "$volumes_root")"
[[ "$(dirname "$volume_path")" == "$canonical_volumes_root" ]] ||
  die "--volume must be a direct child of $canonical_volumes_root"
if [[ "$volume_path" == "$known_live_volume" &&
  "$live_token" != "$live_confirmation" ]]; then
  die "known live volume requires --confirm-live-volume $live_confirmation"
fi
[[ -d "$volume_path" && ! -L "$volume_path" ]] ||
  die "volume is missing or is a symlink: $volume_path"

ensure_volume_idle "$volume_path" false

mods_directory="$volume_path/mods"
volume_id="$(basename "$volume_path")"
bridge_backup_root="$DEFAULT_BACKUP_ROOT/bridge/$volume_id"
target_jar="$mods_directory/$target_name"
target_checksum="$volume_path/.colonyautobridge.sha256"
legacy_target_checksum="$target_jar.sha256"

jar_has_bridge_metadata() {
  local jar_path="$1"
  local metadata

  metadata="$(unzip -p "$jar_path" META-INF/mods.toml 2>/dev/null)" ||
    return 1
  grep -Fq 'modId="colonyautobridge"' <<<"$metadata"
}

validate_bridge_jar() {
  local jar_path="$1"
  local required_checksum="${2:-}"
  local actual_checksum
  local entries
  local metadata
  local manifest

  [[ -f "$jar_path" && ! -L "$jar_path" ]] ||
    die "jar is missing, not regular, or is a symlink: $jar_path"
  unzip -tqq "$jar_path" ||
    die "jar archive validation failed: $jar_path"
  entries="$(unzip -Z1 "$jar_path")" ||
    die "could not list jar archive: $jar_path"
  grep -Fqx 'com/colonyautopilot/bridge/ColonyAutopilotBridge.class' \
    <<<"$entries" ||
    die "jar does not contain the bridge entrypoint: $jar_path"
  grep -Fqx 'colonyautobridge.mixins.json' <<<"$entries" ||
    die "jar does not contain the required mixin configuration: $jar_path"
  grep -Fqx \
    'com/colonyautopilot/bridge/mixin/BuildingStructureHandlerMixin.class' \
    <<<"$entries" ||
    die "jar does not contain the construction batching mixin: $jar_path"
  grep -Fqx \
    'com/colonyautopilot/bridge/mixin/StructureAiAccessor.class' \
    <<<"$entries" ||
    die "jar does not contain the completion accessor: $jar_path"

  metadata="$(unzip -p "$jar_path" META-INF/mods.toml)"
  grep -Fq 'modId="colonyautobridge"' <<<"$metadata" ||
    die "jar metadata has the wrong mod id: $jar_path"
  grep -Fq 'displayName="Colony Autopilot Bridge"' <<<"$metadata" ||
    die "jar metadata has the wrong display name: $jar_path"
  grep -Eq '^version="[^"]+"$' <<<"$metadata" ||
    die "jar metadata has no concrete version: $jar_path"

  manifest="$(unzip -p "$jar_path" META-INF/MANIFEST.MF)"
  grep -Fq 'Specification-Vendor: Colony Autopilot' <<<"$manifest" ||
    die "jar manifest has the wrong vendor: $jar_path"
  grep -Fq 'MixinConfigs: colonyautobridge.mixins.json' <<<"$manifest" ||
    die "jar manifest does not register the bridge mixins: $jar_path"

  actual_checksum="$(sha256sum "$jar_path" | awk '{print $1}')"
  if [[ -n "$required_checksum" && "$actual_checksum" != "$required_checksum" ]]; then
    die "jar checksum mismatch: expected $required_checksum, got $actual_checksum"
  fi
  printf '%s' "$actual_checksum"
}

discover_installed_jars() {
  local candidate
  local filename
  shopt -s nullglob
  for candidate in "$mods_directory"/*.jar; do
    filename="$(basename "$candidate")"
    if [[ "$filename" == colonyautobridge*.jar ]] ||
      jar_has_bridge_metadata "$candidate"; then
      printf '%s\n' "$candidate"
    fi
  done
  shopt -u nullglob
}

backup_installed_jars() {
  local backup_directory
  local installed_jar

  ((${#installed_jars[@]})) || return 0
  backup_directory="$bridge_backup_root/$(timestamp_utc)"

  if [[ "$APPLY" != true ]]; then
    quote_command install -d -m 0750 "$backup_directory"
    for installed_jar in "${installed_jars[@]}"; do
      quote_command cp -a -- "$installed_jar" "$backup_directory/"
    done
    quote_command bash -c \
      'cd "$1" && sha256sum ./*.jar >MANIFEST.sha256 && sha256sum -c MANIFEST.sha256' \
      _ "$backup_directory"
    return
  fi

  [[ ! -e "$backup_directory" ]] ||
    die "backup destination already exists: $backup_directory"
  install -d -m 0750 "$backup_directory"
  for installed_jar in "${installed_jars[@]}"; do
    cp -a -- "$installed_jar" "$backup_directory/"
  done
  (
    cd "$backup_directory"
    sha256sum ./*.jar >MANIFEST.sha256
    sha256sum -c MANIFEST.sha256
  )
  log "verified bridge backup: $backup_directory"
}

mapfile -t installed_jars < <(
  if [[ -d "$mods_directory" ]]; then
    discover_installed_jars
  fi
)
for installed_jar in "${installed_jars[@]}"; do
  [[ -f "$installed_jar" && ! -L "$installed_jar" ]] ||
    die "installed bridge jar is unsafe: $installed_jar"
done

case "$operation" in
  install)
    [[ -n "$source_jar" ]] || die "--jar is required for installation"
    [[ "$expected_sha256" =~ ^[0-9a-f]{64}$ ]] ||
      die "--sha256 must be a 64-character hexadecimal hash"
    source_jar="$(canonical_path "$source_jar")"
    [[ "$source_jar" != "$volume_path/"* ]] ||
      die "source jar must be outside the target volume"
    source_checksum="$(validate_bridge_jar "$source_jar" "$expected_sha256")"
    backup_installed_jars

    if [[ "$APPLY" != true ]]; then
      quote_command install -d -m 0755 "$mods_directory"
      quote_command install -m 0644 "$source_jar" "$mods_directory/.bridge-staging.jar"
      for installed_jar in "${installed_jars[@]}"; do
        quote_command rm -f -- "$installed_jar"
      done
      quote_command mv -f -- "$mods_directory/.bridge-staging.jar" "$target_jar"
      log "dry-run: target checksum will be verified before and after publication"
      exit 0
    fi

    install -d -m 0755 "$mods_directory"
    staging_jar="$(mktemp --tmpdir="$mods_directory" .bridge.XXXXXXXX.jar)"
    staging_checksum="$(mktemp --tmpdir="$volume_path" .bridge.XXXXXXXX.sha256)"
    trap 'rm -f -- "$staging_jar" "$staging_checksum"' EXIT
    install -m 0644 "$source_jar" "$staging_jar"
    [[ "$(sha256sum "$staging_jar" | awk '{print $1}')" == "$source_checksum" ]] ||
      die "staged jar checksum verification failed"
    rm -f -- "${installed_jars[@]}" "$target_checksum" "$legacy_target_checksum"
    mv -f -- "$staging_jar" "$target_jar"
    printf '%s  mods/%s\n' "$source_checksum" "$target_name" >"$staging_checksum"
    chmod 0644 "$staging_checksum"
    mv -f -- "$staging_checksum" "$target_checksum"
    (
      cd "$volume_path"
      sha256sum -c "$(basename "$target_checksum")"
    )
    trap - EXIT
    log "installed verified bridge: $target_jar"
    ;;
  remove)
    [[ -z "$source_jar" && -z "$expected_sha256" ]] ||
      die "--jar and --sha256 are not valid with --remove"
    backup_installed_jars
    if ((${#installed_jars[@]} == 0)); then
      log "no installed bridge jars found"
      exit 0
    fi
    if [[ "$APPLY" != true ]]; then
      quote_command rm -f -- "${installed_jars[@]}" \
        "$target_checksum" "$legacy_target_checksum"
      exit 0
    fi
    rm -f -- "${installed_jars[@]}" "$target_checksum" "$legacy_target_checksum"
    log "removed bridge jars after verified backup"
    ;;
  rollback)
    [[ -z "$source_jar" && -z "$expected_sha256" ]] ||
      die "--jar and --sha256 are not valid with --rollback"
    [[ -d "$rollback_directory" && ! -L "$rollback_directory" ]] ||
      die "rollback directory is missing or is a symlink"
    rollback_directory="$(canonical_path "$rollback_directory")"
    canonical_backup_root="$(canonical_path "$bridge_backup_root")"
    [[ "$rollback_directory" == "$canonical_backup_root/"* ]] ||
      die "rollback directory must be under $canonical_backup_root"
    [[ -f "$rollback_directory/MANIFEST.sha256" ]] ||
      die "rollback manifest is missing"
    (
      cd "$rollback_directory"
      sha256sum -c MANIFEST.sha256
    )
    shopt -s nullglob
    rollback_jars=("$rollback_directory"/*.jar)
    shopt -u nullglob
    ((${#rollback_jars[@]})) || die "rollback contains no jars"
    for rollback_jar in "${rollback_jars[@]}"; do
      validate_bridge_jar "$rollback_jar" >/dev/null
    done
    backup_installed_jars

    if [[ "$APPLY" != true ]]; then
      for rollback_jar in "${rollback_jars[@]}"; do
        quote_command install -m 0644 "$rollback_jar" \
          "$mods_directory/$(basename "$rollback_jar")"
      done
      exit 0
    fi

    install -d -m 0755 "$mods_directory"
    staging_directory="$(mktemp -d "$mods_directory/.bridge-rollback.XXXXXXXX")"
    trap 'rm -rf -- "$staging_directory"' EXIT
    for rollback_jar in "${rollback_jars[@]}"; do
      install -m 0644 "$rollback_jar" "$staging_directory/"
    done
    rm -f -- "${installed_jars[@]}" "$target_checksum" "$legacy_target_checksum"
    for rollback_jar in "$staging_directory"/*.jar; do
      mv -f -- "$rollback_jar" "$mods_directory/"
    done
    rmdir "$staging_directory"
    trap - EXIT
    log "restored verified bridge backup: $rollback_directory"
    ;;
esac
