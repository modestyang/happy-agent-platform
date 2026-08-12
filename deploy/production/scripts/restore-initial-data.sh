#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"

bundle_count() { sed -n "s/^$2=\([0-9][0-9]*\)$/\1/p" "$1/metadata.env"; }
bundle_hash() { sed -n "s/^$2=\([a-fA-F0-9]\{64\}\)$/\1/p" "$1/metadata.env"; }
tree_hash() { (cd "$1" && find . -type f -print | LC_ALL=C sort | while IFS= read -r file; do sha256sum "$file"; done | sha256sum | awk '{print $1}'); }
validate_metadata() {
  local file=$1 key line
  while IFS= read -r line; do
    key=${line%%=*}
    case "$key" in fitness_history_count|agent_history_count|application_table_count|key_object_count|media_sha256|media_tree_sha256|master_key_sha256) ;; *) die "unknown metadata key";; esac
  done <"$file"
  for key in fitness_history_count agent_history_count application_table_count key_object_count media_sha256 media_tree_sha256 master_key_sha256; do
    [ "$(grep -c "^$key=" "$file")" = 1 ] || die "metadata key must occur exactly once: $key"
  done
}
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
  local original=$1 bundle release staging media key_tmp expected_fitness expected_agent expected_tables expected_objects expected_media expected_tree expected_key actual
  case "$original" in ''|~*|*'?'*|*'['*|*'*'*|!/*) die "bundle must be a safe absolute path";; esac
  bundle=$(realpath -m -- "$original"); [ -d "$bundle" ] || die "migration bundle is missing"
  require_file "$bundle/SHA256SUMS"; require_bundle_manifest "$bundle"
  validate_metadata "$bundle/metadata.env"
  expected_fitness=$(bundle_count "$bundle" fitness_history_count); expected_agent=$(bundle_count "$bundle" agent_history_count)
  expected_tables=$(bundle_count "$bundle" application_table_count); expected_objects=$(bundle_count "$bundle" key_object_count)
  expected_media=$(bundle_hash "$bundle" media_sha256); expected_tree=$(bundle_hash "$bundle" media_tree_sha256); expected_key=$(bundle_hash "$bundle" master_key_sha256)
  for actual in "$expected_fitness" "$expected_agent" "$expected_tables" "$expected_objects" "$expected_media" "$expected_tree" "$expected_key"; do [ -n "$actual" ] || die "invalid bundle metadata"; done
  [ "$(sha256sum "$bundle/media.tar" | awk '{print $1}')" = "$expected_media" ] || die "media checksum mismatch"
  [ "$(sha256sum "$bundle/agent-master-key" | awk '{print $1}')" = "$expected_key" ] || die "master key checksum mismatch"
  if ! tar -tf "$bundle/media.tar" >/dev/null; then die "invalid media archive"; fi
  if tar -tf "$bundle/media.tar" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then die "unsafe media archive path"; fi
  if tar -tvf "$bundle/media.tar" | awk '{kind=substr($0,1,1); if (kind != "-" && kind != "d") exit 1}'; then :; else die "unsafe media archive member type"; fi
  media=$(validate_descendant "$HAPPY_AGENT_ROOT/data/media")
  [ -z "$(find "$media" -mindepth 1 -print -quit)" ] || die "media target is not empty"
  [ ! -e "$HAPPY_AGENT_ROOT/secrets/agent-master-key" ] || die "initial target already has a master key"
  staging=$(validate_descendant "$HAPPY_AGENT_ROOT/data/.restore-media-$$")
  install -d -m 0700 "$staging"
  tar -C "$staging" -xf "$bundle/media.tar"
  [ "$(tree_hash "$staging")" = "$expected_tree" ] || die "staged media checksum mismatch"
  release=$(current_release)
  service_healthy "$release" postgres || die "PostgreSQL is not healthy"
  [ "$(compose_release "$release" exec -T postgres psql -Atqc "select count(*) from pg_namespace n where n.nspname !~ '^pg_' and n.nspname not in ('information_schema','public','fitness','agent'); select count(*) from pg_namespace where nspname in ('fitness','agent'); select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname !~ '^pg_' and n.nspname <> 'information_schema'; select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname !~ '^pg_' and n.nspname <> 'information_schema'; select count(*) from pg_type t join pg_namespace n on n.oid=t.typnamespace where n.nspname !~ '^pg_' and n.nspname <> 'information_schema'; select count(*) from pg_roles where rolname in ('fitness_app','agent_app') and (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolinherit); select count(*) from pg_roles where rolname in ('fitness_app','agent_app'); select has_database_privilege('public','happy_agent','CONNECT')::int; select (has_database_privilege('fitness_app','happy_agent','CONNECT') and has_database_privilege('agent_app','happy_agent','CONNECT'))::int; select (has_schema_privilege('public','public','USAGE') or has_schema_privilege('public','public','CREATE') or has_schema_privilege('fitness_app','public','USAGE') or has_schema_privilege('agent_app','public','USAGE'))::int; select count(*) from pg_default_acl;")" = '0
0
0
0
0
0
2
0
1
0
0' ] || die "target does not match the initial application role and ACL baseline"
  compose_release "$release" exec -T postgres pg_restore --exit-on-error -U postgres -d happy_agent <"$bundle/initial.dump"
  compose_release "$release" exec -T postgres psql -v ON_ERROR_STOP=1 -f /usr/local/share/happy-agent-enforce-isolation.sql
  actual=$(compose_release "$release" exec -T postgres psql -Atqc 'select (select count(*) from fitness.flyway_schema_history), (select count(*) from agent.flyway_schema_history), (select count(*) from pg_tables where schemaname in (''fitness'',''agent'')), (select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in (''fitness'',''agent''))')
  [ "$actual" = "$expected_fitness|$expected_agent|$expected_tables|$expected_objects" ] || die "restore database verification failed"
  rmdir "$media"
  mv -T -- "$staging" "$media"
  key_tmp=$(validate_descendant "$HAPPY_AGENT_ROOT/secrets/.agent-master-key.$$")
  cp -- "$bundle/agent-master-key" "$key_tmp"
  chmod 0600 "$key_tmp"
  [ "$(sha256sum "$key_tmp" | awk '{print $1}')" = "$expected_key" ] || die "staged master key checksum mismatch"
  mv -T -- "$key_tmp" "$HAPPY_AGENT_ROOT/secrets/agent-master-key"
  log "initial data restored from bundle"
}
with_lock restore "$@"
