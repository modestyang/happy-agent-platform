#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"

metadata_count() { sed -n "s/^$2=\([0-9][0-9]*\)$/\1/p" "$1/metadata.env"; }
metadata_hash() { sed -n "s/^$2=\([a-fA-F0-9]\{64\}\)$/\1/p" "$1/metadata.env"; }

media_tree_hash() {
  (
    cd "$1"
    find . -type f -print | LC_ALL=C sort \
      | while IFS= read -r file; do sha256sum "$file"; done \
      | sha256sum | awk '{print $1}'
  )
}

validate_metadata() {
  local file=$1 line key value seen='|'
  require_file "$file"
  [ ! -L "$file" ] || die 'metadata must be a regular file'
  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^([a-z0-9_]+)=([^=]+)$ ]] || die "malformed metadata line for key: ${line%%=*}"
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    case "$key" in
      fitness_history_count|agent_history_count|application_table_count|key_object_count)
        [[ "$value" =~ ^[0-9]+$ ]] || die 'invalid metadata count'
        ;;
      media_sha256|media_tree_sha256|master_key_sha256)
        [[ "$value" =~ ^[a-fA-F0-9]{64}$ ]] || die 'invalid metadata digest'
        ;;
      *) die 'unknown metadata key';;
    esac
    case "$seen" in *"|$key|"*) die 'duplicate metadata key';; esac
    seen="${seen}${key}|"
  done <"$file"
  for key in fitness_history_count agent_history_count application_table_count key_object_count \
    media_sha256 media_tree_sha256 master_key_sha256; do
    case "$seen" in *"|$key|"*) ;; *) die "missing metadata key: $key";; esac
  done
}

verify_bundle_manifest() {
  local bundle=$1 line digest member actual listed='|'
  require_file "$bundle/SHA256SUMS"
  [ ! -L "$bundle/SHA256SUMS" ] || die 'bundle manifest must be a regular file'
  [ -z "$(find "$bundle" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
    || die 'bundle contains a special member'
  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^([a-fA-F0-9]{64})[[:space:]][\ \*](.+)$ ]] || die 'malformed bundle manifest line'
    digest=${BASH_REMATCH[1]}
    member=${BASH_REMATCH[2]}
    case "$member" in ''|/*|*'?'*|*'['*|*'*'*|*'\\'*|*'|'*|.|..|../*|*/../*|*/..) die 'unsafe bundle member';; esac
    case "$listed" in *"|$member|"*) die 'duplicate bundle manifest member';; esac
    listed="${listed}${member}|"
    actual="$bundle/$member"
    [ -f "$actual" ] && [ ! -L "$actual" ] || die 'bundle member is not a regular file'
  done <"$bundle/SHA256SUMS"
  while IFS= read -r -d '' actual; do
    member=${actual#"$bundle"/}
    [ "$member" = SHA256SUMS ] || case "$listed" in *"|$member|"*) ;; *) die 'unmanifested bundle file';; esac
  done < <(find "$bundle" -type f -print0)
  for member in initial.dump media.tar agent-master-key metadata.env source-validation.json; do
    case "$listed" in *"|$member|"*) ;; *) die "required bundle member is unmanifested: $member";; esac
  done
  (cd "$bundle" && sha256sum --check --strict SHA256SUMS >/dev/null) \
    || die 'bundle checksum verification failed'
}

validate_media_archive() {
  local archive=$1 member type normalized component expected listed='|'
  local -a components
  tar -tf "$archive" >/dev/null || die 'invalid media archive'
  tar -tvf "$archive" >/dev/null || die 'invalid media archive member listing'
  exec 8< <(tar -tvf "$archive" | awk '{print substr($0, 1, 1)}')
  while IFS= read -r member || [ -n "$member" ]; do
    IFS= read -r type <&8 || die 'media archive listing is inconsistent'
    case "$type" in -|d) ;; *) die 'media archive contains a link or special member';; esac
    case "$member" in ''|/*|*'\\'*|*'|'*|*//*|..|../*|*/../*|*/..) die 'unsafe media archive path';; esac
    IFS='/' read -r -a components <<<"$member"
    normalized=''
    for component in "${components[@]}"; do
      case "$component" in ''|.) continue;; ..) die 'unsafe media archive path';; esac
      if [ -n "$normalized" ]; then normalized="$normalized/$component"; else normalized=$component; fi
    done
    [ -n "$normalized" ] && [ "$normalized" != . ] || die 'empty media archive destination'
    expected=$normalized
    [ "$type" != d ] || expected="$expected/"
    [ "$member" = "$expected" ] || die 'non-canonical media archive member'
    case "$listed" in *"|$normalized|"*) die 'duplicate media archive destination';; esac
    listed="${listed}${normalized}|"
  done < <(tar -tf "$archive")
  if IFS= read -r type <&8; then die 'media archive listing is inconsistent'; fi
  exec 8<&-
}

