#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

REPOSITORY_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
PRODUCTION_ROOT="$REPOSITORY_ROOT/deploy/production"
SOURCE_STATE_ROOT=${SOURCE_STATE_ROOT:-/Users/modest/IdeaProjects/happy-agent-platform}
REAL_DOCKER=$(command -v docker)
TMP_PARENT=${TMPDIR:-/tmp}
TMP=$(mktemp -d "${TMP_PARENT%/}/happy-agent-task6-rehearsal.XXXXXX")
case "$TMP" in
  "${TMP_PARENT%/}"/happy-agent-task6-rehearsal.*) ;;
  *) echo 'FAIL: unsafe rehearsal temporary directory' >&2; exit 1;;
esac
TOKEN=$(basename "$TMP" | tr -cd 'a-zA-Z0-9' | tail -c 16 | tr '[:upper:]' '[:lower:]')
PROJECT="happy-agent-rehearsal-$TOKEN"
RELEASE_ID="20990101T000000Z-${TOKEN}"
GOOD_RELEASE="$TMP/root/releases/$RELEASE_ID"
BAD_RELEASE_ID="20990101T000001Z-${TOKEN}"
BAD_RELEASE="$TMP/root/releases/$BAD_RELEASE_ID"
ROOT="$TMP/root"
APP_IMAGE="happy-agent-task6-app:$TOKEN"
WEB_IMAGE="happy-agent-task6-web:$TOKEN"
POSTGRES_IMAGE="happy-agent-postgres:$RELEASE_ID"
WEB_PROBE="happy-agent-task6-web-$TOKEN"
CREATED_IMAGES=()
SUCCESS=0

fail() { echo "FAIL: $*" >&2; exit 1; }
log() { printf 'REHEARSAL: %s\n' "$*" >&2; }

cleanup() {
  local status=$?
  set +e
  "$REAL_DOCKER" rm -f "$WEB_PROBE" >/dev/null 2>&1
  "$REAL_DOCKER" compose -p "$PROJECT" -f "$GOOD_RELEASE/compose.yml" \
    --env-file "$GOOD_RELEASE/.env" down --volumes --remove-orphans >/dev/null 2>&1
  for image in "${CREATED_IMAGES[@]}"; do
    "$REAL_DOCKER" image rm "$image" >/dev/null 2>&1
  done
  case "$TMP" in
    "${TMP_PARENT%/}"/happy-agent-task6-rehearsal.*)
      [ "$(id -u)" = "$(stat -c %u "$TMP" 2>/dev/null || stat -f %u "$TMP")" ] \
        && /bin/rm -rf -- "$TMP"
      ;;
  esac
  if "$REAL_DOCKER" ps -a --format '{{.Names}}' | grep -Fq "$PROJECT" \
      || "$REAL_DOCKER" network ls --format '{{.Name}}' | grep -Fq "$PROJECT" \
      || [ -e "$TMP" ]; then
    echo 'FAIL: rehearsal cleanup left owned resources behind' >&2
    exit 1
  fi
  if [ "$SUCCESS" = 1 ] && [ "$status" = 0 ]; then
    echo 'PASS: disposable production migration rehearsal and scoped cleanup'
  else
    exit "$status"
  fi
}
trap cleanup EXIT INT TERM

for command_name in docker git java javac openssl realpath shasum tar; do
  command -v "$command_name" >/dev/null 2>&1 || fail "required command unavailable: $command_name"
done
case "$SOURCE_STATE_ROOT" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) fail 'unsafe SOURCE_STATE_ROOT';; esac
[ -d "$SOURCE_STATE_ROOT" ] && [ ! -L "$SOURCE_STATE_ROOT" ] \
  || fail 'SOURCE_STATE_ROOT must be a non-symlink directory'
SOURCE_STATE_ROOT=$(realpath "$SOURCE_STATE_ROOT")
[ "$SOURCE_STATE_ROOT" != "$REPOSITORY_ROOT" ] || fail 'source state must not be the rehearsal worktree'
[ -f "$SOURCE_STATE_ROOT/deploy/docker-compose.yml" ] \
  && [ ! -L "$SOURCE_STATE_ROOT/deploy/docker-compose.yml" ] \
  || fail 'source Compose contract is unavailable'
