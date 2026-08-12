#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
activate() {
  [ "$#" = 1 ] || die "usage: activate-release.sh RELEASE_ID"
  local target old='' image attempt attempts interval
  target=$(release_path "$1"); [ -d "$target" ] || die "release is missing"; verify_manifest "$target" .env compose.yml nginx.conf
  [ ! -L "$HAPPY_AGENT_ROOT/current" ] || old=$(current_release)
  "$SCRIPT_DIR/backup.sh"
  for image in "$target"/images/*.tar; do [ -e "$image" ] || continue; docker load -i "$image"; done
  attempts=${HAPPY_AGENT_HEALTH_ATTEMPTS:-12}; interval=${HAPPY_AGENT_HEALTH_INTERVAL:-5}
  compose_release "$target" up -d postgres app nginx
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if all_services_healthy "$target" && public_smoke; then
      switch_current "$target"; log "release activated: $1"; return
    fi
    sleep "$interval"
  done
  compose_release "$target" stop app nginx || log "attempted release stop failed"
  if [ -n "$old" ]; then
    compose_release "$old" up -d app nginx && all_services_healthy "$old" && switch_current "$old" || log "old release recovery failed"
  fi
  die "release activation failed"
}
public_smoke() {
  local endpoint code
  for endpoint in /api/app/bootstrap /admin / /api/app/events; do
    code=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 15 "https://39.101.65.254${endpoint}") || return 1
    case "$endpoint:$code" in /api/app/bootstrap:401|/admin:401|/:200|/api/app/events:401) ;; *) return 1;; esac
  done
}
with_lock activate "$@"
