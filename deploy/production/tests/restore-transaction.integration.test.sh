#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCRIPTS="$ROOT_DIR/scripts"
POSTGRES_IMAGE='postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777'
SUCCESS_TOKEN='HAPPY_AGENT_INITIAL_TARGET_EMPTY'
TMP_PARENT=${TMPDIR:-/tmp}
TMP=$(mktemp -d "${TMP_PARENT%/}/happy-agent-task4-restore.XXXXXX")
case "$TMP" in "${TMP_PARENT%/}"/happy-agent-task4-restore.*) ;; *) echo 'unsafe temporary directory' >&2; exit 1;; esac
CASE=${1:-all}
CONTAINER="happy-agent-task4-baseline-$(basename "$TMP" | tr -cd 'a-zA-Z0-9_.-')"
CONTAINER_STARTED=0

cleanup() {
  local status=$?
  if [ "$CONTAINER_STARTED" = 1 ]; then
    if [ "$status" != 0 ]; then docker logs --tail 120 "$CONTAINER" >&2 || true; fi
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
  case "$TMP" in "${TMP_PARENT%/}"/happy-agent-task4-restore.*) rm -rf -- "$TMP";; esac
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_mode() {
  local mode
  mode=$(stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1")
  [ "$mode" = "$2" ] || fail "mode for $1 is $mode, expected $2"
}

wait_for_postgres() {
  local attempt
  for attempt in $(seq 1 60); do
    if docker logs "$CONTAINER" 2>&1 | grep -Fq 'PostgreSQL init process complete; ready for start up.' \
        && docker exec "$CONTAINER" pg_isready -U postgres -d happy_agent >/dev/null 2>&1; then return; fi
    sleep 0.5
  done
  docker logs "$CONTAINER" >&2 || true
  fail 'PostgreSQL 16.14 did not become ready'
}

baseline_values() {
  docker exec -i "$CONTAINER" psql -XAtq -v ON_ERROR_STOP=1 -U postgres -d happy_agent <<'SQL'
SELECT count(*) FROM pg_roles
WHERE rolname !~ '^pg_' AND rolname NOT IN ('postgres', 'fitness_app', 'agent_app');
SELECT count(*) FROM pg_roles
WHERE rolname IN ('fitness_app', 'agent_app')
  AND rolcanlogin AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb
  AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls;
SELECT count(*) FROM pg_auth_members m
JOIN pg_roles granted ON granted.oid = m.roleid
JOIN pg_roles member_role ON member_role.oid = m.member
WHERE granted.rolname IN ('fitness_app', 'agent_app')
   OR member_role.rolname IN ('fitness_app', 'agent_app');
SELECT (has_database_privilege('public', current_database(), 'CONNECT')
     OR has_database_privilege('public', current_database(), 'CREATE')
     OR has_database_privilege('public', current_database(), 'TEMP'))::int;
SELECT (has_database_privilege('fitness_app', current_database(), 'CONNECT')
    AND NOT has_database_privilege('fitness_app', current_database(), 'CREATE')
    AND NOT has_database_privilege('fitness_app', current_database(), 'TEMP'))::int;
SELECT (has_database_privilege('agent_app', current_database(), 'CONNECT')
    AND NOT has_database_privilege('agent_app', current_database(), 'CREATE')
    AND NOT has_database_privilege('agent_app', current_database(), 'TEMP'))::int;
SELECT (has_schema_privilege('public', 'public', 'USAGE')
     OR has_schema_privilege('public', 'public', 'CREATE'))::int;
SELECT (has_schema_privilege('fitness_app', 'public', 'USAGE')
     OR has_schema_privilege('fitness_app', 'public', 'CREATE')
     OR has_schema_privilege('agent_app', 'public', 'USAGE')
     OR has_schema_privilege('agent_app', 'public', 'CREATE'))::int;
SELECT count(*) FROM pg_namespace WHERE nspname IN ('fitness', 'agent');
SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema';
SELECT count(*) FROM pg_default_acl d JOIN pg_roles r ON r.oid = d.defaclrole
WHERE r.rolname !~ '^pg_';
SELECT count(*)
FROM pg_database d, LATERAL aclexplode(COALESCE(d.datacl, acldefault('d', d.datdba))) acl
LEFT JOIN pg_roles grantee ON grantee.oid = acl.grantee
WHERE d.datname = current_database()
  AND acl.grantee <> 0
  AND grantee.rolname NOT IN ('postgres', 'fitness_app', 'agent_app');
SQL
}

assert_product_baseline() {
  local actual expected
  expected=$'0\n2\n0\n0\n1\n1\n0\n0\n0\n0\n0\n0'
  actual=$(baseline_values)
  if [ "$actual" != "$expected" ]; then
    printf 'FAIL: production init baseline mismatch\nexpected:\n%s\nactual:\n%s\n' "$expected" "$actual" >&2
    exit 1
  fi
}

run_assertion() {
  docker exec -i "$CONTAINER" psql -XAtq -v ON_ERROR_STOP=1 -U postgres -d happy_agent \
    <"$ROOT_DIR/postgres/assert-initial-empty-target.sql"
}

assert_pollution_rejected() {
  local label setup_sql cleanup_sql output_file
  label=$1; setup_sql=$2; cleanup_sql=$3; output_file="$TMP/${label}.stderr"
  printf 'checking pollution: %s\n' "$label" >&2
  docker exec "$CONTAINER" psql -Xq -v ON_ERROR_STOP=1 -U postgres -d happy_agent -c "$setup_sql" >/dev/null
  if run_assertion >"$TMP/${label}.stdout" 2>"$output_file"; then
    docker exec "$CONTAINER" psql -Xq -v ON_ERROR_STOP=1 -U postgres -d happy_agent -c "$cleanup_sql" >/dev/null || true
    fail "initial-target assertion accepted $label pollution"
  fi
  grep -F 'ERROR:' "$output_file" >/dev/null || fail "$label failed without a PostgreSQL assertion error"
  docker exec "$CONTAINER" psql -Xq -v ON_ERROR_STOP=1 -U postgres -d happy_agent -c "$cleanup_sql" >/dev/null
  [ "$(run_assertion)" = "$SUCCESS_TOKEN" ] || fail "baseline was not restored after $label cleanup"
}

run_baseline_test() {
  mkdir -p "$TMP/baseline-secrets"
  printf 'postgres-fixture-password\n' >"$TMP/baseline-secrets/postgres-password"
  printf 'fitness-fixture-password\n' >"$TMP/baseline-secrets/fitness-password"
  printf 'agent-fixture-password\n' >"$TMP/baseline-secrets/agent-password"
  chmod 0600 "$TMP/baseline-secrets"/*

  docker run -d --name "$CONTAINER" \
    -e POSTGRES_DB=happy_agent \
    -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password \
    -e FITNESS_DB_PASSWORD_FILE=/run/secrets/fitness-password \
    -e AGENT_DB_PASSWORD_FILE=/run/secrets/agent-password \
    -v "$TMP/baseline-secrets/postgres-password:/run/secrets/postgres-password:ro" \
    -v "$TMP/baseline-secrets/fitness-password:/run/secrets/fitness-password:ro" \
    -v "$TMP/baseline-secrets/agent-password:/run/secrets/agent-password:ro" \
    -v "$ROOT_DIR/postgres/init-roles.sh:/docker-entrypoint-initdb.d/00-init-roles.sh:ro" \
    -v "$ROOT_DIR/postgres/init-roles.sql:/usr/local/share/happy-agent-init-roles.sql:ro" \
    "$POSTGRES_IMAGE" >/dev/null
  CONTAINER_STARTED=1
  wait_for_postgres
  assert_product_baseline

  [ -f "$ROOT_DIR/postgres/assert-initial-empty-target.sql" ] || fail 'missing production initial-target assertion SQL'
  [ "$(run_assertion)" = "$SUCCESS_TOKEN" ] || fail 'production initial-target assertion omitted its fixed success token'
  assert_pollution_rejected extra-role 'CREATE ROLE unexpected_role' 'DROP ROLE unexpected_role'
  assert_pollution_rejected role-attributes 'ALTER ROLE fitness_app BYPASSRLS' 'ALTER ROLE fitness_app NOBYPASSRLS'
  assert_pollution_rejected membership 'GRANT fitness_app TO agent_app' 'REVOKE fitness_app FROM agent_app'
  assert_pollution_rejected database-acl 'GRANT TEMP ON DATABASE happy_agent TO fitness_app' 'REVOKE TEMP ON DATABASE happy_agent FROM fitness_app'
  assert_pollution_rejected schema-acl 'GRANT USAGE ON SCHEMA public TO fitness_app' 'REVOKE USAGE ON SCHEMA public FROM fitness_app'
  assert_pollution_rejected object 'CREATE TABLE public.pollution(id integer)' 'DROP TABLE public.pollution'
  assert_pollution_rejected collation \
    "CREATE COLLATION public.pollution (provider = libc, locale = 'C')" \
    'DROP COLLATION public.pollution'
  assert_pollution_rejected text-search-dictionary \
    'CREATE TEXT SEARCH DICTIONARY public.pollution (TEMPLATE = pg_catalog.simple)' \
    'DROP TEXT SEARCH DICTIONARY public.pollution'
  assert_pollution_rejected publication 'CREATE PUBLICATION pollution' 'DROP PUBLICATION pollution'
  echo 'PASS: real PostgreSQL initial baseline and pollution rejection'
}

write_coreutils_fakes() {
  local fake=$1
  mkdir -p "$fake"
  cat >"$fake/realpath" <<'EOF'
#!/usr/bin/env bash
[ "${1:-}" = -m ] && shift
[ "${1:-}" = -- ] && shift
if [ -L "$1" ]; then
  link=$(/usr/bin/readlink "$1")
  case "$link" in /*) printf '%s\n' "$link";; *) printf '%s/%s\n' "$(dirname "$1")" "$link";; esac
else
  case "$1" in /*) printf '%s\n' "$1";; *) printf '%s/%s\n' "$(/bin/pwd)" "$1";; esac
fi
EOF
  cat >"$fake/sha256sum" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = --check ]; then
  shift; [ "${1:-}" != --strict ] || shift
  while IFS=' ' read -r expected file; do
    actual=$(/usr/bin/shasum -a 256 "$file" | awk '{print $1}')
    [ "$actual" = "$expected" ] || exit 1
  done <"$1"
  exit 0
fi
if [ "$#" = 0 ]; then /usr/bin/shasum -a 256; exit; fi
for file in "$@"; do /usr/bin/shasum -a 256 "$file"; done
EOF
  cat >"$fake/mv" <<'EOF'
#!/usr/bin/env bash
args=()
for arg in "$@"; do case "$arg" in -T|-Tf|-fT|--) ;; *) args+=("$arg");; esac; done
[ "${FAKE_STATE_SWITCH_FAIL:-0}" != 1 ] \
  || case "${args[1]}" in */state/current) exit 1;; esac
[ ! -L "${args[1]}" ] || /bin/rm -f -- "${args[1]}"
/bin/mv -f -- "${args[0]}" "${args[1]}"
EOF
  cat >"$fake/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  cat >"$fake/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  cat >"$fake/cp" <<'EOF'
#!/usr/bin/env bash
/bin/cp "$@"
destination=${!#}
if [ "${FAKE_BUNDLE_COPY_TAMPER:-0}" = 1 ] \
    && [[ "$destination" == */.bundle/initial.dump ]]; then
  printf 'changed-after-source-validation\n' >>"$destination"
fi
EOF
  chmod +x "$fake/realpath" "$fake/sha256sum" "$fake/mv" "$fake/flock" "$fake/sleep" "$fake/cp"
}

write_restore_docker_fake() {
  local fake=$1
  cat >"$fake/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >>"$FAKE_DOCKER_LOG"
case " $* " in
  *' inspect --format {{.State.Status}} postgres-id '*) printf '%s\n' "$(cat "$FAKE_POSTGRES_STATUS")";;
  *' inspect '*'postgres-id'*)
    status=$(cat "$FAKE_POSTGRES_STATUS")
    health=none
    [ "$status" != running ] || health=healthy
    if [ "${FAKE_SELECTED_POSTGRES_HEALTH_FAIL:-0}" = 1 ] \
        && [ "$(readlink "$HAPPY_AGENT_ROOT/state/current")" != generations/initial-empty ]; then
      health=unhealthy
    fi
    printf 'postgres-id|happy-agent-postgres:test-release|%s|%s\n' "$status" "$health"
    ;;
  *' compose '*' config postgres '*) printf 'services:\n  postgres:\n    image: happy-agent-postgres:test-release\n';;
  *' compose -p happy-agent '*' ps -a -q postgres '*)
    if [ "${FAKE_NO_PRODUCTION_POSTGRES:-0}" != 1 ] || [ -e "$FAKE_POSTGRES_PRESENT" ]; then
      printf 'postgres-id\n'
    fi
    ;;
  *' compose '*' ps -q postgres '*) printf 'postgres-id\n';;
  *' compose '*' ps '*' postgres '*) printf 'postgres running healthy\n';;
  *' compose '*' exec '*' psql '*'assert-initial-empty-target.sql'*)
    if [ "${FAKE_INITIAL_BASELINE_FAIL:-0}" = 1 ]; then
      printf 'POLLUTED\n'
    else
      printf 'HAPPY_AGENT_INITIAL_TARGET_EMPTY\n'
    fi
    ;;
  *' compose '*' exec '*' psql '*'flyway_schema_history'*)
    if [ -n "${FAKE_RESTORE_VERIFY_VALUE:-}" ]; then printf '%s\n' "$FAKE_RESTORE_VERIFY_VALUE";
    elif [ "${FAKE_RESTORE_VERIFY_FAIL:-0}" = 1 ]; then printf '9|9|9|9\n'; else printf '1|1|4|1\n'; fi
    ;;
  *' compose '*' exec '*' psql '*'-Atqc'*)
    if [[ "$*" == *flyway_schema_history* ]]; then
      if [ -n "${FAKE_RESTORE_VERIFY_VALUE:-}" ]; then printf '%s\n' "$FAKE_RESTORE_VERIFY_VALUE";
      elif [ "${FAKE_RESTORE_VERIFY_FAIL:-0}" = 1 ]; then printf '9|9|9|9\n'; else printf '1|1|4|1\n'; fi
    else
      printf '0\n0\n0\n0\n0\n0\n2\n0\n1\n0\n0\n'
    fi
    ;;
  *' compose '*' exec '*' psql '*'-Atq'*)
    if [ -n "${FAKE_RESTORE_VERIFY_VALUE:-}" ]; then printf '%s\n' "$FAKE_RESTORE_VERIFY_VALUE";
    elif [ "${FAKE_RESTORE_VERIFY_FAIL:-0}" = 1 ]; then printf '9|9|9|9\n'; else printf '1|1|4|1\n'; fi
    ;;
  *' compose '*' exec '*' pg_restore '*) :;;
  *' compose '*' down '*) :;;
  *' compose -p happy-agent '*' stop postgres '*)
    if [ "${FAKE_POSTGRES_STOP_LIES:-0}" != 1 ] \
        && { [ "${FAKE_RECOVERY_POSTGRES_STOP_LIES:-0}" != 1 ] \
          || [ "$(readlink "$HAPPY_AGENT_ROOT/state/current")" = generations/initial-empty ]; }; then
      printf 'exited\n' >"$FAKE_POSTGRES_STATUS"
    fi
    ;;
  *' compose -p happy-agent '*' up '*' postgres '*)
    if [ "${FAKE_SELECTED_POSTGRES_START_FAIL:-0}" = 1 ] \
        && [ "$(readlink "$HAPPY_AGENT_ROOT/state/current")" != generations/initial-empty ]; then
      exit 1
    fi
    printf 'running\n' >"$FAKE_POSTGRES_STATUS"
    : >"$FAKE_POSTGRES_PRESENT"
    ;;
  *' compose '*' up '*) :;;
