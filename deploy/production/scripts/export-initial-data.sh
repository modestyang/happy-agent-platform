#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PRODUCTION_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
REPOSITORY_ROOT=$(cd "$PRODUCTION_ROOT/../.." && pwd)
ARTIFACT_ROOT="$REPOSITORY_ROOT/deploy/.local/production"
MIGRATION_ROOT="$ARTIFACT_ROOT/migrations"

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "required command unavailable: $1"; }
file_mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }

for command_name in docker git kill lsof node ps realpath sha256sum tar; do
  require_command "$command_name"
done

source_root_raw=${SOURCE_STATE_ROOT:-$REPOSITORY_ROOT}
case "$source_root_raw" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die 'SOURCE_STATE_ROOT must be a safe absolute path';; esac
[ -d "$source_root_raw" ] && [ ! -L "$source_root_raw" ] \
  || die 'SOURCE_STATE_ROOT must be a non-symlink repository directory'
source_root=$(realpath "$source_root_raw")
[ -e "$source_root/.git" ] && [ ! -L "$source_root/.git" ] \
  || die 'SOURCE_STATE_ROOT is not a repository root'
[ -f "$source_root/deploy/docker-compose.yml" ] \
  && [ ! -L "$source_root/deploy/docker-compose.yml" ] \
  || die 'source Compose file is missing or indirect'
[ -d "$source_root/deploy/.local" ] && [ ! -L "$source_root/deploy/.local" ] \
  || die 'source local state directory is missing or indirect'
master_key="$source_root/deploy/secrets/agent-master-key"
[ ! -L "$master_key" ] && [ -f "$master_key" ] && [ -r "$master_key" ] && [ -s "$master_key" ] \
  || die 'source Agent master key is missing, empty, or indirect'

validate_smoke_file() {
  local raw=$1 label=$2 canonical mode
  case "$raw" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die "$label path must be a safe absolute path";; esac
  [ ! -L "$raw" ] && [ -f "$raw" ] && [ -r "$raw" ] \
    || die "$label must be a non-symlink regular file"
  mode=$(file_mode "$raw")
  [ "$mode" = 600 ] || die "$label must have mode 0600"
  canonical=$(realpath "$raw")
  [ -f "$canonical" ] && [ ! -L "$canonical" ] || die "$label canonical file is unsafe"
  printf '%s\n' "$canonical"
}

session_file=$(validate_smoke_file \
  "${PUBLIC_SMOKE_SESSION_FILE:-$source_root/deploy/.local/production/public-smoke-session}" \
  'public smoke session')
run_id_file=$(validate_smoke_file \
  "${PUBLIC_SMOKE_RUN_ID_FILE:-$source_root/deploy/.local/production/public-smoke-run-id}" \
  'public smoke run id')
session=$(<"$session_file")
run_id=$(<"$run_id_file")
[[ "$session" =~ ^[a-f0-9]{64}$ ]] || die 'public smoke session has an invalid format'
[[ "$run_id" =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ ]] \
  || die 'public smoke run id has an invalid format'
session_sha256=$(printf '%s' "$session" | sha256sum | awk '{print $1}')
unset session
[[ "$session_sha256" =~ ^[a-f0-9]{64}$ ]] || die 'unable to hash public smoke session'

compose_file="$source_root/deploy/docker-compose.yml"
smoke_sql=$(printf "%s\n" \
  '/* happy-agent-smoke-validation */' \
  'SELECT count(*)' \
  'FROM fitness.fitness_sessions session_entry' \
  'JOIN agent.agent_runs run_entry ON run_entry.user_id = session_entry.user_id' \
  "WHERE session_entry.session_token_hash = '$session_sha256'" \
  '  AND session_entry.expires_at > CURRENT_TIMESTAMP' \
  "  AND run_entry.run_id = '$run_id'::uuid;")
smoke_match=$(printf '%s\n' "$smoke_sql" \
  | docker compose -f "$compose_file" exec -T postgres \
      psql -XAtq -v ON_ERROR_STOP=1 --set=happy_agent_operation=happy-agent-smoke-validation \
        -U postgres -d happy_agent -f -)
unset smoke_sql session_sha256 run_id
[ "$smoke_match" = 1 ] \
  || die 'public smoke session/run ownership or expiry validation failed'

listener_pids() {
  local output status
  set +e
  output=$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null)
  status=$?
  set -e
  case "$status" in 0) printf '%s\n' "$output" | sed '/^$/d' | LC_ALL=C sort -u;; 1) :;; *) return "$status";; esac
}

