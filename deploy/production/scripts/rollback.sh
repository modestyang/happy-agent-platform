#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
rollback() {
  [ "$#" = 1 ] || die "usage: rollback.sh RELEASE_ID"
  local target old=''
  target=$(release_path "$1"); [ -d "$target" ] || die "release is missing"; verify_manifest "$target" .env compose.yml nginx.conf
  [ ! -L "$HAPPY_AGENT_ROOT/current" ] || old=$(current_release)
  if attempt_rollback "$target"; then log "rollback selected: $1"; return; fi
  if recover_previous "$target" "$old"; then die "rollback target is unhealthy; previous release recovered"; fi
  die "rollback target is unhealthy; previous release recovery failed"
}
attempt_rollback() {
  local target=$1
  compose_release "$target" up -d --no-deps app nginx || return 1
  all_services_healthy "$target" || return 1
  switch_current "$target"
}
recover_previous() {
  local attempted=$1 previous=$2
  compose_release "$attempted" stop app nginx || log "attempted rollback stop failed; continuing recovery"
  [ -n "$previous" ] || return 1
  compose_release "$previous" up -d --no-deps app nginx || return 1
  all_services_healthy "$previous" || return 1
  switch_current "$previous"
  log "previous release recovered"
}
with_lock rollback "$@"
