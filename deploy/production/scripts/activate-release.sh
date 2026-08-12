#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
activate() {
  [ "$#" = 1 ] || die "usage: activate-release.sh RELEASE_ID"
  local target old='' image attempt attempts interval
  target=$(release_path "$1"); [ -d "$target" ] || die "release is missing"; verify_manifest "$target"
  [ ! -L "$HAPPY_AGENT_ROOT/current" ] || old=$(current_release)
  "$SCRIPT_DIR/backup.sh"
  for image in "$target"/images/*.tar; do [ -e "$image" ] || continue; docker load -i "$image"; done
  attempts=${HAPPY_AGENT_HEALTH_ATTEMPTS:-12}; interval=${HAPPY_AGENT_HEALTH_INTERVAL:-5}
  compose_release "$target" up -d postgres app nginx || true
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if compose_release "$target" ps | grep -q healthy && curl -fsS http://127.0.0.1/healthz >/dev/null; then
      switch_current "$target"; log "release activated: $1"; return
    fi
    sleep "$interval"
  done
  compose_release "$target" stop app nginx || true
  if [ -n "$old" ]; then compose_release "$old" up -d app nginx || true; switch_current "$old"; fi
  die "release activation failed"
}
with_lock activate "$@"
