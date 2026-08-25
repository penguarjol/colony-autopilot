#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

readonly repository_root="/srv/colony-agent/repositories"
readonly bare_repository="$repository_root/colony-autopilot.git"
readonly workspace="/srv/colony-agent/workspaces/colony-autopilot"
readonly source_backup_root="$DEFAULT_BACKUP_ROOT/agent-source"

bundle=""
branch=""

workspace_git() {
  runuser -u colony-agent -- git -C "$workspace" "$@"
}

bare_git() {
  runuser -u colony-agent -- git --git-dir="$bare_repository" "$@"
}

usage() {
  cat <<'EOF'
Usage: init-agent-workspace.sh --bundle PATH --branch NAME [--apply]

Creates or updates the isolated server-local bare repository and working clone.
The input bundle is verified and retained in the root-owned backup directory.
The default is a dry run.

Options:
  --bundle PATH   Git bundle containing the approved source baseline
  --branch NAME   Branch to check out in the agent workspace
  --apply         Perform the initialization
  --help          Show this help
EOF
}

while (($#)); do
  case "$1" in
    --bundle)
      (($# >= 2)) || die "--bundle requires a path"
      bundle="$2"
      shift 2
      ;;
    --branch)
      (($# >= 2)) || die "--branch requires a name"
      branch="$2"
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

[[ -n "$bundle" ]] || die "--bundle is required"
[[ "$branch" =~ ^[A-Za-z0-9._/-]+$ && "$branch" != -* ]] ||
  die "--branch is invalid"
require_apply_root

bundle="$(canonical_path "$bundle")"
[[ -f "$bundle" && ! -L "$bundle" ]] ||
  die "bundle is missing, not regular, or is a symlink: $bundle"

if [[ "$APPLY" != true ]]; then
  quote_command git bundle verify "$bundle"
  quote_command install -d -m 0750 -o colony-agent -g colony-agent \
    "$repository_root" "$(dirname "$workspace")"
  quote_command git clone --bare "$bundle" "$bare_repository"
  quote_command git clone "$bare_repository" "$workspace"
  quote_command git -C "$workspace" switch "$branch"
  quote_command cp "$bundle" "$source_backup_root/"
  exit 0
fi

require_command git
require_command sha256sum
id colony-agent >/dev/null 2>&1 ||
  die "colony-agent user does not exist; run install-agent.sh first"

inspection_root="$(mktemp -d)"
trap 'rm -rf -- "$inspection_root"' EXIT
verification_repository="$inspection_root/verify.git"
git init --bare --quiet "$verification_repository"
git -C "$verification_repository" bundle verify "$bundle"
git clone --bare "$bundle" "$inspection_root/repository.git"
git --git-dir="$inspection_root/repository.git" \
  rev-parse --verify "refs/heads/$branch" >/dev/null ||
  die "bundle does not contain branch: $branch"

tracked_paths="$(
  git --git-dir="$inspection_root/repository.git" \
    ls-tree -r --name-only "refs/heads/$branch"
)"
if grep -E '(^|/)([^/]*[.]env([.]|$)|[^/]*[.](pem|key)$)' \
  <<<"$tracked_paths" >/dev/null; then
  die "bundle contains tracked secret-bearing file types"
fi

install -d -m 0750 -o colony-agent -g colony-agent \
  "$repository_root" "$(dirname "$workspace")"
install -d -m 0750 "$source_backup_root"
backup_name="$(timestamp_utc)-$(basename "$bundle")"
install -m 0640 "$bundle" "$source_backup_root/$backup_name"
(
  cd "$source_backup_root"
  sha256sum "$backup_name" >"$backup_name.sha256"
  sha256sum -c "$backup_name.sha256"
)
chown -R colony-agent:colony-agent /srv/colony-agent

if [[ ! -e "$bare_repository" ]]; then
  git clone --bare "$bundle" "$bare_repository"
else
  [[ -d "$bare_repository" && ! -L "$bare_repository" ]] ||
    die "existing bare repository is unsafe: $bare_repository"
  bare_git fetch "$bundle" \
    "refs/heads/$branch:refs/heads/$branch"
fi

if [[ -d "$workspace" && ! -e "$workspace/.git" ]]; then
  rmdir --ignore-fail-on-non-empty "$workspace"
fi

if [[ ! -e "$workspace" ]]; then
  git clone "$bare_repository" "$workspace"
  git -C "$workspace" switch "$branch"
else
  [[ -d "$workspace/.git" && ! -L "$workspace" ]] ||
    die "existing workspace is unsafe: $workspace"
  workspace_git diff --quiet &&
    workspace_git diff --cached --quiet ||
    die "agent workspace has uncommitted changes"
  current_origin="$(workspace_git remote get-url origin)"
  [[ "$(canonical_path "$current_origin")" == "$bare_repository" ]] ||
    die "agent workspace origin is not the managed bare repository"
  workspace_git fetch origin "$branch"
  if [[ "$(workspace_git branch --show-current)" != "$branch" ]]; then
    workspace_git switch "$branch"
  fi
  workspace_git merge --ff-only "origin/$branch"
fi

chmod 0750 /srv/colony-agent "$repository_root" "$(dirname "$workspace")"
log "initialized agent workspace at $workspace from $branch"

