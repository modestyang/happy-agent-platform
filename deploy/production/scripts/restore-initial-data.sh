#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
restore() {
  [ "$#" = 2 ] && [ "$2" = --initial-empty-target ] || die "usage: restore-initial-data.sh BUNDLE --initial-empty-target"
  local bundle=$1 release media expected_fitness expected_agent actual_fitness actual_agent
  bundle=$(realpath -m -- "$bundle")
  case "$bundle" in /*) ;; *) die "bundle must be absolute";; esac
  [ -d "$bundle" ] || die "migration bundle is missing"
  require_file "$bundle/SHA256SUMS"; (cd "$bundle" && sha256sum --check SHA256SUMS >/dev/null)
  require_file "$bundle/initial.dump"; require_file "$bundle/media.tar"; require_file "$bundle/agent-master-key"; require_file "$bundle/metadata.env"
  expected_fitness=$(sed -n 's/^fitness_history_count=\([0-9][0-9]*\)$/\1/p' "$bundle/metadata.env")
  expected_agent=$(sed -n 's/^agent_history_count=\([0-9][0-9]*\)$/\1/p' "$bundle/metadata.env")
  [ -n "$expected_fitness" ] && [ -n "$expected_agent" ] || die "invalid bundle metadata"
  release=$(current_release)
  compose_release "$release" ps postgres | grep -q healthy || die "PostgreSQL is not healthy"
  [ "$(compose_release "$release" exec -T postgres psql -Atqc 'select count(*) from pg_tables where schemaname not in (''pg_catalog'',''information_schema'')')" = 0 ] || die "target is not empty"
  tar -tf "$bundle/media.tar" | grep -Eq '(^/|(^|/)\.\.(/|$))' && die "unsafe media archive"
  compose_release "$release" exec -T postgres pg_restore --exit-on-error -U postgres -d happy_agent <"$bundle/initial.dump"
  compose_release "$release" exec -T postgres psql -v ON_ERROR_STOP=1 -f /usr/local/share/happy-agent-enforce-isolation.sql
  media=$(validate_descendant "$HAPPY_AGENT_ROOT/data/media")
  tar -C "$media" -xf "$bundle/media.tar"
  cp -- "$bundle/agent-master-key" "$HAPPY_AGENT_ROOT/secrets/agent-master-key"
  chmod 0600 "$HAPPY_AGENT_ROOT/secrets/agent-master-key"
  actual_fitness=$(compose_release "$release" exec -T postgres psql -Atqc 'select count(*) from fitness.flyway_schema_history')
  actual_agent=$(compose_release "$release" exec -T postgres psql -Atqc 'select count(*) from agent.flyway_schema_history')
  [ "$actual_fitness" = "$expected_fitness" ] && [ "$actual_agent" = "$expected_agent" ] || die "restore metadata validation failed"
  log "initial data restored from bundle"
}
with_lock restore "$@"
