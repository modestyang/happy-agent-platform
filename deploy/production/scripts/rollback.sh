#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
rollback() {
  [ "$#" = 1 ] || die "usage: rollback.sh RELEASE_ID"
  local target old=''
  target=$(release_path "$1"); [ -d "$target" ] || die "release is missing"; verify_manifest "$target"
  [ ! -L "$HAPPY_AGENT_ROOT/current" ] || old=$(current_release)
  if compose_release "$target" up -d --no-deps app nginx && compose_release "$target" ps app nginx | grep -q healthy; then switch_current "$target"; log "rollback selected: $1"; return; fi
  compose_release "$target" stop app nginx || true
  if [ -n "$old" ]; then compose_release "$old" up -d --no-deps app nginx || true; switch_current "$old"; fi
  die "rollback target is unhealthy"
}
with_lock rollback "$@"