temporary_postgres_healthy() {
  local project=$1 runtime_root=$2 release=$3
  compose_temporary "$project" "$runtime_root" "$release" \
    ps --format '{{.Service}} {{.State}} {{.Health}}' postgres \
    | grep -Fx 'postgres running healthy' >/dev/null
}

wait_temporary_postgres() {
  local project=$1 runtime_root=$2 release=$3 attempt
  for attempt in $(seq 1 60); do
    if temporary_postgres_healthy "$project" "$runtime_root" "$release"; then return 0; fi
    sleep 1
  done
  return 1
}

wait_selected_postgres() {
  local release=$1 attempt
  for attempt in $(seq 1 60); do
    if service_matches_release "$release" postgres; then return 0; fi
    sleep 1
  done
  return 1
}

production_postgres_container() {
  local release=$1 container_id lines
  container_id=$(compose_release "$release" ps -a -q postgres) || return 1
  lines=$(printf '%s\n' "$container_id" | sed '/^$/d' | wc -l | tr -d ' ')
  [ "$lines" -le 1 ] || return 1
  printf '%s\n' "$container_id"
}

postgres_container_stopped() {
  local container_id=$1 status
  status=$(docker inspect --format '{{.State.Status}}' "$container_id") || return 1
  case "$status" in created|exited|dead) return 0;; *) return 1;; esac
}