[ -f "$SOURCE_STATE_ROOT/deploy/.local/compose.env" ] \
  && [ ! -L "$SOURCE_STATE_ROOT/deploy/.local/compose.env" ] \
  || fail 'source Compose environment is unavailable or indirect'
[ -f "$SOURCE_STATE_ROOT/deploy/secrets/agent-master-key" ] \
  && [ ! -L "$SOURCE_STATE_ROOT/deploy/secrets/agent-master-key" ] \
  || fail 'source Agent master key is unavailable or indirect'

mkdir -p "$TMP/bin"
cat >"$TMP/bin/flock" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$#" = 2 ] && [ "$1" = -x ] && [[ "$2" =~ ^[0-9]+$ ]]
EOF
cat >"$TMP/bin/mv" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  -T|-Tf)
    shift
    [ "${1:-}" != -- ] || shift
    [ "$#" = 2 ] || exit 2
    python3 - "$1" "$2" <<'PY'
import os
import sys
os.replace(sys.argv[1], sys.argv[2])
PY
    ;;
  *) exec /bin/mv "$@";;
esac
EOF
cat >"$TMP/bin/realpath" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "${1:-}" != -m ] || shift
[ "${1:-}" != -- ] || shift
[ "$#" = 1 ] || exit 2
python3 - "$1" <<'PY'
import os
import sys
print(os.path.realpath(sys.argv[1]))
PY
EOF
cat >"$TMP/bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ "${1:-}" = --check ]; then
  shift
  [ "${1:-}" != --strict ] || shift
  manifest=$1
  while IFS= read -r line || [ -n "$line" ]; do
    expected=${line%% *}
    file=${line#* }
    file=${file# }
    file=${file#\*}
    [ "$(shasum -a 256 "$file" | awk '{print $1}')" = "$expected" ] || exit 1
  done <"$manifest"
  exit 0
fi
if [ "$#" = 0 ]; then exec shasum -a 256; fi
exec shasum -a 256 "$@"
EOF
cat >"$TMP/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ "${1:-}" = compose ]; then
  args=("$@")
  for ((index = 1; index < ${#args[@]}; index++)); do
    if [ "${args[$index]}" = -p ] && [ "${args[$((index + 1))]:-}" = happy-agent ]; then
      args[$((index + 1))]=$REHEARSAL_PROJECT
    fi
  done
  exec "$REAL_DOCKER" "${args[@]}"
fi
exec "$REAL_DOCKER" "$@"
EOF
chmod 0700 "$TMP/bin/flock" "$TMP/bin/mv" "$TMP/bin/realpath" "$TMP/bin/sha256sum" \
  "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH" REAL_DOCKER REHEARSAL_PROJECT="$PROJECT"

sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
media_tree_hash() {
  (
    cd "$1"
    find . -type f -print | LC_ALL=C sort \
      | while IFS= read -r file; do shasum -a 256 "$file"; done \
      | shasum -a 256 | awk '{print $1}'
  )
}
write_manifest() {
  local directory=$1
  (
    cd "$directory"
    find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | sed 's#^./##' \
      | while IFS= read -r file; do printf '%s  %s\n' "$(sha256_file "$file")" "$file"; done \
      >SHA256SUMS
  )
  chmod 0600 "$directory/SHA256SUMS"
}
compose() {
  HAPPY_AGENT_ROOT="$ROOT" docker compose -p happy-agent --env-file "$GOOD_RELEASE/.env" \
    -f "$GOOD_RELEASE/compose.yml" "$@"
}
wait_healthy() {
  local service=$1 attempt state
  for attempt in $(seq 1 90); do
    state=$(compose ps --format '{{.Service}} {{.State}} {{.Health}}' "$service" 2>/dev/null || true)
    [ "$state" = "$service running healthy" ] && return 0
    sleep 1
  done
  compose ps >&2 || true
  return 1
}
wait_initialized_postgres() {
  local attempt container_id
  for attempt in $(seq 1 120); do
    container_id=$(compose ps -q postgres 2>/dev/null || true)
    if [ -n "$container_id" ] \
        && docker logs "$container_id" 2>&1 \
          | grep -Fq 'PostgreSQL init process complete; ready for start up.' \
        && wait_healthy postgres; then
      return 0
    fi
    sleep 1
  done
  return 1
}
source_psql() {
  "$REAL_DOCKER" compose --env-file "$SOURCE_STATE_ROOT/deploy/.local/compose.env" \
    -f "$SOURCE_STATE_ROOT/deploy/docker-compose.yml" exec -T postgres \
    psql -XAtq -v ON_ERROR_STOP=1 -U postgres -d happy_agent "$@"
}
target_psql() { compose exec -T postgres psql -XAtq -v ON_ERROR_STOP=1 -U postgres -d happy_agent "$@"; }

log 'build exact production images on the trusted local builder'
[ -f "$REPOSITORY_ROOT/starter/target/starter-0.0.1-SNAPSHOT-exec.jar" ] \
  || (cd "$REPOSITORY_ROOT" && ./mvnw -DskipTests -pl starter -am package >/dev/null)
[ -f "$REPOSITORY_ROOT/frontend/dist/index.html" ] \
  || (cd "$REPOSITORY_ROOT" && npm --prefix frontend run build >/dev/null)
mkdir -p "$TMP/build/app/deploy/production" "$TMP/build/app/starter/target" \
  "$TMP/build/web/frontend/dist" "$TMP/build/postgres"
cp "$PRODUCTION_ROOT/app-entrypoint.sh" "$TMP/build/app/deploy/production/app-entrypoint.sh"
cp "$REPOSITORY_ROOT/starter/target/starter-0.0.1-SNAPSHOT-exec.jar" \
  "$TMP/build/app/starter/target/starter-0.0.1-SNAPSHOT-exec.jar"
cp -R "$REPOSITORY_ROOT/frontend/dist/." "$TMP/build/web/frontend/dist/"
sed 's#^FROM postgres:16.14-alpine3.24#FROM postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777#' \
  "$REPOSITORY_ROOT/deploy/postgres/Dockerfile" >"$TMP/build/postgres.Dockerfile"
docker build -f "$PRODUCTION_ROOT/app.Dockerfile" -t "$APP_IMAGE" "$TMP/build/app" >/dev/null
CREATED_IMAGES+=("$APP_IMAGE")
docker build -f "$PRODUCTION_ROOT/web.Dockerfile" -t "$WEB_IMAGE" "$TMP/build/web" >/dev/null
CREATED_IMAGES+=("$WEB_IMAGE")
docker build -f "$TMP/build/postgres.Dockerfile" -t "$POSTGRES_IMAGE" \
  "$TMP/build/postgres" >/dev/null
CREATED_IMAGES+=("$POSTGRES_IMAGE")

log 'create a closed migration bundle from the explicit read-only source state'
SOURCE_CONTAINER=$("$REAL_DOCKER" compose \
  --env-file "$SOURCE_STATE_ROOT/deploy/.local/compose.env" \
  -f "$SOURCE_STATE_ROOT/deploy/docker-compose.yml" ps -q postgres)
[ -n "$SOURCE_CONTAINER" ] || fail 'source PostgreSQL is not running'
BUNDLE="$TMP/migration"
mkdir -p "$BUNDLE"
"$REAL_DOCKER" compose --env-file "$SOURCE_STATE_ROOT/deploy/.local/compose.env" \
  -f "$SOURCE_STATE_ROOT/deploy/docker-compose.yml" exec -T postgres \
  pg_dump --format=custom --dbname=happy_agent --username=postgres >"$BUNDLE/initial.dump"
[ -s "$BUNDLE/initial.dump" ] || fail 'source custom dump is empty'
fitness_history_count=$(source_psql -c 'SELECT count(*) FROM fitness.fitness_schema_history;')
agent_history_count=$(source_psql -c 'SELECT count(*) FROM agent.agent_schema_history;')
application_table_count=$(source_psql -c "SELECT count(*) FROM pg_tables WHERE schemaname IN ('fitness','agent');")
key_object_count=$(source_psql -c "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('fitness','agent');")
fitness_history=$(source_psql -c "SELECT coalesce(string_agg(installed_rank::text || ':' || coalesce(checksum::text,''), ',' ORDER BY installed_rank),'') FROM fitness.fitness_schema_history;")
agent_history=$(source_psql -c "SELECT coalesce(string_agg(installed_rank::text || ':' || coalesce(checksum::text,''), ',' ORDER BY installed_rank),'') FROM agent.agent_schema_history;")
fitness_user_count=$(source_psql -c 'SELECT count(*) FROM fitness.users;')
agent_credential_count=$(source_psql -c 'SELECT count(*) FROM agent.agent_provider_credentials;')
SOURCE_MEDIA="$SOURCE_STATE_ROOT/deploy/.local/media"
if [ -d "$SOURCE_MEDIA" ] && [ ! -L "$SOURCE_MEDIA" ]; then
  (cd "$SOURCE_MEDIA" && find . -type f -print | sed 's#^./##' | LC_ALL=C sort \
    >"$TMP/media-members")
  (cd "$SOURCE_MEDIA" && tar -cf "$BUNDLE/media.tar" -T "$TMP/media-members")
  source_media_tree=$(media_tree_hash "$SOURCE_MEDIA")
else
  tar -cf "$BUNDLE/media.tar" -T /dev/null
  source_media_tree=$(printf '' | shasum -a 256 | awk '{print $1}')
fi
cp "$SOURCE_STATE_ROOT/deploy/secrets/agent-master-key" "$BUNDLE/agent-master-key"
cat >"$BUNDLE/metadata.env" <<EOF
fitness_history_count=$fitness_history_count
agent_history_count=$agent_history_count
application_table_count=$application_table_count
key_object_count=$key_object_count
media_sha256=$(sha256_file "$BUNDLE/media.tar")
media_tree_sha256=$source_media_tree
master_key_sha256=$(sha256_file "$BUNDLE/agent-master-key")
EOF
printf '{"source":"read-only-rehearsal","fitnessUsers":%s,"agentProviderCredentials":%s}\n' \
  "$fitness_user_count" "$agent_credential_count" >"$BUNDLE/source-validation.json"
chmod 0600 "$BUNDLE"/*
write_manifest "$BUNDLE"

log 'prepare a disposable production root and roles-only empty target'
mkdir -p "$GOOD_RELEASE/postgres" "$ROOT/releases" "$ROOT/secrets" \
  "$ROOT/certificates/production/live/happy-agent-ip" "$ROOT/data/acme-webroot" \
  "$ROOT/state/generations/original/postgres" "$ROOT/state/generations/original/media" \
  "$ROOT/backups" "$ROOT/logs"
cp "$PRODUCTION_ROOT/compose.yml" "$GOOD_RELEASE/compose.yml"
cp "$PRODUCTION_ROOT/postgres/"* "$GOOD_RELEASE/postgres/"
sed -e 's#__TLS_CERTIFICATE_PATH__#/etc/letsencrypt/production/live/happy-agent-ip/fullchain.pem#g' \
  -e 's#__TLS_PRIVATE_KEY_PATH__#/etc/letsencrypt/production/live/happy-agent-ip/privkey.pem#g' \
  "$PRODUCTION_ROOT/nginx/ip-https.conf.template" >"$GOOD_RELEASE/nginx.conf"
printf 'RELEASE_ID=%s\nAPP_IMAGE=%s\nWEB_IMAGE=%s\n' \
  "$RELEASE_ID" "$APP_IMAGE" "$WEB_IMAGE" >"$GOOD_RELEASE/.env"
for secret in postgres-password fitness-db-password agent-db-password; do
  openssl rand -base64 24 >"$ROOT/secrets/$secret"
done
cp "$BUNDLE/agent-master-key" "$ROOT/state/generations/original/agent-master-key"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=39.101.65.254' \
  -addext 'subjectAltName=IP:39.101.65.254' \
  -keyout "$ROOT/certificates/production/live/happy-agent-ip/privkey.pem" \
  -out "$ROOT/certificates/production/live/happy-agent-ip/cert.pem" >/dev/null 2>&1
cp "$ROOT/certificates/production/live/happy-agent-ip/cert.pem" \
  "$ROOT/certificates/production/live/happy-agent-ip/fullchain.pem"
cp "$ROOT/certificates/production/live/happy-agent-ip/cert.pem" \
  "$ROOT/certificates/production/live/happy-agent-ip/chain.pem"
chmod 0700 "$ROOT/secrets" "$ROOT/state/generations/original"
chmod 0600 "$ROOT/secrets/"* "$ROOT/state/generations/original/agent-master-key" \
  "$ROOT/certificates/production/live/happy-agent-ip/privkey.pem"
write_manifest "$GOOD_RELEASE"
ln -s "releases/$RELEASE_ID" "$ROOT/current"
ln -s generations/original "$ROOT/state/current"
compose up -d --no-deps postgres >/dev/null
wait_initialized_postgres || fail 'roles-only target PostgreSQL did not finish initialization'
[ "$(target_psql -f /usr/local/share/happy-agent-assert-initial-empty-target.sql)" \
  = HAPPY_AGENT_INITIAL_TARGET_EMPTY ] || fail 'fresh target is not the production empty baseline'

log 'execute the actual production restore contract'
HAPPY_AGENT_ROOT="$ROOT" HAPPY_AGENT_TIMESTAMP=20990101T000002Z \
  bash "$PRODUCTION_ROOT/scripts/restore-initial-data.sh" "$BUNDLE" --initial-empty-target
[ "$(realpath "$ROOT/state/current")" \
  = "$(realpath "$ROOT/state/generations/restore-20990101T000002Z")" ] \
  || fail 'restore did not atomically select the restored generation'

assert_restored_state() {
  [ "$(target_psql -c "SELECT coalesce(string_agg(installed_rank::text || ':' || coalesce(checksum::text,''), ',' ORDER BY installed_rank),'') FROM fitness.fitness_schema_history;")" = "$fitness_history" ] \
    || fail 'Fitness Flyway history differs from source'
  [ "$(target_psql -c "SELECT coalesce(string_agg(installed_rank::text || ':' || coalesce(checksum::text,''), ',' ORDER BY installed_rank),'') FROM agent.agent_schema_history;")" = "$agent_history" ] \
    || fail 'Agent Flyway history differs from source'
  [ "$(target_psql -c 'SELECT count(*) FROM fitness.users;')" = "$fitness_user_count" ] \
    || fail 'Fitness business count differs from source'
  [ "$(target_psql -c 'SELECT count(*) FROM agent.agent_provider_credentials;')" \
    = "$agent_credential_count" ] \
    || fail 'Agent business count differs from source'
  [ "$(media_tree_hash "$ROOT/state/current/media")" = "$source_media_tree" ] \
    || fail 'restored media tree differs from source'
}
assert_role_denied() {
  local role=$1 query=$2
  if target_psql -c "SET ROLE $role; $query" >/dev/null 2>&1; then
    fail "$role retained cross-schema read access"
  fi
}
assert_restored_state
assert_role_denied fitness_app 'SELECT count(*) FROM agent.agent_runs;'
assert_role_denied agent_app 'SELECT count(*) FROM fitness.users;'

log 'start the exact production App and Web images'
compose up -d app >/dev/null
wait_healthy app || fail 'production App image did not become healthy against restored data'
docker run -d --name "$WEB_PROBE" "$WEB_IMAGE" >/dev/null
[ "$(docker inspect --format '{{.State.Status}}' "$WEB_PROBE")" = running ] \
  || fail 'production Web image did not start'
docker rm -f "$WEB_PROBE" >/dev/null

log 'verify Provider credential authentication with the existing application cipher boundary'
credential_row=$(target_psql -F '|' -c "SELECT provider_key,credential_key_version,encode(credential_ciphertext,'base64'),encode(credential_iv,'base64') FROM agent.agent_provider_credentials ORDER BY provider_key LIMIT 1;")
[ -n "$credential_row" ] || fail 'source contains no Provider credential to authenticate'
IFS='|' read -r provider_key credential_version credential_ciphertext credential_iv <<<"$credential_row"
cat >"$TMP/CredentialProbe.java" <<'EOF'
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.EncryptedSecret;
import happy.jayden.yang.agentbuilder.infrastructure.security.AesGcmCredentialCipher;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

public final class CredentialProbe {
  public static void main(String[] args) {
    ComponentRef ref = new ComponentRef(new ComponentKey(args[1]), new ComponentVersion(Integer.parseInt(args[2])));
    EncryptedSecret encrypted = new EncryptedSecret(ref, Base64.getDecoder().decode(args[3]), Base64.getDecoder().decode(args[4]));
    char[] plain = AesGcmCredentialCipher.fromEnvironment(Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, args[0]), ref).decrypt(encrypted);
    Arrays.fill(plain, '\0');
    boolean rejected = false;
    try {
      AesGcmCredentialCipher.fromEnvironment(Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, args[5]), ref).decrypt(encrypted);
    } catch (SecurityException expected) {
      rejected = true;
    }
    if (!rejected) throw new IllegalStateException("wrong key accepted");
    System.out.println("configured=true decryptable=true wrong_key_rejected=true");
  }
}
EOF
openssl rand -base64 32 >"$TMP/wrong-master-key"
chmod 0600 "$TMP/wrong-master-key"
PROBE_CP="$REPOSITORY_ROOT/agentbuilder/agentbuilder-core/target/classes:$REPOSITORY_ROOT/agentbuilder/agentbuilder-infrastructure/target/classes"
javac -cp "$PROBE_CP" -d "$TMP" "$TMP/CredentialProbe.java"
probe_result=$(java -cp "$TMP:$PROBE_CP" CredentialProbe \
  "$ROOT/state/current/agent-master-key" "$provider_key" "$credential_version" \
  "$credential_ciphertext" "$credential_iv" "$TMP/wrong-master-key")
unset credential_row credential_ciphertext credential_iv
[ "$probe_result" = 'configured=true decryptable=true wrong_key_rejected=true' ] \
  || fail 'Provider credential authentication probe did not return the safe boolean contract'

log 'restart PostgreSQL and App, then repeat persistence checks'
compose restart postgres app >/dev/null
wait_healthy postgres || fail 'PostgreSQL was not healthy after restart'
wait_healthy app || fail 'App was not healthy after restart'
assert_restored_state

log 'inject an unhealthy release through the actual activation/recovery contract'
mkdir -p "$BAD_RELEASE"
cp -R "$GOOD_RELEASE/." "$BAD_RELEASE/"
sed -i.bak "s/^RELEASE_ID=.*/RELEASE_ID=$BAD_RELEASE_ID/" "$BAD_RELEASE/.env"
sed -i.bak "s#^APP_IMAGE=.*#APP_IMAGE=$WEB_IMAGE#" "$BAD_RELEASE/.env"
rm "$BAD_RELEASE/.env.bak"
write_manifest "$BAD_RELEASE"
cat >"$TMP/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
headers= body= url= authenticated=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dump-header) headers=$2; shift 2;;
    --output) body=$2; shift 2;;
    --config) authenticated=1; shift 2;;
    --write-out) shift 2;;
    --header|--max-time) shift 2;;
    --*) shift;;
    *) url=$1; shift;;
  esac