esac
EOF
  chmod +x "$fake/docker"
}

make_release() {
  local root=$1 id=$2 release="$1/releases/$2"
  mkdir -p "$release/postgres" "$release/images"
  cp "$ROOT_DIR/compose.yml" "$release/compose.yml"
  cp "$ROOT_DIR/postgres/init-roles.sh" "$release/postgres/init-roles.sh"
  cp "$ROOT_DIR/postgres/init-roles.sql" "$release/postgres/init-roles.sql"
  cp "$ROOT_DIR/postgres/enforce-isolation.sql" "$release/postgres/enforce-isolation.sql"
  [ ! -f "$ROOT_DIR/postgres/assert-initial-empty-target.sql" ] || cp "$ROOT_DIR/postgres/assert-initial-empty-target.sql" "$release/postgres/assert-initial-empty-target.sql"
  printf 'RELEASE_ID=%s\nAPP_IMAGE=app:%s\nWEB_IMAGE=web:%s\n' "$id" "$id" "$id" >"$release/.env"
  printf 'image\n' >"$release/images/$id.tar"
  printf 'server {}\n' >"$release/nginx.conf"
  (cd "$release" && find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | sed 's#^./##' | while IFS= read -r file; do sha256sum "$file"; done >SHA256SUMS)
}

make_bundle() {
  local target=$1 media_hash tree_hash key_hash
  mkdir -p "$target/media-source"
  printf 'dump\n' >"$target/initial.dump"
  printf 'media\n' >"$target/media-source/file"
  tar -C "$target/media-source" -cf "$target/media.tar" file
  printf 'master-key-fixture\000bytes' >"$target/agent-master-key"
  media_hash=$(sha256sum "$target/media.tar" | awk '{print $1}')
  tree_hash=$(cd "$target/media-source" && find . -type f -print | LC_ALL=C sort | while IFS= read -r file; do sha256sum "$file"; done | sha256sum | awk '{print $1}')
  key_hash=$(sha256sum "$target/agent-master-key" | awk '{print $1}')
  printf 'fitness_history_count=1\nagent_history_count=1\napplication_table_count=4\nkey_object_count=1\nmedia_sha256=%s\nmedia_tree_sha256=%s\nmaster_key_sha256=%s\n' \
    "$media_hash" "$tree_hash" "$key_hash" >"$target/metadata.env"
  rm -rf -- "$target/media-source"
  (cd "$target" && sha256sum initial.dump media.tar agent-master-key metadata.env >SHA256SUMS)
}

