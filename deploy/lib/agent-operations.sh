#!/usr/bin/env bash
set -euo pipefail

readonly AGENT_WORKSPACE="/srv/colony-agent/workspaces/colony-autopilot"
readonly AGENT_BARE_REPOSITORY="/srv/colony-agent/repositories/colony-autopilot.git"
readonly AGENT_STATE_ROOT="/var/lib/colony-agent-ops"
readonly AGENT_DEPLOY_GATE="$AGENT_STATE_ROOT/deploy-enabled"
readonly AGENT_AUDIT_ROOT="/var/log/colony-agent/deployments"
readonly AGENT_BACKUP_ROOT="/var/backups/colony-autopilot"
readonly LIVE_CONTAINER="fea5b15c-2aa0-473f-b361-3885b286cd7e"
readonly LIVE_VOLUME="/var/lib/pterodactyl/volumes/$LIVE_CONTAINER"
readonly OPS_ROOT="/usr/local/lib/colony-agent/ops"

operation_log() {
  printf '%s\n' "$*" >&2
}

operation_die() {
  operation_log "error: $*"
  return 1
}

operation_setup() {
  [[ "${EUID:-$(id -u)}" -eq 0 ]] ||
    operation_die "operation must run as root"
  (($# == 0)) || operation_die "operation accepts no arguments"
  umask 0027
  install -d -m 0750 -o root -g colony-agent \
    "$AGENT_STATE_ROOT" "$AGENT_AUDIT_ROOT"
  exec 9>/run/lock/colony-agent-operations.lock
  flock --exclusive 9
}

require_deploy_authority() {
  [[ -f "$AGENT_DEPLOY_GATE" && ! -L "$AGENT_DEPLOY_GATE" ]] ||
    operation_die "deployment authority is disabled"
  [[ "$(stat -c '%U:%G %a' "$AGENT_DEPLOY_GATE")" == "root:colony-agent 640" ]] ||
    operation_die "deployment authority gate is unsafe"
  grep -Fqx 'authorized=true' "$AGENT_DEPLOY_GATE" ||
    operation_die "deployment authority is disabled"
}

run_as_agent() {
  runuser -u colony-agent -- env \
    HOME=/var/lib/colony-agent \
    JAVA_HOME=/opt/colony-agent/java \
    PATH=/opt/colony-agent/java/bin:/usr/local/bin:/usr/bin:/bin \
    "$@"
}

agent_workspace_git() {
  run_as_agent git -C "$AGENT_WORKSPACE" "$@"
}

agent_bare_git() {
  run_as_agent git --git-dir="$AGENT_BARE_REPOSITORY" "$@"
}

validate_workspace() {
  [[ -d "$AGENT_WORKSPACE/.git" && ! -L "$AGENT_WORKSPACE" ]] ||
    operation_die "managed agent workspace is unavailable"
  agent_workspace_git diff --quiet ||
    operation_die "agent workspace has unstaged changes"
  agent_workspace_git diff --cached --quiet ||
    operation_die "agent workspace has staged changes"

  DEPLOY_BRANCH="$(agent_workspace_git branch --show-current)"
  [[ "$DEPLOY_BRANCH" == agent/* ]] ||
    operation_die "deployments require an agent/* feature branch"
  DEPLOY_HEAD="$(agent_workspace_git rev-parse HEAD)"
  origin_head="$(agent_workspace_git rev-parse "origin/$DEPLOY_BRANCH")"
  [[ "$DEPLOY_HEAD" == "$origin_head" ]] ||
    operation_die "feature commit must be pushed to the managed bare repository"
  export DEPLOY_BRANCH DEPLOY_HEAD
}

run_verification() {
  run_as_agent bash -c '
    set -euo pipefail
    cd "$1"
    /opt/colony-agent/gradle/gradle-8.8/bin/gradle \
      -p bridge --no-daemon --offline clean test build
    python3 -m unittest discover -s controller/tests
    bash deploy/tests/smoke.sh
    bash deploy/tests/agent-security.sh
  ' _ "$AGENT_WORKSPACE"
}

current_bridge_jar() {
  local jars
  shopt -s nullglob
  jars=("$AGENT_WORKSPACE"/bridge/build/libs/colonyautobridge-*.jar)
  shopt -u nullglob
  ((${#jars[@]} == 1)) ||
    operation_die "verification must produce exactly one bridge jar"
  printf '%s\n' "${jars[0]}"
}

require_live_services() {
  systemctl is-active --quiet colony-autopilot.service ||
    operation_die "colony-autopilot must be active before an operation"
  [[ "$(docker inspect --format '{{.State.Running}}' "$LIVE_CONTAINER")" == true ]] ||
    operation_die "Minecraft container must be active before an operation"
}

stop_live_services() {
  systemctl stop colony-autopilot.service
  docker stop "$LIVE_CONTAINER" >/dev/null
}

start_live_services() {
  docker start "$LIVE_CONTAINER" >/dev/null
}

latest_bridge_backup() {
  local candidate
  local latest=""
  shopt -s nullglob
  for candidate in "$AGENT_BACKUP_ROOT/bridge/$LIVE_CONTAINER"/*; do
    [[ -d "$candidate" ]] || continue
    if [[ -z "$latest" || "$candidate" > "$latest" ]]; then
      latest="$candidate"
    fi
  done
  shopt -u nullglob
  [[ -n "$latest" ]] || operation_die "bridge backup was not created"
  printf '%s\n' "$latest"
}

create_release_backup() {
  DEPLOY_TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
  RELEASE_BACKUP="$AGENT_BACKUP_ROOT/agent-deploy/$DEPLOY_TIMESTAMP"
  install -d -m 0750 "$RELEASE_BACKUP/controller"
  if [[ -d /opt/colony-autopilot/controller ]]; then
    cp -a /opt/colony-autopilot/controller/. "$RELEASE_BACKUP/controller/"
  fi
  if [[ -f /opt/colony-autopilot/bin/colony-autopilot ]]; then
    install -D -m 0755 /opt/colony-autopilot/bin/colony-autopilot \
      "$RELEASE_BACKUP/bin/colony-autopilot"
  fi
  agent_bare_git bundle create "$RELEASE_BACKUP/source.bundle" --all
  (
    cd "$RELEASE_BACKUP"
    sha256sum source.bundle >MANIFEST.sha256
    sha256sum -c MANIFEST.sha256
  )
  export DEPLOY_TIMESTAMP RELEASE_BACKUP
}

backup_live_world() {
  "$OPS_ROOT/scripts/backup.sh" --apply
}

install_verified_release() {
  local artifact="$1"
  local checksum="$2"
  local source

  "$OPS_ROOT/scripts/install-bridge.sh" \
    --volume "$LIVE_VOLUME" \
    --jar "$artifact" \
    --sha256 "$checksum" \
    --confirm-live-volume INSTALL_BRIDGE_ON_LIVE_VOLUME \
    --apply || return

  for source in "$AGENT_WORKSPACE"/controller/*.py; do
    [[ -f "$source" && ! -L "$source" ]] ||
      return 1
    install -m 0644 "$source" \
      "/opt/colony-autopilot/controller/$(basename "$source")" || return
  done
}

health_check() {
  local started_at="$1"
  local deadline=$((SECONDS + 1200))

  while ((SECONDS < deadline)); do
    if [[ "$(docker inspect --format '{{.State.Running}}' "$LIVE_CONTAINER")" != true ]]; then
      operation_die "Minecraft container stopped during startup"
      return
    fi
    if docker logs --since "$started_at" "$LIVE_CONTAINER" 2>&1 |
      grep -Fq 'Done ('; then
      systemctl start colony-autopilot.service
      sleep 5
      systemctl is-active --quiet colony-autopilot.service ||
        operation_die "colony-autopilot failed its post-start check"
      return
    fi
    sleep 5
  done
  operation_die "Minecraft did not become ready within 20 minutes"
}

rollback_deployment() {
  local bridge_backup="$1"
  local controller_backup="$2"
  local started_at

  systemctl stop colony-autopilot.service 2>/dev/null || true
  docker stop "$LIVE_CONTAINER" >/dev/null 2>&1 || true
  "$OPS_ROOT/scripts/install-bridge.sh" \
    --volume "$LIVE_VOLUME" \
    --rollback "$bridge_backup" \
    --confirm-live-volume INSTALL_BRIDGE_ON_LIVE_VOLUME \
    --apply
  rsync -a --delete "$controller_backup/" /opt/colony-autopilot/controller/
  started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  start_live_services
  health_check "$started_at"
}

write_deployment_state() {
  local bridge_backup="$1"
  local artifact_checksum="$2"
  local state_file="$AGENT_STATE_ROOT/last-deploy.env"
  local audit_file="$AGENT_AUDIT_ROOT/$DEPLOY_TIMESTAMP.env"
  local temporary

  temporary="$(mktemp "$AGENT_STATE_ROOT/.deployment.XXXXXXXX")"
  {
    printf 'DEPLOY_TIMESTAMP=%q\n' "$DEPLOY_TIMESTAMP"
    printf 'DEPLOY_BRANCH=%q\n' "$DEPLOY_BRANCH"
    printf 'DEPLOY_HEAD=%q\n' "$DEPLOY_HEAD"
    printf 'RELEASE_BACKUP=%q\n' "$RELEASE_BACKUP"
    printf 'BRIDGE_BACKUP=%q\n' "$bridge_backup"
    printf 'ARTIFACT_SHA256=%q\n' "$artifact_checksum"
    printf 'DEPLOY_STATUS=success\n'
  } >"$temporary"
  chown root:colony-agent "$temporary"
  chmod 0640 "$temporary"
  cp "$temporary" "$audit_file"
  mv "$temporary" "$state_file"
}