listeners=$(listener_pids) || die 'unable to inspect listeners on port 8080'
if [ -n "$listeners" ]; then
  while IFS= read -r pid; do
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || die 'lsof returned an unsafe listener PID'
    cwd=$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p') \
      || die 'unable to inspect listener cwd'
    [ -d "$cwd" ] && [ ! -L "$cwd" ] || die 'port 8080 listener cwd is unsafe'
    cwd=$(realpath "$cwd")
    command_line=$(ps -p "$pid" -o command=) || die 'unable to inspect listener command'
    cwd_belongs=0
    command_belongs=0
    case "$cwd" in "$source_root"|"$source_root"/*) cwd_belongs=1;; esac
    [[ "$command_line" =~ (^|[[:space:]])([^[:space:]]*/)?java([[:space:]]|$) ]] \
      || die 'port 8080 listener is not a Java backend'
    case "$command_line" in
      *java*"$source_root"/starter/target/starter-*-exec.jar* \
        |*java*"$source_root_raw"/starter/target/starter-*-exec.jar*) command_belongs=1;;
    esac
    [ "$cwd_belongs" = 1 ] || [ "$command_belongs" = 1 ] \
      || die 'port 8080 listener does not belong to SOURCE_STATE_ROOT'
    case "$command_line" in *starter-*-exec.jar*) ;; *) die 'port 8080 listener is not the starter backend';; esac
  done <<<"$listeners"
  while IFS= read -r pid; do kill -TERM "$pid"; done <<<"$listeners"
  for ((attempt = 1; attempt <= 30; attempt++)); do
    remaining=$(listener_pids) || die 'unable to recheck listeners on port 8080'
    [ -n "$remaining" ] || break
    sleep 1
  done
  [ -z "${remaining:-}" ] || die 'source backend did not release port 8080'
fi
unset listeners

