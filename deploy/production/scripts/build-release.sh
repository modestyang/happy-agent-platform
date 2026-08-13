#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PRODUCTION_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
REPOSITORY_ROOT=$(cd "$PRODUCTION_ROOT/../.." && pwd)
ARTIFACT_ROOT="$REPOSITORY_ROOT/deploy/.local/production"
RELEASE_ROOT="$ARTIFACT_ROOT/releases"
STAGING_ROOT="$ARTIFACT_ROOT/staging"
TARGET_PLATFORM=linux/amd64

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "required command unavailable: $1"; }

for command_name in docker git node npm sha256sum; do require_command "$command_name"; done
[ -x "$REPOSITORY_ROOT/mvnw" ] || die 'Maven wrapper is not executable'
[ "$(git -C "$REPOSITORY_ROOT" rev-parse --show-toplevel)" = "$REPOSITORY_ROOT" ] \
  || die 'build repository root mismatch'

timestamp=${HAPPY_AGENT_BUILD_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}
[[ "$timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || die 'invalid injected build timestamp'
source_commit=$(git -C "$REPOSITORY_ROOT" rev-parse HEAD)
source_short=$(git -C "$REPOSITORY_ROOT" rev-parse --short HEAD)
[[ "$source_commit" =~ ^[a-f0-9]{40}$ ]] || die 'invalid source commit'
[[ "$source_short" =~ ^[a-f0-9]{7,40}$ ]] || die 'invalid abbreviated source commit'
release_id="$timestamp-$source_short"
[[ "$release_id" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{7,40}$ ]] || die 'unsafe release id'

umask 077
install -d -m 0700 "$ARTIFACT_ROOT" "$RELEASE_ROOT" "$STAGING_ROOT"
pending="$RELEASE_ROOT/.pending-$release_id"
complete="$RELEASE_ROOT/$release_id"
build_tmp="$STAGING_ROOT/.build-$release_id-$$"
[ ! -e "$pending" ] && [ ! -e "$complete" ] && [ ! -e "$build_tmp" ] \
  || die 'release id already exists'
install -d -m 0700 "$pending" "$pending/images" "$pending/scripts" "$pending/postgres" \
  "$pending/systemd" "$build_tmp"

cleanup_build() {
  local cleanup_status=$?
  if [ -d "$pending" ]; then /bin/rm -rf -- "$pending"; fi
  if [ -d "$build_tmp" ]; then /bin/rm -rf -- "$build_tmp"; fi
  return "$cleanup_status"
}
trap cleanup_build EXIT

(
  cd "$REPOSITORY_ROOT"
  ./mvnw -Dtest='*,!FitnessExperienceIntegrationTest#dailyMealPlanReadEndpointReturnsThePersistedThreeMealPlan' \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ./mvnw -q -pl starter -am \
    -Dtest='FitnessExperienceIntegrationTest#dailyMealPlanReadEndpointReturnsThePersistedThreeMealPlan' \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ./mvnw spotless:check
  npm --prefix frontend test -- --exclude src/admin/AdminWorkbench.test.tsx
  npm --prefix frontend test -- src/admin/AdminWorkbench.test.tsx
  npm --prefix frontend run typecheck
  npm --prefix frontend run build
  ./mvnw -DskipTests -pl starter -am package
) >&2

maven_version=$(cd "$REPOSITORY_ROOT" && ./mvnw --version | head -n1)
npm_version=$(npm --version | head -n1)
docker_version=$(docker --version | head -n1)
compose_version=$(docker compose version | head -n1)
[ -n "$maven_version" ] && [ -n "$npm_version" ] && [ -n "$docker_version" ] \
  && [ -n "$compose_version" ] || die 'tool version discovery failed'

app_base='eclipse-temurin:17-jre-jammy@sha256:89e68b9bb83713510b63e2059a415792a7fc77e14b739a7d7ede97f6d9ca2c38'
web_base='nginx:stable-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46'
postgres_base='postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777'
grep -Fxq "$app_base" "$PRODUCTION_ROOT/base-images.lock" \
  && grep -Fxq "$web_base" "$PRODUCTION_ROOT/base-images.lock" \
  && grep -Fxq "$postgres_base" "$PRODUCTION_ROOT/base-images.lock" \
  || die 'pinned base-image lock is incomplete'
grep -Fq "FROM $app_base" "$PRODUCTION_ROOT/app.Dockerfile" \
  && grep -Fq "FROM $web_base" "$PRODUCTION_ROOT/web.Dockerfile" \
  || die 'App/Web Dockerfile does not use the pinned base contract'
sed "s#^FROM postgres:16.14-alpine3.24#FROM $postgres_base#" \
  "$REPOSITORY_ROOT/deploy/postgres/Dockerfile" >"$build_tmp/postgres.Dockerfile"
[ "$(grep -Fc "FROM $postgres_base" "$build_tmp/postgres.Dockerfile")" = 2 ] \
  || die 'PostgreSQL build did not pin both stages'

app_context="$build_tmp/app-context"
web_context="$build_tmp/web-context"
postgres_context="$build_tmp/postgres-context"
install -d -m 0700 "$app_context/deploy/production" "$app_context/starter/target" \
  "$web_context/frontend/dist" "$postgres_context"
install -m 0600 "$PRODUCTION_ROOT/app-entrypoint.sh" \
  "$app_context/deploy/production/app-entrypoint.sh"
shopt -s nullglob
starter_jars=("$REPOSITORY_ROOT"/starter/target/starter-*-exec.jar)
shopt -u nullglob
[ "${#starter_jars[@]}" = 1 ] || die 'expected exactly one executable starter jar'
install -m 0600 "${starter_jars[0]}" "$app_context/starter/target/${starter_jars[0]##*/}"
[ -d "$REPOSITORY_ROOT/frontend/dist" ] && [ ! -L "$REPOSITORY_ROOT/frontend/dist" ] \
  || die 'frontend build output is missing or indirect'
[ -z "$(find "$REPOSITORY_ROOT/frontend/dist" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
  || die 'frontend build output contains a link or special member'
cp -R "$REPOSITORY_ROOT/frontend/dist/." "$web_context/frontend/dist/"

app_image="happy-agent-app:$release_id"
web_image="happy-agent-web:$release_id"
postgres_image="happy-agent-postgres:$release_id"
(
  cd "$REPOSITORY_ROOT"
  docker buildx build --platform "$TARGET_PLATFORM" -f deploy/production/app.Dockerfile \
    -t "$app_image" --load "$app_context"
  docker buildx build --platform "$TARGET_PLATFORM" -f deploy/production/web.Dockerfile \
    -t "$web_image" --load "$web_context"
  docker buildx build --platform "$TARGET_PLATFORM" -f "$build_tmp/postgres.Dockerfile" \
    -t "$postgres_image" --load "$postgres_context"
)

docker image save -o "$pending/images/app.tar" "$app_image"
docker image save -o "$pending/images/web.tar" "$web_image"
docker image save -o "$pending/images/postgres.tar" "$postgres_image"
chmod 0600 "$pending/images"/*.tar

app_image_id=$(docker image inspect --format '{{.Id}}' "$app_image")
web_image_id=$(docker image inspect --format '{{.Id}}' "$web_image")
postgres_image_id=$(docker image inspect --format '{{.Id}}' "$postgres_image")
app_repo_digests=$(docker image inspect --format '{{json .RepoDigests}}' "$app_image")
web_repo_digests=$(docker image inspect --format '{{json .RepoDigests}}' "$web_image")
postgres_repo_digests=$(docker image inspect --format '{{json .RepoDigests}}' "$postgres_image")
for image_id in "$app_image_id" "$web_image_id" "$postgres_image_id"; do
  [[ "$image_id" =~ ^sha256:[a-f0-9]{64}$ ]] || die 'built image has no valid image ID'
done
for repo_digests in "$app_repo_digests" "$web_repo_digests" "$postgres_repo_digests"; do
  node -e 'const value=JSON.parse(process.argv[1]); if (!Array.isArray(value)) process.exit(1)' \
    "$repo_digests" || die 'invalid image RepoDigest metadata'
done

install -m 0600 "$PRODUCTION_ROOT/compose.yml" "$pending/compose.yml"
install -m 0600 "$PRODUCTION_ROOT/nginx/ip-https.conf.template" "$build_tmp/nginx.conf"
sed -e 's#__TLS_CERTIFICATE_PATH__#/etc/letsencrypt/production/live/happy-agent-ip/fullchain.pem#g' \
  -e 's#__TLS_PRIVATE_KEY_PATH__#/etc/letsencrypt/production/live/happy-agent-ip/privkey.pem#g' \
  "$build_tmp/nginx.conf" >"$pending/nginx.conf"
install -m 0600 "$PRODUCTION_ROOT/nginx/ip-http.conf.template" "$pending/nginx-http.conf"
! grep -Eq '__TLS_(CERTIFICATE|PRIVATE_KEY)_PATH__' "$pending/nginx.conf" \
  || die 'HTTPS Nginx template was not fully rendered'
printf 'RELEASE_ID=%s\nAPP_IMAGE=%s\nWEB_IMAGE=%s\n' \
  "$release_id" "$app_image" "$web_image" >"$pending/.env"
chmod 0600 "$pending/.env" "$pending/nginx.conf" "$pending/nginx-http.conf"

for script_name in common.sh bootstrap-host.sh issue-certificate.sh renew-certificate.sh backup.sh \
  restore-initial-data.sh activate-release.sh rollback.sh status.sh; do
  install -m 0700 "$PRODUCTION_ROOT/scripts/$script_name" "$pending/scripts/$script_name"
done
for postgres_file in init-roles.sh init-roles.sql enforce-isolation.sql assert-initial-empty-target.sql; do
  install -m 0600 "$PRODUCTION_ROOT/postgres/$postgres_file" "$pending/postgres/$postgres_file"
done
for unit_file in happy-agent-cert-renew.service happy-agent-cert-renew.timer; do
  install -m 0600 "$PRODUCTION_ROOT/systemd/$unit_file" "$pending/systemd/$unit_file"
done

git_status=$(git -C "$REPOSITORY_ROOT" status --porcelain=v1 --untracked-files=all)
if [ -n "$git_status" ]; then source_dirty=true; else source_dirty=false; fi
source_diff_sha256=$(git -C "$REPOSITORY_ROOT" diff --binary HEAD | sha256sum | awk '{print $1}')
status_inventory_sha256=$(printf '%s\n' "$git_status" | sha256sum | awk '{print $1}')
agent_v1_sha256=$(sha256sum "$REPOSITORY_ROOT/agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql" | awk '{print $1}')
for digest in "$source_diff_sha256" "$status_inventory_sha256" "$agent_v1_sha256"; do
  [[ "$digest" =~ ^[a-f0-9]{64}$ ]] || die 'invalid source metadata digest'
done

cat >"$pending/build-metadata.env" <<EOF
release_id=$release_id
source_commit=$source_commit
source_dirty=$source_dirty
source_diff_sha256=$source_diff_sha256
status_inventory_sha256=$status_inventory_sha256
agent_v1_sha256=$agent_v1_sha256
target_platform=$TARGET_PLATFORM
app_image_id=$app_image_id
web_image_id=$web_image_id
postgres_image_id=$postgres_image_id
file_inventory_scope=package_files_excluding_build_metadata_and_manifest
EOF
chmod 0600 "$pending/build-metadata.env"

file_checksums="$build_tmp/file-checksums.tsv"
(
  cd "$pending"
  find . -type f ! -name SHA256SUMS ! -name build-metadata.env ! -name build-metadata.json -print \
    | LC_ALL=C sort | sed 's#^./##' \
    | while IFS= read -r package_file; do
        printf '%s\t%s\n' "$package_file" "$(sha256sum "$package_file" | awk '{print $1}')"
      done
) >"$file_checksums"
node - "$pending/build-metadata.json" "$file_checksums" "$release_id" "$source_commit" \
  "$source_dirty" "$source_diff_sha256" "$status_inventory_sha256" "$agent_v1_sha256" \
  "$maven_version" "$npm_version" "$docker_version" "$compose_version" "$TARGET_PLATFORM" \
  "$app_image_id" "$app_repo_digests" "$web_image_id" "$web_repo_digests" \
  "$postgres_image_id" "$postgres_repo_digests" <<'NODE'
const fs = require('fs');
const [output, inventory, releaseId, sourceCommit, dirty, diffSha256, statusSha256,
  agentV1Sha256, maven, npm, docker, compose, targetPlatform,
  appId, appDigests, webId, webDigests, postgresId, postgresDigests] = process.argv.slice(2);
const files = {};
for (const line of fs.readFileSync(inventory, 'utf8').split('\n')) {
  if (!line) continue;
  const [name, digest] = line.split('\t');
  files[name] = digest;
}
const metadata = {
  releaseId,
  source: {commit: sourceCommit, dirty: dirty === 'true', diffSha256, statusInventorySha256: statusSha256},
  agentV1Sha256,
  targetPlatform,
  tools: {maven, npm, docker, compose},
  images: {
    app: {id: appId, repoDigests: JSON.parse(appDigests)},
    web: {id: webId, repoDigests: JSON.parse(webDigests)},
    postgres: {id: postgresId, repoDigests: JSON.parse(postgresDigests)}
  },
  files
};
fs.writeFileSync(output, `${JSON.stringify(metadata, null, 2)}\n`, {mode: 0o600});
NODE
chmod 0600 "$pending/build-metadata.json"

(
  cd "$pending"
  find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | sed 's#^./##' \
    | while IFS= read -r package_file; do sha256sum "$package_file"; done >SHA256SUMS
)
chmod 0600 "$pending/SHA256SUMS"
[ -z "$(find "$pending" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
  || die 'release contains a link or special member'
(cd "$pending" && sha256sum --check --strict SHA256SUMS >/dev/null) \
  || die 'release checksum verification failed'

/bin/rm -rf -- "$build_tmp"
mv -- "$pending" "$complete"
pending=''
build_tmp=''
trap - EXIT
log "release built: $release_id"
printf '%s\n' "$complete"
