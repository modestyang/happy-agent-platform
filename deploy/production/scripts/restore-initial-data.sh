#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"

bundle_value() { sed -n "s/^$2=\([0-9a-fA-F][0-9a-fA-F]*\)$/\1/p" "$1/metadata.env"; }
require_bundle_manifest() {
  local bundle=$1 hash member actual listed=''
  while IFS=' ' read -r hash member; do
    [[ "$hash" =~ ^[a-fA-F0-9]{64}$ ]] || die "invalid bundle digest"
    case "$member" in ''|/*|*'..'*|*'?'*|*'['*|*'*'*) die "unsafe bundle member";; esac
    case "|$listed|" in *"|$member|"*) die "duplicate bundle member";; esac
    listed="${listed}${member}|"; actual="$bundle/$member"
    [ -f "$actual" ] && [ ! -L "$actual" ] || die "bundle member is not regular"
  done <"$bundle/SHA256SUMS"
  [ -z "$(find "$bundle" ! -type f ! -type d -print -quit)" ] || die "bundle contains special member"
  while IFS= read -r -d '' actual; do
    member=${actual#"$bundle"/}
    [ "$member" = SHA256SUMS ] || case "|$listed|" in *"|$member|"*) ;; *) die "unmanifested bundle member";; esac
  done < <(find "$bundle" -type f -print0)
  for member in initial.dump media.tar agent-master-key metadata.env; do case "|$listed|" in *"|$member|"*) ;; *) die "required bundle member is unmanifested";; esac; done
  (cd "$bundle" && sha256sum --check --strict SHA256SUMS >/dev/null)
}
restore() {
  [ "$#" = 2 ] && [ "$2" = --initial-empty-target ] || die "usage: restore-initial-data.sh BUNDLE --initial-empty-target"
  local original=$1 bundle release staging media expected_fitness expected_agent expected_tables expected_objects expected_media expected_key actual
  case "$original" in ''|~*|*'?'*|*'['*|*'*'*|!/*) die "bundle must be a safe absolute path";; esac
  bundle=$(realpath -m -- "$original"); [ -d "$bundle" ] || die "migration bundle is missing"
  require_file "$bundle/SHA256SUMS"; require_bundle_manifest "$bundle"
  expected_fitness=$(bundle_value "$bundle" fitness_history_count); expected_agent=$(bundle_value "$bundle" agent_history_count)
  expected_tables=$(bundle_value "$bundle" application_table_count); expected_objects=$(bundle_value "$bundle" key_object_count)
  expected_media=$(bundle_value "$bundle" media_sha256); expected_key=$(bundle_value "$bundle" master_key_sha256)
  for actual in "$expected_fitness" "$expected_agent" "$expected_tables" "$expected_objects" "$expected_media" "$expected_key"; do [ -n "$actual" ] || die "invalid bundle metadata"; done
  [ "$(sha256sum "$bundle/media.tar" | awk '{print $1}')" = "$expected_media" ] || die "media checksum mismatch"
  [ "$(sha256sum "$bundle/agent-master-key" | awk '{print $1}')" = "$expected_key" ] || die "master key checksum mismatch"
  if ! tar -tf "$bundle/media.tar" >/dev/null; then die "invalid media archive"; fi
  if tar -tf "$bundle/media.tar" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then die "unsafe media archive path"; fi
  if tar -tvf "$bundle/media.tar" | awk '{kind=substr($0,1,1); if (kind != "-" && kind != "d") exit 1}'; then :; else die "unsafe media archive member type"; fi
  media=$(validate_descendant "$HAPPY_AGENT_ROOT/data/media")
  [ -z "$(find "$media" -mindepth 1 -print -quit)" ] || die "media target is not empty"
  staging=$(validate_descendant "$HAPPY_AGENT_ROOT/data/.restore-media-$$")
  install -d -m 0700 "$staging"
  tar -C "$staging" -xf "$bundle/media.tar"
  release=$(current_release)
  service_healthy "$release" postgres || die "PostgreSQL is not healthy"
  [ "$(compose_release "$release" exec -T postgres psql -Atqc "select count(*) from pg_namespace n where n.nspname !~ '^pg_' and n.nspname <> 'information_schema'; select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname !~ '^pg_' and n.nspname <> 'information_schema'; select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname !~ '^pg_' and n.nspname <> 'information_schema';")" = '0
0
0' ] || die "target contains application schemas or user objects"
  compose_release "$release" exec -T postgres pg_restore --exit-on-error -U postgres -d happy_agent <"$bundle/initial.dump"
  compose_release "$release" exec -T postgres psql -v ON_ERROR_STOP=1 -f /usr/local/share/happy-agent-enforce-isolation.sql
  actual=$(compose_release "$release" exec -T postgres psql -Atqc 'select (select count(*) from fitness.flyway_schema_history), (select count(*) from agent.flyway_schema_history), (select count(*) from pg_tables where schemaname in (''fitness'',''agent'')), (select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in (''fitness'',''agent''))')
  [ "$actual" = "$expected_fitness|$expected_agent|$expected_tables|$expected_objects" ] || die "restore database verification failed"
  tar -C "$media" -xf "$bundle/media.tar"
  cp -- "$bundle/agent-master-key" "$HAPPY_AGENT_ROOT/secrets/agent-master-key"
  chmod 0600 "$HAPPY_AGENT_ROOT/secrets/agent-master-key"
  [ "$(sha256sum "$HAPPY_AGENT_ROOT/secrets/agent-master-key" | awk '{print $1}')" = "$expected_key" ] || die "restored master key checksum mismatch"
  rm -rf -- "$staging"
  log "initial data restored from bundle"
}
with_lock restore "$@"