postgres_container=$(docker compose -f "$compose_file" ps -q postgres)
postgres_container_count=$(printf '%s\n' "$postgres_container" | sed '/^$/d' | wc -l | tr -d ' ')
[ "$postgres_container_count" = 1 ] || die 'expected exactly one running source PostgreSQL container'
[[ "$postgres_container" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] \
  || die 'source PostgreSQL container id is unsafe'
postgres_server_version=$(docker compose -f "$compose_file" exec -T postgres \
  psql -XAtq -v ON_ERROR_STOP=1 -U postgres -d happy_agent -c 'SHOW server_version;')
[[ "$postgres_server_version" =~ ^16([.][0-9]+)+([[:space:]].*)?$ ]] \
  || die 'source PostgreSQL server major must be 16'
dump_version_output=$(docker compose -f "$compose_file" exec -T postgres pg_dump --version)
[[ "$dump_version_output" =~ ^pg_dump[[:space:]]\(PostgreSQL\)[[:space:]]16([.][0-9]+)+ ]] \
  || die 'source pg_dump major must be 16'
postgres_dump_version=$(printf '%s\n' "$dump_version_output" | sed -E 's/^pg_dump \(PostgreSQL\) ([^ ]+).*$/\1/')

timestamp=${HAPPY_AGENT_EXPORT_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}
[[ "$timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || die 'invalid injected export timestamp'
source_commit=$(git -C "$source_root" rev-parse HEAD)
source_short=$(git -C "$source_root" rev-parse --short HEAD)
[[ "$source_commit" =~ ^[a-f0-9]{40}$ ]] && [[ "$source_short" =~ ^[a-f0-9]{7,40}$ ]] \
  || die 'invalid source repository commit'
bundle_id="initial-$timestamp-$source_short"
umask 077
install -d -m 0700 "$ARTIFACT_ROOT" "$MIGRATION_ROOT"
pending="$MIGRATION_ROOT/.pending-$bundle_id"
complete="$MIGRATION_ROOT/$bundle_id"
[ ! -e "$pending" ] && [ ! -e "$complete" ] || die 'migration bundle id already exists'
install -d -m 0700 "$pending"
cleanup_export() {
  local cleanup_status=$?
  if [ -d "$pending" ]; then /bin/rm -rf -- "$pending"; fi
  return "$cleanup_status"
}
trap cleanup_export EXIT

docker compose -f "$compose_file" exec -T postgres \
  psql -Xq -v ON_ERROR_STOP=1 -U postgres -d happy_agent -c 'CHECKPOINT;' >/dev/null
validation_sql=$(printf '%s\n' \
  '/* happy-agent-source-validation */' \
  "SELECT 'postgres_server_version=' || current_setting('server_version')" \
  "UNION ALL SELECT 'fitness_history_count=' || count(*) FROM fitness.flyway_schema_history" \
  "UNION ALL SELECT 'agent_history_count=' || count(*) FROM agent.flyway_schema_history" \
  "UNION ALL SELECT 'application_table_count=' || count(*) FROM pg_tables WHERE schemaname IN ('fitness','agent')" \
  "UNION ALL SELECT 'key_object_count=' || count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('fitness','agent')" \
  "UNION ALL SELECT 'fitness_schema_count=' || count(*) FROM pg_namespace WHERE nspname='fitness'" \
  "UNION ALL SELECT 'agent_schema_count=' || count(*) FROM pg_namespace WHERE nspname='agent'" \
  "UNION ALL SELECT 'fitness_table_count=' || count(*) FROM pg_tables WHERE schemaname='fitness'" \
  "UNION ALL SELECT 'agent_table_count=' || count(*) FROM pg_tables WHERE schemaname='agent'" \
  "UNION ALL SELECT 'fitness_history_checksums=' || coalesce(string_agg(checksum::text, ',' ORDER BY installed_rank), '') FROM fitness.flyway_schema_history" \
  "UNION ALL SELECT 'agent_history_checksums=' || coalesce(string_agg(checksum::text, ',' ORDER BY installed_rank), '') FROM agent.flyway_schema_history" \
  "UNION ALL SELECT 'fitness_user_count=' || count(*) FROM fitness.users" \
  "UNION ALL SELECT 'agent_run_count=' || count(*) FROM agent.agent_runs;")
validation_lines=$(printf '%s\n' "$validation_sql" \
  | docker compose -f "$compose_file" exec -T postgres \
      psql -XAtq -v ON_ERROR_STOP=1 --set=happy_agent_operation=happy-agent-source-validation \
        -U postgres -d happy_agent -f -)
unset validation_sql

validation_value() {
  local key=$1 value count
  count=$(printf '%s\n' "$validation_lines" | grep -Ec "^${key}=")
  [ "$count" = 1 ] || die "source validation key is missing or duplicated: $key"
  value=$(printf '%s\n' "$validation_lines" | sed -n "s/^${key}=//p")
  printf '%s\n' "$value"
}
for count_key in fitness_history_count agent_history_count application_table_count key_object_count \
  fitness_schema_count agent_schema_count fitness_table_count agent_table_count fitness_user_count \
  agent_run_count; do
  count_value=$(validation_value "$count_key")
  [[ "$count_value" =~ ^[0-9]+$ ]] || die "invalid source validation count: $count_key"
  printf -v "$count_key" '%s' "$count_value"
done
fitness_history_checksums=$(validation_value fitness_history_checksums)
agent_history_checksums=$(validation_value agent_history_checksums)
[[ "$fitness_history_checksums" =~ ^$|^-?[0-9]+(,-?[0-9]+)*$ ]] \
  || die 'invalid fitness Flyway checksum inventory'
[[ "$agent_history_checksums" =~ ^$|^-?[0-9]+(,-?[0-9]+)*$ ]] \
  || die 'invalid Agent Flyway checksum inventory'

docker compose -f "$compose_file" exec -T postgres \
  pg_dump --format=custom --dbname=happy_agent --username=postgres >"$pending/initial.dump"
[ -s "$pending/initial.dump" ] || die 'source database dump is empty'

media_root="$source_root/deploy/.local/media"
media_members=()
if [ -e "$media_root" ]; then
  [ -d "$media_root" ] && [ ! -L "$media_root" ] || die 'source media root is unsafe'
  [ -z "$(find "$media_root" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
    || die 'source media contains a link or special member'
  while IFS= read -r -d '' media_member; do
    media_member=${media_member#./}
    case "$media_member" in ''|/*|*'\'*|*'|'*|*$'\n'*|*$'\r'*|..|../*|*/../*|*/..) \
      die 'source media path is not canonically representable';; esac
    media_members+=("$media_member")
  done < <(cd "$media_root" && find . -type f -print0)
  if [ "${#media_members[@]}" -gt 0 ]; then
    sorted_media=()
    while IFS= read -r media_member; do sorted_media+=("$media_member"); done \
      < <(printf '%s\n' "${media_members[@]}" | LC_ALL=C sort)
    COPYFILE_DISABLE=1 tar -C "$media_root" -cf "$pending/media.tar" -- "${sorted_media[@]}"
  else
    tar -C "$media_root" -cf "$pending/media.tar" -T /dev/null
  fi
  media_tree_sha256=$(
    cd "$media_root"
    find . -type f -print | LC_ALL=C sort \
      | while IFS= read -r media_member; do sha256sum "$media_member"; done \
      | sha256sum | awk '{print $1}'
  )
else
  empty_media="$pending/.empty-media"
  install -d -m 0700 "$empty_media"
  tar -C "$empty_media" -cf "$pending/media.tar" -T /dev/null
  media_tree_sha256=$(printf '' | sha256sum | awk '{print $1}')
  rmdir "$empty_media"
fi
cp -- "$master_key" "$pending/agent-master-key"
cmp -- "$master_key" "$pending/agent-master-key" >/dev/null \
  || die 'exported Agent master key differs from source bytes'
media_sha256=$(sha256sum "$pending/media.tar" | awk '{print $1}')
master_key_sha256=$(sha256sum "$pending/agent-master-key" | awk '{print $1}')

cat >"$pending/metadata.env" <<EOF
fitness_history_count=$fitness_history_count
agent_history_count=$agent_history_count
application_table_count=$application_table_count
key_object_count=$key_object_count
media_sha256=$media_sha256
media_tree_sha256=$media_tree_sha256
master_key_sha256=$master_key_sha256
EOF

source_status=$(git -C "$source_root" status --porcelain=v1 --untracked-files=all)
if [ -n "$source_status" ]; then source_dirty=true; else source_dirty=false; fi
source_diff_sha256=$(git -C "$source_root" diff --binary HEAD | sha256sum | awk '{print $1}')
source_status_inventory_sha256=$(printf '%s\n' "$source_status" | sha256sum | awk '{print $1}')
node - "$pending/source-validation.json" "$postgres_server_version" "$postgres_dump_version" \
  "$source_commit" "$source_dirty" "$source_diff_sha256" "$source_status_inventory_sha256" \
  "$fitness_schema_count" "$agent_schema_count" "$fitness_table_count" "$agent_table_count" \
  "$fitness_history_count" "$agent_history_count" "$fitness_history_checksums" \
  "$agent_history_checksums" "$application_table_count" "$key_object_count" \
  "$media_sha256" "$media_tree_sha256" "$master_key_sha256" "$fitness_user_count" \
  "$agent_run_count" <<'NODE'
const fs = require('fs');
const [output, serverVersion, dumpVersion, commit, dirty, diffSha256, statusInventorySha256,
  fitnessSchemaCount, agentSchemaCount, fitnessTableCount, agentTableCount,
  fitnessHistoryCount, agentHistoryCount, fitnessHistoryChecksums, agentHistoryChecksums,
  applicationTableCount, keyObjectCount, mediaSha256, mediaTreeSha256, masterKeySha256,
  fitnessUserCount, agentRunCount] = process.argv.slice(2);
const integer = value => Number.parseInt(value, 10);
const checksumList = value => value ? value.split(',').map(integer) : [];
const metadata = {
  postgres: {serverVersion, dumpVersion},
  source: {commit, dirty: dirty === 'true', diffSha256, statusInventorySha256},
  schemas: {
    fitness: {schemaCount: integer(fitnessSchemaCount), tableCount: integer(fitnessTableCount),
      flywayCount: integer(fitnessHistoryCount), flywayChecksums: checksumList(fitnessHistoryChecksums)},
    agent: {schemaCount: integer(agentSchemaCount), tableCount: integer(agentTableCount),
      flywayCount: integer(agentHistoryCount), flywayChecksums: checksumList(agentHistoryChecksums)}
  },
  task4: {applicationTableCount: integer(applicationTableCount), keyObjectCount: integer(keyObjectCount)},
  media: {archiveSha256: mediaSha256, treeSha256: mediaTreeSha256},
  masterKeySha256,
  criticalCounts: {fitnessUserCount: integer(fitnessUserCount), agentRunCount: integer(agentRunCount)}
};
fs.writeFileSync(output, `${JSON.stringify(metadata, null, 2)}\n`, {mode: 0o600});
NODE

find "$pending" -type f -exec chmod 0600 {} +
(
  cd "$pending"
  find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | sed 's#^./##' \
    | while IFS= read -r bundle_file; do sha256sum "$bundle_file"; done >SHA256SUMS
)
chmod 0600 "$pending/SHA256SUMS"
[ -z "$(find "$pending" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
  || die 'migration bundle contains a link or special member'
(cd "$pending" && sha256sum --check --strict SHA256SUMS >/dev/null) \
  || die 'migration bundle checksum verification failed'
mv -- "$pending" "$complete"
pending=''
trap - EXIT
log "initial migration bundle exported: $bundle_id"
printf '%s\n' "$complete"
