#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

required_files=(
  deploy/command-center/Caddyfile
  deploy/command-center/docker-compose.yml
  deploy/command-center/homepage/services.yaml
  deploy/command-center/homepage/settings.yaml
  deploy/command-center/homepage/bookmarks.yaml
  deploy/command-center/homepage/widgets.yaml
  deploy/command-center/homepage/docker.yaml
  deploy/systemd/command-center-caddy.service
  deploy/scripts/install-command-center.sh
  deploy/scripts/uninstall-command-center.sh
)

for relative_path in "${required_files[@]}"; do
  [[ -f "$repo_root/$relative_path" ]] || {
    printf 'missing required command center file: %s\n' "$relative_path" >&2
    exit 1
  }
done

for script in \
  deploy/scripts/install-command-center.sh \
  deploy/scripts/uninstall-command-center.sh; do
  bash -n "$repo_root/$script"
done

caddyfile="$repo_root/deploy/command-center/Caddyfile"
grep -Fq ':3000' "$caddyfile"
grep -Fq ':4096' "$caddyfile"
grep -Fq ':3001' "$caddyfile"
grep -Fq 'basicauth' "$caddyfile"
grep -Fq 'reverse_proxy' "$caddyfile"
grep -Fq '/ollama/' "$caddyfile"
grep -Fq '127.0.0.1:4097' "$caddyfile"
grep -Fq '127.0.0.1:8082' "$caddyfile"
grep -Fq '127.0.0.1:3002' "$caddyfile"
grep -Fq '127.0.0.1:11434' "$caddyfile"

compose="$repo_root/deploy/command-center/docker-compose.yml"
grep -Fq 'homepage' "$compose"
grep -Fq 'uptime-kuma' "$compose"
grep -Fq '127.0.0.1:8082' "$compose"
grep -Fq '127.0.0.1:3002' "$compose"

grep -Fq 'OpenCode' "$repo_root/deploy/command-center/homepage/services.yaml"
grep -Fq 'Ollama' "$repo_root/deploy/command-center/homepage/services.yaml"
grep -Fq 'Colony Autopilot' "$repo_root/deploy/command-center/homepage/services.yaml"
grep -Fq 'Minecraft' "$repo_root/deploy/command-center/homepage/services.yaml"

grep -Fq 'GamePilot' "$repo_root/deploy/command-center/homepage/bookmarks.yaml"
grep -Fq 'penguarjol' "$repo_root/deploy/command-center/homepage/bookmarks.yaml"

grep -Fq 'caddy hash-password' \
  "$repo_root/deploy/scripts/install-command-center.sh"
grep -Fq 'agent.env' \
  "$repo_root/deploy/scripts/install-command-center.sh"
grep -Fq 'docker compose' \
  "$repo_root/deploy/scripts/install-command-center.sh"
grep -Fq 'command-center-caddy.service' \
  "$repo_root/deploy/scripts/install-command-center.sh"

grep -Fq 'docker compose' \
  "$repo_root/deploy/scripts/uninstall-command-center.sh"
grep -Fq 'command-center-caddy' \
  "$repo_root/deploy/scripts/uninstall-command-center.sh"

printf 'command center smoke tests passed\n'