refresh_bundle_manifest() {
  local bundle=$1
  (cd "$bundle" && sha256sum initial.dump media.tar agent-master-key metadata.env >SHA256SUMS)
}

refresh_media_metadata() {
  local bundle=$1 source=$2 media_hash tree_hash
  media_hash=$(sha256sum "$bundle/media.tar" | awk '{print $1}')
  tree_hash=$(cd "$source" && find . -type f -print | LC_ALL=C sort \
    | while IFS= read -r file; do sha256sum "$file"; done \
    | sha256sum | awk '{print $1}')
  sed -e "s/^media_sha256=.*/media_sha256=$media_hash/" \
    -e "s/^media_tree_sha256=.*/media_tree_sha256=$tree_hash/" \
    "$bundle/metadata.env" >"$bundle/metadata.env.new"
  mv "$bundle/metadata.env.new" "$bundle/metadata.env"
  refresh_bundle_manifest "$bundle"
}

setup_restore_root() {
  local root=$1
  mkdir -p "$root/releases" "$root/state/generations/initial-empty/postgres" \
    "$root/state/generations/initial-empty/media" "$root/secrets" "$root/data/media"
  printf 'old-postgres-state\n' >"$root/state/generations/initial-empty/postgres/marker"
  printf 'old-media-state\n' >"$root/state/generations/initial-empty/media/marker"
  printf 'postgres-password\n' >"$root/secrets/postgres-password"
  printf 'fitness-password\n' >"$root/secrets/fitness-db-password"
  printf 'agent-password\n' >"$root/secrets/agent-db-password"
  chmod 0600 "$root/secrets"/*
  ln -s generations/initial-empty "$root/state/current"
  make_release "$root" test-release
  ln -s releases/test-release "$root/current"
}

assert_no_pending_generation() {
  local root=$1
  [ -z "$(find "$root/state/generations" -mindepth 1 -maxdepth 1 -name '.pending-*' -print -quit)" ] || fail 'restore leaked a pending generation'
}

assert_no_restore_runtime() {
  local root=$1
  [ -z "$(find "$root/state" -mindepth 1 -maxdepth 1 -name '.restore-runtime-*' -print -quit)" ] \
    || fail 'restore leaked a temporary runtime root'
}

assert_restore_preflight_rejected() {
  local label=$1 root=$2 timestamp=$3 bundle=$4
  shift 4
  export HAPPY_AGENT_ROOT=$root HAPPY_AGENT_TIMESTAMP=$timestamp
  printf 'running\n' >"$FAKE_POSTGRES_STATUS"
  : >"$FAKE_DOCKER_LOG"
  if [ -n "${REJECT_ENV_ASSIGNMENT:-}" ]; then
    if env "$REJECT_ENV_ASSIGNMENT" "$SCRIPTS/restore-initial-data.sh" "$bundle" "$@"; then
      fail "restore accepted $label"
    fi
  elif "$SCRIPTS/restore-initial-data.sh" "$bundle" "$@"; then
    fail "restore accepted $label"
  fi
  [ "$(readlink "$root/state/current")" = generations/initial-empty ] \
    || fail "$label changed state/current"
  ! grep -Fq ' pg_restore ' "$FAKE_DOCKER_LOG" || fail "$label reached pg_restore"
  assert_no_pending_generation "$root"
  assert_no_restore_runtime "$root"
  unset REJECT_ENV_ASSIGNMENT
}

run_restore_preflight_tests() {
  local bundle=$1 case_bundle case_root label timestamp=20260813T020000Z source

  case_root="$TMP/preflight-missing/root"
  setup_restore_root "$case_root"
  assert_restore_preflight_rejected missing-bundle "$case_root" "$timestamp" \
    "$TMP/preflight-missing/no-bundle" --initial-empty-target

  case_root="$TMP/preflight-flag/root"
  setup_restore_root "$case_root"
  assert_restore_preflight_rejected omitted-confirmation-flag "$case_root" 20260813T020001Z "$bundle"

  for label in checksum missing-manifest metadata-duplicate metadata-malformed metadata-unknown \
    media-hash media-tree-hash key-hash archive-symlink archive-hardlink archive-device archive-duplicate \
    archive-dot-alias archive-inner-dot-alias archive-traversal; do
    case_bundle="$TMP/preflight-$label/bundle"
    case_root="$TMP/preflight-$label/root"
    mkdir -p "$case_bundle"
    cp -R "$bundle/." "$case_bundle/"
    setup_restore_root "$case_root"
    case "$label" in
      checksum)
        printf 'tampered\n' >>"$case_bundle/initial.dump"
        ;;
      missing-manifest)
        sed '/ agent-master-key$/d' "$case_bundle/SHA256SUMS" >"$case_bundle/SHA256SUMS.new"
        mv "$case_bundle/SHA256SUMS.new" "$case_bundle/SHA256SUMS"
        ;;
      metadata-duplicate)
        printf 'fitness_history_count=1\n' >>"$case_bundle/metadata.env"
        refresh_bundle_manifest "$case_bundle"
        ;;
      metadata-malformed)
        printf 'malformed-line\n' >>"$case_bundle/metadata.env"
        refresh_bundle_manifest "$case_bundle"
        ;;
      metadata-unknown)
        printf 'unknown_count=1\n' >>"$case_bundle/metadata.env"
        refresh_bundle_manifest "$case_bundle"
        ;;
      media-hash)
        sed 's/^media_sha256=.*/media_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
          "$case_bundle/metadata.env" >"$case_bundle/metadata.env.new"
        mv "$case_bundle/metadata.env.new" "$case_bundle/metadata.env"
        refresh_bundle_manifest "$case_bundle"
        ;;
      media-tree-hash)
        sed 's/^media_tree_sha256=.*/media_tree_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
          "$case_bundle/metadata.env" >"$case_bundle/metadata.env.new"
        mv "$case_bundle/metadata.env.new" "$case_bundle/metadata.env"
        refresh_bundle_manifest "$case_bundle"
        ;;
      key-hash)
        sed 's/^master_key_sha256=.*/master_key_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
          "$case_bundle/metadata.env" >"$case_bundle/metadata.env.new"
        mv "$case_bundle/metadata.env.new" "$case_bundle/metadata.env"
        refresh_bundle_manifest "$case_bundle"
        ;;
      archive-symlink)
        source="$TMP/preflight-$label/source"; mkdir -p "$source"; printf 'x\n' >"$source/file"; ln -s file "$source/link"
        tar -C "$source" -cf "$case_bundle/media.tar" file link; refresh_bundle_manifest "$case_bundle"
        ;;
      archive-hardlink)
        source="$TMP/preflight-$label/source"; mkdir -p "$source"; printf 'x\n' >"$source/file"; ln "$source/file" "$source/alias"
        tar -C "$source" -cf "$case_bundle/media.tar" file alias; refresh_bundle_manifest "$case_bundle"
        ;;
      archive-device)
        tar -C / -cf "$case_bundle/media.tar" dev/null; refresh_bundle_manifest "$case_bundle"
        ;;
      archive-duplicate)
        source="$TMP/preflight-$label/source"; mkdir -p "$source"; printf 'x\n' >"$source/file"
        tar -C "$source" -cf "$case_bundle/media.tar" file
        tar -C "$source" -rf "$case_bundle/media.tar" file
        refresh_bundle_manifest "$case_bundle"
        ;;
      archive-dot-alias)
        source="$TMP/preflight-$label/source"; mkdir -p "$source"; printf 'media\n' >"$source/file"
        tar -C "$source" -cf "$case_bundle/media.tar" ./file
        tar -C "$source" -rf "$case_bundle/media.tar" file
        [ "$(tar -tf "$case_bundle/media.tar")" = $'./file\nfile' ] \
          || fail 'dot-alias archive fixture did not preserve distinct raw names'
        refresh_media_metadata "$case_bundle" "$source"
        ;;
      archive-inner-dot-alias)
        source="$TMP/preflight-$label/source"; mkdir -p "$source/dir"; printf 'media\n' >"$source/dir/asset"
        tar -C "$source" -cf "$case_bundle/media.tar" dir/./asset
        tar -C "$source" -rf "$case_bundle/media.tar" dir/asset
        [ "$(tar -tf "$case_bundle/media.tar")" = $'dir/./asset\ndir/asset' ] \
          || fail 'inner-dot-alias archive fixture did not preserve distinct raw names'
        refresh_media_metadata "$case_bundle" "$source"
        ;;
      archive-traversal)
        source="$TMP/preflight-$label/source"; mkdir -p "$source/inside"; printf 'x\n' >"$source/escape"
        tar -C "$source/inside" -cf "$case_bundle/media.tar" ../escape
        tar -tf "$case_bundle/media.tar" | grep -Fq '../escape' \
          || fail 'traversal archive fixture did not contain parent traversal'
        refresh_bundle_manifest "$case_bundle"
        ;;
    esac
    timestamp=$(printf '20260813T02%04dZ' $((10#${timestamp:11:4} + 1)))
    assert_restore_preflight_rejected "$label" "$case_root" "$timestamp" "$case_bundle" --initial-empty-target
    case "$label" in archive-dot-alias|archive-inner-dot-alias)
      [ ! -s "$FAKE_DOCKER_LOG" ] || fail "$label reached temporary PostgreSQL before rejection"
      ;;
    esac
  done

  case_root="$TMP/preflight-baseline/root"
  setup_restore_root "$case_root"
  REJECT_ENV_ASSIGNMENT=FAKE_INITIAL_BASELINE_FAIL=1
  assert_restore_preflight_rejected non-empty-target "$case_root" 20260813T020100Z "$bundle" --initial-empty-target

  case_root="$TMP/preflight-source-toctou/root"
  setup_restore_root "$case_root"
  REJECT_ENV_ASSIGNMENT=FAKE_BUNDLE_COPY_TAMPER=1
  assert_restore_preflight_rejected source-bundle-toctou "$case_root" 20260813T020101Z "$bundle" --initial-empty-target
  echo 'PASS: restore binding preflight rejection matrix'
}

run_restore_transaction_test() {
  local fake="$TMP/restore-fake" success_root="$TMP/success-root" failed_root="$TMP/failed-root"
  local start_failed_root="$TMP/start-failed-root" health_failed_root="$TMP/health-failed-root"
  local bundle="$TMP/bundle" selected old_postgres_hash old_media_hash forced_recreates
  write_coreutils_fakes "$fake"
  write_restore_docker_fake "$fake"
  export PATH="$fake:/usr/bin:/bin"
  export FAKE_DOCKER_LOG="$TMP/restore-docker.log"
  export FAKE_POSTGRES_STATUS="$TMP/restore-postgres.status"
  export FAKE_POSTGRES_PRESENT="$TMP/restore-postgres.present"
  printf 'running\n' >"$FAKE_POSTGRES_STATUS"
  export HAPPY_AGENT_TIMESTAMP=20260813T010203Z
  make_bundle "$bundle"
  : >"$FAKE_DOCKER_LOG"

  run_restore_preflight_tests "$bundle"
  export HAPPY_AGENT_TIMESTAMP=20260813T010203Z

  setup_restore_root "$success_root"
  export HAPPY_AGENT_ROOT="$success_root"
  export FAKE_NO_PRODUCTION_POSTGRES=1
  "$SCRIPTS/restore-initial-data.sh" "$bundle" --initial-empty-target
  unset FAKE_NO_PRODUCTION_POSTGRES
  selected=$(readlink "$success_root/state/current")
  [ "$selected" != generations/initial-empty ] || fail "restore did not atomically select a new state generation: $selected"
  case "$selected" in generations/*) ;; *) fail 'state/current is not a relative generation link';; esac
  [ -d "$success_root/state/current/postgres" ] || fail 'selected generation has no PostgreSQL directory'
  [ -f "$success_root/state/current/media/file" ] || fail 'selected generation has no restored media'
  cmp "$bundle/agent-master-key" "$success_root/state/current/agent-master-key" >/dev/null || fail 'selected generation master key differs from bundle bytes'
  assert_mode "$success_root/state/current/agent-master-key" 600
  [ -z "$(find "$success_root/data/media" -mindepth 1 -print -quit)" ] || fail 'restore changed legacy live media'
  [ ! -e "$success_root/secrets/agent-master-key" ] || fail 'restore wrote a master key outside the generation'
  assert_no_pending_generation "$success_root"
  grep -Eq 'docker compose -p happy-agent-restore-[A-Za-z0-9_.-]+ ' "$FAKE_DOCKER_LOG" || fail 'restore did not use a unique temporary Compose project'
  grep -Fq ' down --volumes --remove-orphans' "$FAKE_DOCKER_LOG" || fail 'restore did not remove its temporary Compose project'

  setup_restore_root "$failed_root"
  export HAPPY_AGENT_ROOT="$failed_root"
  export HAPPY_AGENT_TIMESTAMP=20260813T010204Z
  old_postgres_hash=$(sha256sum "$failed_root/state/current/postgres/marker" | awk '{print $1}')
  old_media_hash=$(sha256sum "$failed_root/state/current/media/marker" | awk '{print $1}')
  if FAKE_RESTORE_VERIFY_FAIL=1 "$SCRIPTS/restore-initial-data.sh" "$bundle" --initial-empty-target; then
    fail 'restore unexpectedly succeeded after database verification failure'
  fi
  [ "$(readlink "$failed_root/state/current")" = generations/initial-empty ] || fail 'failed restore changed state/current'
  [ "$(sha256sum "$failed_root/state/current/postgres/marker" | awk '{print $1}')" = "$old_postgres_hash" ] || fail 'failed restore changed selected PostgreSQL state'
  [ "$(sha256sum "$failed_root/state/current/media/marker" | awk '{print $1}')" = "$old_media_hash" ] || fail 'failed restore changed selected media state'
  assert_no_pending_generation "$failed_root"

  verify_index=0
  for verify_case in fitness-history agent-history table-count key-object-count; do
    verify_index=$((verify_index + 1))
    case "$verify_case" in
      fitness-history) verify_value='2|1|4|1';;
      agent-history) verify_value='1|2|4|1';;
      table-count) verify_value='1|1|5|1';;
      key-object-count) verify_value='1|1|4|2';;
    esac
    verify_root="$TMP/verify-$verify_case/root"
    setup_restore_root "$verify_root"
    export HAPPY_AGENT_ROOT="$verify_root"
    export HAPPY_AGENT_TIMESTAMP=$(printf '20260813T0102%02dZ' "$verify_index")
    : >"$FAKE_DOCKER_LOG"
    if FAKE_RESTORE_VERIFY_VALUE="$verify_value" \
        "$SCRIPTS/restore-initial-data.sh" "$bundle" --initial-empty-target; then
      fail "restore accepted a $verify_case mismatch"
    fi
    [ "$(readlink "$verify_root/state/current")" = generations/initial-empty ] \
      || fail "$verify_case mismatch changed state/current"
    grep -Fq ' pg_restore ' "$FAKE_DOCKER_LOG" \
      || fail "$verify_case mismatch did not exercise post-import validation"
    assert_no_pending_generation "$verify_root"
    assert_no_restore_runtime "$verify_root"
  done

  for failure in start health switch stop; do
    case "$failure" in
      start) recovery_root=$start_failed_root; failure_env=FAKE_SELECTED_POSTGRES_START_FAIL;;
      health) recovery_root=$health_failed_root; failure_env=FAKE_SELECTED_POSTGRES_HEALTH_FAIL;;
      switch) recovery_root="$TMP/switch-failed-root"; failure_env=FAKE_STATE_SWITCH_FAIL;;
      stop) recovery_root="$TMP/stop-failed-root"; failure_env=FAKE_POSTGRES_STOP_LIES;;
    esac
    setup_restore_root "$recovery_root"
    export HAPPY_AGENT_ROOT="$recovery_root"
    export HAPPY_AGENT_TIMESTAMP=20260813T010205Z
    printf 'running\n' >"$FAKE_POSTGRES_STATUS"
    : >"$FAKE_DOCKER_LOG"
    if env "$failure_env=1" "$SCRIPTS/restore-initial-data.sh" "$bundle" --initial-empty-target; then
      fail "restore unexpectedly succeeded after selected PostgreSQL $failure failure"
    fi
    [ "$(readlink "$recovery_root/state/current")" = generations/initial-empty ] \
      || fail "$failure failure did not restore the previous generation"
    [ "$(cat "$FAKE_POSTGRES_STATUS")" = running ] \
      || fail "$failure failure did not restart the previous PostgreSQL"
    forced_recreates=$({ grep -F 'compose -p happy-agent ' "$FAKE_DOCKER_LOG" \
      | grep -F -- ' up -d --no-deps --force-recreate postgres' || true; } | wc -l | tr -d ' ')
    expected_recreates=2
    case "$failure" in switch) expected_recreates=1;; stop) expected_recreates=0;; esac
    [ "$forced_recreates" = "$expected_recreates" ] \
      || fail "$failure recovery performed $forced_recreates PostgreSQL recreates, expected $expected_recreates"
    grep -F '{{.State.Status}}' "$FAKE_DOCKER_LOG" >/dev/null \
      || fail "$failure path switched generations without confirming PostgreSQL stopped"
    assert_no_pending_generation "$recovery_root"
  done

  recovery_root="$TMP/recovery-stop-failed-root"
  recovery_output="$TMP/recovery-stop-failed.output"
  setup_restore_root "$recovery_root"
  export HAPPY_AGENT_ROOT="$recovery_root" HAPPY_AGENT_TIMESTAMP=20260813T010206Z
  printf 'running\n' >"$FAKE_POSTGRES_STATUS"
  if FAKE_SELECTED_POSTGRES_HEALTH_FAIL=1 FAKE_RECOVERY_POSTGRES_STOP_LIES=1 \
      "$SCRIPTS/restore-initial-data.sh" "$bundle" --initial-empty-target \
      >"$recovery_output" 2>&1; then
    fail 'restore succeeded when previous-state recovery could not stop the selected PostgreSQL'
  fi
  [ "$(readlink "$recovery_root/state/current")" != generations/initial-empty ] \
    || fail 'unsafe recovery changed state/current before PostgreSQL stopped'
  [ -d "$recovery_root/state/current/postgres" ] \
    || fail 'failed recovery deleted the still-selected state generation'
  grep -Fq 'previous state recovery failed' "$recovery_output" \
    || fail 'failed recovery was not reported explicitly'
  assert_no_pending_generation "$recovery_root"
  echo 'PASS: state-generation restore transaction'
}

case "$CASE" in
  baseline) run_baseline_test;;
  transaction) run_restore_transaction_test;;
  all) run_baseline_test; docker rm -f "$CONTAINER" >/dev/null; CONTAINER_STARTED=0; run_restore_transaction_test;;
  *) fail "unknown test case: $CASE";;
esac