done
case "$url" in
  */events)
    if [ "$authenticated" = 1 ]; then code=200; type='text/event-stream'; content='data: {}';
    else code=401; type='application/problem+json'; content='{}'; fi
    cache='Cache-Control: no-cache'
    ;;
  */api/*) code=401; type='application/problem+json'; content='{}'; cache='';;
  *) code=200; type='text/html'; content='<html></html>'; cache='';;
esac
printf 'HTTP/1.1 %s Test\r\nContent-Type: %s\r\n%s\r\n\r\n' "$code" "$type" "$cache" >"$headers"
printf '%s\n' "$content" >"$body"
printf '%s' "$code"
EOF
chmod 0700 "$TMP/bin/curl"
openssl rand -hex 32 >"$ROOT/secrets/public-smoke-session"
printf '00000000-0000-0000-0000-000000000001\n' >"$ROOT/secrets/public-smoke-run-id"
chmod 0600 "$ROOT/secrets/public-smoke-session" "$ROOT/secrets/public-smoke-run-id"
if HAPPY_AGENT_ROOT="$ROOT" HAPPY_AGENT_HEALTH_ATTEMPTS=30 HAPPY_AGENT_HEALTH_INTERVAL=2 \
    bash "$PRODUCTION_ROOT/scripts/activate-release.sh" "$BAD_RELEASE_ID"; then
  fail 'unhealthy release activation unexpectedly succeeded'
fi
[ "$(realpath "$ROOT/current")" = "$(realpath "$GOOD_RELEASE")" ] \
  || fail 'unhealthy activation did not recover the previous current release'
wait_healthy postgres || fail 'old PostgreSQL was not healthy after failed release'
wait_healthy app || fail 'old App was not healthy after failed release'
assert_restored_state

SUCCESS=1
