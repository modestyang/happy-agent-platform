#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
source "$SCRIPT_DIR/backup.sh"
activate() {
  [ "$#" = 1 ] || die "usage: activate-release.sh RELEASE_ID"
  local target old='' image attempt attempts interval
  target=$(release_path "$1"); [ -d "$target" ] || die "release is missing"; verify_manifest "$target" .env compose.yml nginx.conf
  [ ! -L "$HAPPY_AGENT_ROOT/current" ] || old=$(current_release)
  backup_core
  for image in "$target"/images/*.tar; do [ -e "$image" ] || continue; docker load -i "$image"; done
  attempts=${HAPPY_AGENT_HEALTH_ATTEMPTS:-12}; interval=${HAPPY_AGENT_HEALTH_INTERVAL:-5}
  compose_release "$target" up -d postgres app nginx
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if all_services_healthy "$target" && public_smoke; then
      switch_current "$target"; log "release activated: $1"; return
    fi
    sleep "$interval"
  done
  if recover_previous "$target" "$old"; then die "release activation failed; previous release recovered"; fi
  die "release activation failed; previous release recovery failed"
}
recover_previous() {
  local attempted=$1 previous=$2 stop_ok=0
  compose_release "$attempted" stop app nginx && stop_ok=1 || log "attempted release stop failed; continuing recovery"
  [ -n "$previous" ] || return 1
  compose_release "$previous" up -d app nginx || return 1
  all_services_healthy "$previous" || return 1
  switch_current "$previous"
  log "previous release recovered after attempted stop=$stop_ok"
}
public_smoke() {
  local endpoint code
  for endpoint in /api/app/bootstrap /admin / /api/app/events; do
    code=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 15 "https://39.101.65.254${endpoint}") || return 1
    case "$endpoint:$code" in /api/app/bootstrap:401|/admin:401|/:200|/api/app/events:401) ;; *) return 1;; esac
  done
}
with_lock activate "$@"
