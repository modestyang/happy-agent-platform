#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
source "$SCRIPT_DIR/backup.sh"

attempt_activation() {
  local target=$1 previous=$2 postgres_before=$3 attempts=$4 interval=$5
  compose_release "$previous" stop app nginx || return 1
  switch_current "$target" || return 1
  compose_release "$target" up -d --no-deps app nginx || return 1
  wait_application_runtime "$target" "$postgres_before" "$attempts" "$interval" || return 1
  public_smoke || return 1
}

activate_core() {
  [ "$#" = 1 ] || die 'usage: activate-release.sh RELEASE_ID'
  local target previous generation postgres_before image attempts interval
  target=$(release_path "$1")
  [ -d "$target" ] || die 'release is missing'
  verify_manifest "$target" .env compose.yml nginx.conf
  previous=$(current_release)
  verify_manifest "$previous" .env compose.yml nginx.conf
  generation=$(current_generation)
  require_file "$generation/agent-master-key"
  [ ! -L "$generation/agent-master-key" ] \
    || die 'active state generation has an indirect Agent master key'
  [ -s "$generation/agent-master-key" ] || die 'active state generation has an empty Agent master key'
  postgres_before=$(postgres_identity "$previous") || die 'PostgreSQL is not healthy before activation'

  backup_core
  while IFS= read -r -d '' image; do
    [ -f "$image" ] && [ ! -L "$image" ] || die 'release image archive is unsafe'
    docker load -i "$image"
  done < <(find "$target/images" -maxdepth 1 -type f -name '*.tar' -print0 2>/dev/null)

  attempts=${HAPPY_AGENT_HEALTH_ATTEMPTS:-12}
  interval=${HAPPY_AGENT_HEALTH_INTERVAL:-5}
  [[ "$attempts" =~ ^[1-9][0-9]*$ ]] || die 'invalid health attempt count'
  [[ "$interval" =~ ^[0-9]+$ ]] || die 'invalid health interval'
  if attempt_activation "$target" "$previous" "$postgres_before" "$attempts" "$interval"; then
    log "release activated: $1"
    return 0
  fi
  if _recover_previous_release "$target" "$previous" "$postgres_before" "$attempts" "$interval"; then
    die 'release activation failed; previous release recovered'
  fi
  die 'release activation failed; previous release recovery failed'
}

with_lock activate_core "$@"