restore_core() (
  set -euo pipefail
  [ "$#" = 2 ] && [ "$2" = --initial-empty-target ] \
    || die 'usage: restore-initial-data.sh BUNDLE --initial-empty-target'
  local original=$1 source_bundle bundle staged_bundle timestamp generation_id pending complete runtime_root project
  local release previous_generation production_postgres expected_fitness expected_agent expected_tables expected_objects
  local expected_media expected_tree expected_key actual project_started=0 renamed=0 committed=0

  case "$original" in ''|~*|*'?'*|*'['*|*'*'*|!/*) die 'bundle must be a safe absolute path';; esac
  source_bundle=$(realpath -m -- "$original")
  [ -d "$source_bundle" ] && [ ! -L "$source_bundle" ] \
    || die 'migration bundle is missing or unsafe'
  verify_bundle_manifest "$source_bundle"
  validate_metadata "$source_bundle/metadata.env"
  validate_media_archive "$source_bundle/media.tar"

  expected_media=$(metadata_hash "$source_bundle" media_sha256)
  expected_key=$(metadata_hash "$source_bundle" master_key_sha256)
  [ "$(sha256sum "$source_bundle/media.tar" | awk '{print $1}')" = "$expected_media" ] \
    || die 'media checksum mismatch'
  [ "$(sha256sum "$source_bundle/agent-master-key" | awk '{print $1}')" = "$expected_key" ] \
    || die 'master key checksum mismatch'
  [ -s "$source_bundle/agent-master-key" ] || die 'master key is empty'

  timestamp=${HAPPY_AGENT_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}
  [[ "$timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || die 'unsafe restore timestamp'
  generation_id="restore-$timestamp"
  pending=$(validate_descendant "$HAPPY_AGENT_ROOT/state/generations/.pending-$generation_id")
  complete=$(generation_path "$generation_id")
  runtime_root=$(validate_descendant "$HAPPY_AGENT_ROOT/state/.restore-runtime-$generation_id")
  project=$(printf 'happy-agent-restore-%s-%s' "$timestamp" "$$" | tr '[:upper:]' '[:lower:]')
  validate_identifier "$project" >/dev/null
  [ ! -e "$pending" ] && [ ! -e "$complete" ] && [ ! -e "$runtime_root" ] \
    || die 'restore generation already exists'

  cleanup_restore() {
    local cleanup_status=$?
    if [ "$project_started" = 1 ]; then
      compose_temporary "$project" "$runtime_root" "$release" down --volumes --remove-orphans \
        >/dev/null 2>&1 || true
    fi
    if [ -n "${runtime_root:-}" ] && [ -d "$runtime_root" ]; then rm -rf -- "$runtime_root"; fi
    if [ "$committed" = 0 ]; then
      if [ "$renamed" = 1 ] && [ -d "$complete" ]; then
        if [ -L "$HAPPY_AGENT_ROOT/state/current" ] \
            && [ "$(realpath -m -- "$HAPPY_AGENT_ROOT/state/current")" = "$complete" ]; then
          log 'failed restore left the selected complete generation quarantined in place'
        else
          rm -rf -- "$complete"
        fi
      fi
      if [ -d "$pending" ]; then
        if [ -L "$HAPPY_AGENT_ROOT/state/current" ] \
            && [ "$(realpath -m -- "$HAPPY_AGENT_ROOT/state/current")" = "$pending" ]; then
          log 'failed restore left the selected pending generation quarantined in place'
        else
          rm -rf -- "$pending"
        fi
      fi
    fi
    return "$cleanup_status"
  }
  trap cleanup_restore EXIT

  install -d -m 0700 "$pending" "$pending/postgres"
  staged_bundle="$pending/.bundle"
  install -d -m 0700 "$staged_bundle"
  for member in SHA256SUMS initial.dump media.tar agent-master-key metadata.env \
    source-validation.json; do
    cp -P -- "$source_bundle/$member" "$staged_bundle/$member"
  done
  verify_bundle_manifest "$staged_bundle"
  validate_metadata "$staged_bundle/metadata.env"
  validate_media_archive "$staged_bundle/media.tar"
  bundle=$staged_bundle
  expected_fitness=$(metadata_count "$bundle" fitness_history_count)
  expected_agent=$(metadata_count "$bundle" agent_history_count)
  expected_tables=$(metadata_count "$bundle" application_table_count)
  expected_objects=$(metadata_count "$bundle" key_object_count)
  expected_media=$(metadata_hash "$bundle" media_sha256)
  expected_tree=$(metadata_hash "$bundle" media_tree_sha256)
  expected_key=$(metadata_hash "$bundle" master_key_sha256)
  [ "$(sha256sum "$bundle/media.tar" | awk '{print $1}')" = "$expected_media" ] \
    || die 'staged media archive checksum mismatch'
  [ "$(sha256sum "$bundle/agent-master-key" | awk '{print $1}')" = "$expected_key" ] \
    || die 'staged master key source checksum mismatch'
  [ -s "$bundle/agent-master-key" ] || die 'staged master key source is empty'

  install -d -m 0750 "$pending/media"
  tar -C "$pending/media" -xf "$bundle/media.tar"
  [ -z "$(find "$pending/media" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
    || die 'extracted media contains a special member'
  [ "$(media_tree_hash "$pending/media")" = "$expected_tree" ] \
    || die 'staged media checksum mismatch'
  cp -- "$bundle/agent-master-key" "$pending/agent-master-key"
  chmod 0600 "$pending/agent-master-key"
  cmp -- "$bundle/agent-master-key" "$pending/agent-master-key" >/dev/null \
    || die 'staged master key differs from bundle bytes'
  [ "$(sha256sum "$pending/agent-master-key" | awk '{print $1}')" = "$expected_key" ] \
    || die 'staged master key checksum mismatch'

  release=$(current_release)
  verify_manifest "$release" .env compose.yml nginx.conf postgres/init-roles.sh \
    postgres/init-roles.sql postgres/enforce-isolation.sql postgres/assert-initial-empty-target.sql
  previous_generation=$(current_generation)

  install -d -m 0700 "$runtime_root" "$runtime_root/state" "$runtime_root/data"
  ln -s ../../current "$runtime_root/current"
  ln -s ../../secrets "$runtime_root/secrets"
  ln -s ../../certificates "$runtime_root/certificates"
  ln -s ../../../data/acme-webroot "$runtime_root/data/acme-webroot"
  ln -s "../../generations/.pending-$generation_id" "$runtime_root/state/current"

  project_started=1
  compose_temporary "$project" "$runtime_root" "$release" up -d --no-deps postgres
  wait_temporary_postgres "$project" "$runtime_root" "$release" \
    || die 'temporary PostgreSQL did not become healthy'
  actual=$(compose_temporary "$project" "$runtime_root" "$release" exec -T postgres \
    psql -XAtq -v ON_ERROR_STOP=1 -U postgres -d happy_agent \
      -f /usr/local/share/happy-agent-assert-initial-empty-target.sql)
  [ "$actual" = HAPPY_AGENT_INITIAL_TARGET_EMPTY ] \
    || die 'temporary PostgreSQL did not match the initial empty baseline'

  compose_temporary "$project" "$runtime_root" "$release" exec -T postgres \
    pg_restore --exit-on-error -U postgres -d happy_agent <"$bundle/initial.dump"
  compose_temporary "$project" "$runtime_root" "$release" exec -T postgres \
    psql -X -v ON_ERROR_STOP=1 -U postgres -d happy_agent \
      -f /usr/local/share/happy-agent-enforce-isolation.sql
  actual=$(compose_temporary "$project" "$runtime_root" "$release" exec -T postgres \
    psql -XAtq -v ON_ERROR_STOP=1 -U postgres -d happy_agent -c \
      "SELECT (SELECT count(*) FROM fitness.flyway_schema_history), (SELECT count(*) FROM agent.flyway_schema_history), (SELECT count(*) FROM pg_tables WHERE schemaname IN ('fitness','agent')), (SELECT count(*) FROM pg_proc function_entry JOIN pg_namespace namespace_entry ON namespace_entry.oid = function_entry.pronamespace WHERE namespace_entry.nspname IN ('fitness','agent'));" )
  [ "$actual" = "$expected_fitness|$expected_agent|$expected_tables|$expected_objects" ] \
    || die 'restored database verification failed'

  compose_temporary "$project" "$runtime_root" "$release" down --volumes --remove-orphans
  project_started=0
  rm -rf -- "$staged_bundle"
  staged_bundle=''

  compose_release "$release" stop app nginx

  production_postgres=$(production_postgres_container "$release") \
    || die 'production PostgreSQL container identity is ambiguous'

  recover_previous_postgres() {
    local selected recovery_postgres stop_failed=0
    selected=$(current_generation) || return 1
    if [ "$selected" = "$previous_generation" ] \
        && service_matches_release "$release" postgres; then
      log 'previous state generation and running PostgreSQL were retained'
      return 0
    fi
    recovery_postgres=$(production_postgres_container "$release") || return 1
    compose_release "$release" stop postgres >/dev/null 2>&1 || stop_failed=1
    if [ -n "$recovery_postgres" ] && ! postgres_container_stopped "$recovery_postgres"; then
      log 'recovery could not confirm PostgreSQL stopped before changing state generation'
      return 1
    fi
    [ "$stop_failed" = 0 ] \
      || log 'recovery stop reported failure but PostgreSQL is confirmed stopped; continuing'
    selected=$(current_generation) || return 1
    if [ "$selected" != "$previous_generation" ]; then
      switch_state_current "$previous_generation" || return 1
    fi
    compose_release "$release" up -d --no-deps --force-recreate postgres || return 1
    wait_selected_postgres "$release" || return 1
    [ "$(current_generation)" = "$previous_generation" ] || return 1
    log 'previous state generation and PostgreSQL health recovered'
  }

  if ! compose_release "$release" stop postgres \
      || { [ -n "$production_postgres" ] \
        && ! postgres_container_stopped "$production_postgres"; }; then
    if recover_previous_postgres; then
      die 'production PostgreSQL could not be confirmed stopped; previous state recovered'
    fi
    die 'production PostgreSQL could not be confirmed stopped; previous state recovery failed'
  fi
  if ! mv -T -- "$pending" "$complete"; then
    if recover_previous_postgres; then
      die 'restored state could not be finalized; previous state recovered'
    fi
    die 'restored state could not be finalized; previous state recovery failed'
  fi
  renamed=1
  if ! switch_state_current "$complete"; then
    if recover_previous_postgres; then
      die 'restored state could not be selected; previous state recovered'
    fi
    die 'restored state could not be selected; previous state recovery failed'
  fi
  if ! compose_release "$release" up -d --no-deps --force-recreate postgres \
      || ! wait_selected_postgres "$release"; then
    log 'restored PostgreSQL start failed; attempting previous state recovery'
    if recover_previous_postgres; then
      die 'restored PostgreSQL start failed; previous state recovered'
    fi
    die 'restored PostgreSQL start failed; previous state recovery failed'
  fi
  committed=1
  log "initial data restored into state generation $generation_id"
)

with_lock restore_core "$@"
