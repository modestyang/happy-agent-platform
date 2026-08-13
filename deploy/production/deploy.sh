#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

usage() {
  printf 'usage: deploy.sh bootstrap|migrate|release|status|backup|rollback RELEASE_ID\n' >&2
  exit 2
}

[ "$#" -ge 1 ] || usage
command_name=$1
case "$command_name" in
  bootstrap|migrate|release|status|backup) [ "$#" = 1 ] || usage;;
  rollback)
    [ "$#" = 2 ] || usage
    [[ "$2" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{7,40}$ ]] || usage
    rollback_release_id=$2
    ;;
  *) usage;;
esac

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
BUILD_SCRIPT="$SCRIPT_DIR/scripts/build-release.sh"
EXPORT_SCRIPT="$SCRIPT_DIR/scripts/export-initial-data.sh"
CLOUD_SCRIPT="$SCRIPT_DIR/scripts/cloud-guardrails.sh"

readonly EXPECTED_REGION=cn-wulanchabu
readonly EXPECTED_INSTANCE_ID=i-0jlfb8o4hqpjekoudg4x
readonly EXPECTED_SECURITY_GROUP_ID=sg-0jlb5v2njkb2jbzrvurr
readonly EXPECTED_PUBLIC_IP=39.101.65.254
readonly EXPECTED_PROFILE=ecs-audit
readonly ACR_REGISTRY=crpi-3r93ak2ft29pxf1q.cn-wulanchabu.personal.cr.aliyuncs.com
readonly REMOTE_USER=root
readonly REMOTE_ROOT=/opt/happy-agent
readonly REGION=${HAPPY_AGENT_REGION:-$EXPECTED_REGION}
readonly INSTANCE_ID=${HAPPY_AGENT_INSTANCE_ID:-$EXPECTED_INSTANCE_ID}
readonly SECURITY_GROUP_ID=${HAPPY_AGENT_SECURITY_GROUP_ID:-$EXPECTED_SECURITY_GROUP_ID}
readonly PUBLIC_IP=${HAPPY_AGENT_PUBLIC_IP:-$EXPECTED_PUBLIC_IP}
readonly ALIYUN_PROFILE=${HAPPY_AGENT_ALIYUN_PROFILE:-$EXPECTED_PROFILE}
readonly REMOTE="$REMOTE_USER@$PUBLIC_IP"

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "required command unavailable: $1"; }
file_mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
validate_identifier() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die 'unsafe deployment identifier'
}
ensure_private_directory() {
  if [ -e "$1" ]; then
    [ -d "$1" ] && [ ! -L "$1" ] || die "local artifact path is unsafe: $1"
    [ "$(file_mode "$1")" = 700 ] || die "local artifact directory must have mode 0700: $1"
  else
    install -d -m 0700 "$1"
  fi
}
private_credential_file() {
  local raw=$1 label=$2
  case "$raw" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die "$label path must be a safe absolute path";; esac
  [ -f "$raw" ] && [ ! -L "$raw" ] && [ -r "$raw" ] \
    || die "$label is missing, unreadable, or indirect"
  [ "$(file_mode "$raw")" = 600 ] || die "$label must have mode 0600"
  realpath "$raw"
}

[ "$REGION" = "$EXPECTED_REGION" ] && [ "$INSTANCE_ID" = "$EXPECTED_INSTANCE_ID" ] \
  && [ "$SECURITY_GROUP_ID" = "$EXPECTED_SECURITY_GROUP_ID" ] \
  && [ "$PUBLIC_IP" = "$EXPECTED_PUBLIC_IP" ] && [ "$ALIYUN_PROFILE" = "$EXPECTED_PROFILE" ] \
  || die 'fixed deployment target variables drifted'
for command_required in awk date find git install node realpath scp sha256sum ssh; do
  require_command "$command_required"
done
for product_script in "$BUILD_SCRIPT" "$EXPORT_SCRIPT" "$CLOUD_SCRIPT"; do
  [ -x "$product_script" ] || die "required local product script is unavailable: $product_script"
done

source_state_raw=${SOURCE_STATE_ROOT:-/Users/modest/IdeaProjects/happy-agent-platform}
case "$source_state_raw" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die 'SOURCE_STATE_ROOT must be a safe absolute path';; esac
[ -d "$source_state_raw" ] && [ ! -L "$source_state_raw" ] \
  || die 'SOURCE_STATE_ROOT must be a non-symlink directory'
SOURCE_STATE_ROOT=$(realpath "$source_state_raw")
export SOURCE_STATE_ROOT

identity_raw=${HAPPY_AGENT_SSH_IDENTITY:-"$HOME/.ssh/id_ed25519"}
case "$identity_raw" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die 'SSH identity must be a safe absolute path';; esac
[ -f "$identity_raw" ] && [ ! -L "$identity_raw" ] && [ -r "$identity_raw" ] \
  || die 'SSH identity is missing, unreadable, or indirect'
identity_mode=$(file_mode "$identity_raw")
case "$identity_mode" in 400|600) ;; *) die 'SSH identity must have mode 0400 or 0600';; esac
SSH_IDENTITY=$(realpath "$identity_raw")

known_hosts_raw=${HAPPY_AGENT_KNOWN_HOSTS:-"$SOURCE_STATE_ROOT/deploy/.local/production/known_hosts"}
case "$known_hosts_raw" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die 'known_hosts must be a safe absolute path';; esac
[ -f "$known_hosts_raw" ] && [ ! -L "$known_hosts_raw" ] && [ -r "$known_hosts_raw" ] \
  || die 'verified known_hosts file is missing, unreadable, or indirect'
[ "$(file_mode "$known_hosts_raw")" = 600 ] || die 'verified known_hosts must have mode 0600'
KNOWN_HOSTS=$(realpath "$known_hosts_raw")
awk -v ip="$PUBLIC_IP" '$1 == ip && NF >= 3 {found=1} END {exit !found}' "$KNOWN_HOSTS" \
  || die 'verified known_hosts has no exact production IP entry'

ARTIFACT_ROOT="$REPOSITORY_ROOT/deploy/.local/production"
RELEASE_ROOT="$ARTIFACT_ROOT/releases"
MIGRATION_ROOT="$ARTIFACT_ROOT/migrations"
RECOVERY_ROOT="$ARTIFACT_ROOT/recovery"
STAGING_ROOT="$ARTIFACT_ROOT/staging"
umask 077
ensure_private_directory "$ARTIFACT_ROOT"
ensure_private_directory "$RELEASE_ROOT"
ensure_private_directory "$MIGRATION_ROOT"
ensure_private_directory "$RECOVERY_ROOT"
ensure_private_directory "$STAGING_ROOT"
ACR_USERNAME=''
ACR_USERNAME_FILE=''
ACR_PASSWORD_FILE=''

load_acr_credentials() {
  [ -z "$ACR_USERNAME" ] || return 0
  ACR_USERNAME_FILE=$(private_credential_file \
    "${HAPPY_AGENT_ACR_USERNAME_FILE:-$ARTIFACT_ROOT/acr-username}" 'ACR username file')
  ACR_PASSWORD_FILE=$(private_credential_file \
    "${HAPPY_AGENT_ACR_PASSWORD_FILE:-$ARTIFACT_ROOT/acr-password}" 'ACR password file')
  ACR_USERNAME=$(cat "$ACR_USERNAME_FILE")
  [[ "$ACR_USERNAME" =~ ^[A-Za-z0-9._@-]+$ ]] \
    && [ "$(wc -l <"$ACR_USERNAME_FILE" | tr -d ' ')" = 1 ] \
    || die 'ACR username file must contain one safe line'
  [ -s "$ACR_PASSWORD_FILE" ] || die 'ACR password file must not be empty'
  export HAPPY_AGENT_ACR_USERNAME_FILE="$ACR_USERNAME_FILE"
  export HAPPY_AGENT_ACR_PASSWORD_FILE="$ACR_PASSWORD_FILE"
}

SSH_OPTIONS=(
  -o BatchMode=yes
  -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$KNOWN_HOSTS"
  -i "$SSH_IDENTITY"
)

remote_exec() {
  ssh -n "${SSH_OPTIONS[@]}" "$REMOTE" "$@"
}

remote_registry_login() {
  load_acr_credentials
  ssh "${SSH_OPTIONS[@]}" "$REMOTE" docker login --username "$ACR_USERNAME" \
    --password-stdin "$ACR_REGISTRY" <"$ACR_PASSWORD_FILE"
  remote_exec chmod 0700 /root/.docker
  remote_exec chmod 0600 /root/.docker/config.json
}

remote_control() {
  ssh "${SSH_OPTIONS[@]}" "$REMOTE" /bin/bash -s -- "$@" <<'REMOTE_SCRIPT'
set -euo pipefail
export LC_ALL=C
root=/opt/happy-agent
action=${1:-}
[ "$#" -ge 1 ] || exit 2
shift
valid_identifier() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; }
verify_manifest() {
  local directory=$1 line digest member actual listed='|'
  [ -d "$directory" ] && [ ! -L "$directory" ] || return 1
  [ -f "$directory/SHA256SUMS" ] && [ ! -L "$directory/SHA256SUMS" ] || return 1
  [ -z "$(find "$directory" -mindepth 1 ! -type f ! -type d -print -quit)" ] || return 1
  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^([a-f0-9]{64})[[:space:]][\ \*](.+)$ ]] || return 1
    digest=${BASH_REMATCH[1]}
    member=${BASH_REMATCH[2]}
    case "$member" in ''|/*|*'?'*|*'['*|*'*'*|*'\\'*|*'|'*|.|..|../*|*/../*|*/..) return 1;; esac
    case "$listed" in *"|$member|"*) return 1;; esac
    [ -f "$directory/$member" ] && [ ! -L "$directory/$member" ] || return 1
    listed="${listed}${member}|"
  done <"$directory/SHA256SUMS"
  [ "$listed" != '|' ] || return 1
  while IFS= read -r -d '' actual; do
    member=${actual#"$directory"/}
    [ "$member" = SHA256SUMS ] || case "$listed" in *"|$member|"*) ;; *) return 1;; esac
  done < <(find "$directory" -type f -print0)
  (cd "$directory" && sha256sum --check --strict SHA256SUMS >/dev/null)
}
pending_path() {
  local kind=$1 id=$2
  valid_identifier "$kind" && valid_identifier "$id" || return 1
  printf '%s/staging/.pending-%s-%s\n' "$root" "$kind" "$id"
}
atomic_current() {
  local release=$1 temporary="$root/.current.$$.tmp"
  valid_identifier "$release" || return 1
  [ ! -e "$temporary" ] && [ ! -L "$temporary" ] || return 1
  ln -s "releases/$release" "$temporary"
  mv -Tf -- "$temporary" "$root/current"
}
case "$action" in
  prepare-staging)
    [ "$#" = 2 ] || exit 2
    kind=$1; id=$2
    pending=$(pending_path "$kind" "$id")
    install -d -m 0700 "$root/staging"
    [ ! -e "$pending" ] && [ ! -L "$pending" ] || exit 1
    install -d -m 0700 "$pending"
    ;;
  cleanup-staging)
    [ "$#" = 2 ] || exit 2
    pending=$(pending_path "$1" "$2")
    if [ -d "$pending" ] && [ ! -L "$pending" ]; then rm -rf -- "$pending"; fi
    ;;
  publish-release)
    [ "$#" = 1 ] || exit 2
    id=$1; pending=$(pending_path release "$id")
    valid_identifier "$id" || exit 2
    verify_manifest "$pending"
    for required in .env compose.yml nginx.conf; do
      grep -E "^[a-f0-9]{64} [ *]${required//./[.]}$" "$pending/SHA256SUMS" >/dev/null || exit 1
    done
    install -d -m 0750 "$root/releases"
    [ ! -e "$root/releases/$id" ] || exit 1
    mv -T -- "$pending" "$root/releases/$id"
    ;;
  publish-migration)
    [ "$#" = 1 ] || exit 2
    id=$1; pending=$(pending_path migration "$id")
    valid_identifier "$id" || exit 2
    verify_manifest "$pending"
    for required in initial.dump media.tar agent-master-key metadata.env source-validation.json; do
      grep -E "^[a-f0-9]{64} [ *]${required}$" "$pending/SHA256SUMS" >/dev/null || exit 1
    done
    install -d -m 0700 "$root/migrations"
    [ ! -e "$root/migrations/$id" ] || exit 1
    mv -T -- "$pending" "$root/migrations/$id"
    ;;
  publish-bootstrap)
    [ "$#" = 1 ] || exit 2
    id=$1; pending=$(pending_path bootstrap "$id")
    valid_identifier "$id" || exit 2
    verify_manifest "$pending"
    install -d -m 0700 "$root/bootstrap"
    [ ! -e "$root/bootstrap/$id" ] || exit 1
    mv -T -- "$pending" "$root/bootstrap/$id"
    ;;
  pull-release)
    [ "$#" = 1 ] || exit 2
    id=$1; release="$root/releases/$id"
    valid_identifier "$id" || exit 2
    verify_manifest "$release"
    HAPPY_AGENT_ROOT="$root" docker compose -p happy-agent --env-file "$release/.env" \
      -f "$release/compose.yml" pull postgres app nginx
    ;;
  start-bootstrap)
    [ "$#" = 1 ] || exit 2
    id=$1; bundle="$root/bootstrap/$id"
    valid_identifier "$id" || exit 2
    verify_manifest "$bundle"
    image=$(sed -n 's/^NGINX_IMAGE=//p' "$bundle/image.env")
    [ "$image" = 'nginx:stable-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46' ] || exit 1
    docker pull "$image"
    if docker container inspect happy-agent-bootstrap-nginx >/dev/null 2>&1; then
      docker rm -f happy-agent-bootstrap-nginx
    fi
    docker run -d --name happy-agent-bootstrap-nginx --restart unless-stopped \
      -p 80:80 -v "$bundle/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
      -v "$root/data/acme-webroot:/var/www/acme:ro" "$image"
    ;;
  start-http-release)
    [ "$#" = 1 ] || exit 2
    id=$1; release="$root/releases/http-$id"
    valid_identifier "$id" || exit 2
    verify_manifest "$release"
    HAPPY_AGENT_ROOT="$root" /bin/bash -c \
      'source "$1/scripts/common.sh"; ensure_release_images "$1" nginx' _ "$release"
    if docker container inspect happy-agent-bootstrap-nginx >/dev/null 2>&1; then
      docker rm -f happy-agent-bootstrap-nginx
    fi
    atomic_current "http-$id"
    HAPPY_AGENT_ROOT="$root" docker compose -p happy-agent --env-file "$release/.env" \
      -f "$release/compose.yml" up -d --no-deps nginx
    ready=0
    for attempt in $(seq 1 60); do
      if HAPPY_AGENT_ROOT="$root" /bin/bash -c \
          'source "$1/scripts/common.sh"; service_matches_release "$1" nginx' _ "$release"; then
        ready=1
        break
      fi
      sleep 1
    done
    [ "$ready" = 1 ] || exit 1
    ;;
  install-smoke)
    [ "$#" = 1 ] || exit 2
    id=$1; pending=$(pending_path smoke "$id")
    valid_identifier "$id" || exit 2
    install -d -m 0700 "$root/secrets"
    for secret in public-smoke-session public-smoke-run-id; do
      raw="$pending/$secret"
      [ ! -L "$raw" ] && [ -f "$raw" ] && [ -r "$raw" ] || exit 1
      [ "$(stat -c %a "$raw")" = 600 ] || exit 1
      temporary="$root/secrets/.$secret.$$.tmp"
      [ ! -e "$temporary" ] && [ ! -L "$temporary" ] || exit 1
      install -m 0600 "$raw" "$temporary"
      mv -Tf -- "$temporary" "$root/secrets/$secret"
    done
    rm -rf -- "$pending"
    ;;
  migration-marker-status)
    [ "$#" = 0 ] || exit 2
    if [ -f "$root/state/first-migration.marker" ] && [ ! -L "$root/state/first-migration.marker" ]; then
      printf 'complete\n'
    elif [ -e "$root/state/first-migration.marker" ] || [ -L "$root/state/first-migration.marker" ]; then
      printf 'unsafe\n'
    elif [ -L "$root/state/current" ] \
        && [ "$(readlink -f -- "$root/state/current")" = "$root/state/generations/initial-empty" ] \
        && [ -d "$root/state/generations/initial-empty" ] \
        && [ ! -L "$root/state/generations/initial-empty" ]; then
      printf 'ready\n'
    else
      printf 'unsafe\n'
    fi
    ;;
  write-migration-marker)
    [ "$#" = 2 ] || exit 2
    release_id=$1; migration_id=$2
    valid_identifier "$release_id" && valid_identifier "$migration_id" || exit 2
    marker="$root/state/first-migration.marker"
    [ ! -e "$marker" ] && [ ! -L "$marker" ] || exit 1
    temporary="$root/state/.first-migration.$$.tmp"
    install -m 0600 /dev/null "$temporary"
    printf 'release_id=%s\nmigration_id=%s\n' "$release_id" "$migration_id" >"$temporary"
    mv -T -- "$temporary" "$marker"
    ;;
  latest-backup)
    [ "$#" = 0 ] || exit 2
    latest=''
    while IFS= read -r candidate; do
      case "${candidate##*/}" in
        [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z) ;;
        *) continue;;
      esac
      if verify_manifest "$candidate"; then latest=$candidate; fi
    done < <(find "$root/backups" -mindepth 1 -maxdepth 1 -type d ! -name '.pending-*' -print | LC_ALL=C sort)
    [ -n "$latest" ] || exit 1
    printf '%s\n' "$latest"
    ;;
  *) exit 2;;
esac
REMOTE_SCRIPT
}

verify_local_manifest() {
  local directory=$1 line digest member actual listed='|' required
  shift
  [ -d "$directory" ] && [ ! -L "$directory" ] || die "artifact directory is unsafe: $directory"
  [ -f "$directory/SHA256SUMS" ] && [ ! -L "$directory/SHA256SUMS" ] \
    || die 'artifact checksum manifest is missing or indirect'
  [ -z "$(find "$directory" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
    || die 'artifact contains a link or special member'
  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^([a-f0-9]{64})[[:space:]][\ \*](.+)$ ]] \
      || die 'artifact checksum manifest is malformed'
    digest=${BASH_REMATCH[1]}
    member=${BASH_REMATCH[2]}
    case "$member" in ''|/*|*'?'*|*'['*|*'*'*|*'\'*|*'|'*|.|..|../*|*/../*|*/..) \
      die 'artifact checksum member is unsafe';; esac
    case "$listed" in *"|$member|"*) die 'artifact checksum member is duplicated';; esac
    [ -f "$directory/$member" ] && [ ! -L "$directory/$member" ] \
      || die 'artifact checksum member is not a regular file'
    listed="${listed}${member}|"
  done <"$directory/SHA256SUMS"
  while IFS= read -r -d '' actual; do
    member=${actual#"$directory"/}
    [ "$member" = SHA256SUMS ] || case "$listed" in *"|$member|"*) ;; *) die 'artifact has an unmanifested file';; esac
  done < <(find "$directory" -type f -print0)
  for required in "$@"; do
    case "$listed" in *"|$required|"*) ;; *) die "required artifact member is unmanifested: $required";; esac
  done
  (cd "$directory" && sha256sum --check --strict SHA256SUMS >/dev/null) \
    || die 'artifact checksum verification failed'
}

build_release() {
  local release release_id release_root_canonical requested_release
  requested_release=${HAPPY_AGENT_RELEASE_PATH:-}
  if [ -n "$requested_release" ]; then
    case "$requested_release" in
      /|~*|*'?'*|*'['*|*'*'*|!/*) die 'reusable release path must be a safe absolute path';;
    esac
    [ -d "$requested_release" ] && [ ! -L "$requested_release" ] \
      || die 'reusable release path is missing or indirect'
    release=$requested_release
  elif ! release=$($BUILD_SCRIPT); then
    die 'release builder failed'
  fi
  [ -d "$release" ] && [ ! -L "$release" ] || die 'release builder returned an unsafe artifact path'
  release=$(realpath "$release")
  release_root_canonical=$(realpath "$RELEASE_ROOT")
  case "$release" in "$release_root_canonical"/*) ;; *) die 'release builder returned a path outside the release root';; esac
  release_id=${release##*/}
  [[ "$release_id" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{7,40}$ ]] || die 'release builder returned an unsafe id'
  verify_local_manifest "$release" .env compose.yml nginx.conf
  printf '%s\n' "$release"
}

upload_directory() {
  local kind=$1 id=$2 directory=$3 member
  local -a upload_members=()
  validate_identifier "$kind"
  validate_identifier "$id"
  while IFS= read -r -d '' member; do upload_members+=("$member"); done \
    < <(find "$directory" -mindepth 1 -maxdepth 1 -print0)
  [ "${#upload_members[@]}" -gt 0 ] || die 'refusing to upload an empty artifact directory'
  remote_control prepare-staging "$kind" "$id"
  scp -r "${SSH_OPTIONS[@]}" "${upload_members[@]}" \
    "$REMOTE:$REMOTE_ROOT/staging/.pending-$kind-$id/"
  remote_control "publish-$kind" "$id"
}

prepare_http_release() (
  set -euo pipefail
  local release=$1 release_id=$2 pending="$STAGING_ROOT/.http-$release_id-$$" keep=0
  cleanup_http_release() {
    local status=$?
    if [ "$keep" = 0 ] && [ -d "$pending" ]; then /bin/rm -rf -- "$pending"; fi
    return "$status"
  }
  trap cleanup_http_release EXIT
  [ ! -e "$pending" ] || die 'HTTP release staging path already exists'
  install -d -m 0700 "$pending"
  cp -R "$release/." "$pending/"
  [ -f "$pending/nginx-http.conf" ] && [ ! -L "$pending/nginx-http.conf" ] \
    || die 'release has no safe HTTP bootstrap Nginx config'
  cp -- "$pending/nginx-http.conf" "$pending/nginx.conf"
  chmod 0600 "$pending/nginx.conf"
  (
    cd "$pending"
    find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | sed 's#^./##' \
      | while IFS= read -r file; do sha256sum "$file"; done >SHA256SUMS
  )
  chmod 0600 "$pending/SHA256SUMS"
  verify_local_manifest "$pending" .env compose.yml nginx.conf
  keep=1
  printf '%s\n' "$pending"
)

pull_latest_backup() {
  local remote_backup backup_id pending complete
  remote_backup=$(remote_control latest-backup)
  case "$remote_backup" in
    "$REMOTE_ROOT"/backups/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z) ;;
    *) die 'remote latest-backup returned an unsafe path';;
  esac
  backup_id=${remote_backup##*/}
  pending="$RECOVERY_ROOT/.pending-$backup_id"
  complete="$RECOVERY_ROOT/$backup_id"
  [ ! -e "$pending" ] && [ ! -e "$complete" ] || die 'local recovery package already exists'
  install -d -m 0700 "$pending"
  if ! scp -r "${SSH_OPTIONS[@]}" "$REMOTE:$remote_backup/." "$pending/"; then
    /bin/rm -rf -- "$pending"
    return 1
  fi
  chmod 0700 "$pending"
  find "$pending" -type f -exec chmod 0600 {} +
  verify_local_manifest "$pending"
  mv -- "$pending" "$complete"
  log "recovery package pulled: $backup_id"
}

build_bootstrap_bundle() (
  set -euo pipefail
  local timestamp source_short id pending keep=0
  timestamp=${HAPPY_AGENT_BOOTSTRAP_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}
  [[ "$timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || die 'invalid bootstrap timestamp'
  source_short=$(git -C "$REPOSITORY_ROOT" rev-parse --short HEAD)
  [[ "$source_short" =~ ^[a-f0-9]{7,40}$ ]] || die 'invalid bootstrap source commit'
  id="bootstrap-$timestamp-$source_short"
  pending="$STAGING_ROOT/.$id-$$"
  cleanup_bootstrap_build() {
    local status=$?
    if [ "$keep" = 0 ] && [ -d "$pending" ]; then /bin/rm -rf -- "$pending"; fi
    return "$status"
  }
  trap cleanup_bootstrap_build EXIT
  [ ! -e "$pending" ] || die 'bootstrap staging path already exists'
  install -d -m 0700 "$pending" "$pending/scripts" "$pending/systemd"
  install -m 0700 "$SCRIPT_DIR/scripts/common.sh" "$pending/scripts/common.sh"
  install -m 0700 "$SCRIPT_DIR/scripts/bootstrap-host.sh" "$pending/scripts/bootstrap-host.sh"
  install -m 0600 "$SCRIPT_DIR/nginx/ip-http.conf.template" "$pending/nginx.conf"
  install -m 0600 "$SCRIPT_DIR/systemd/happy-agent-cert-renew.service" \
    "$pending/systemd/happy-agent-cert-renew.service"
  install -m 0600 "$SCRIPT_DIR/systemd/happy-agent-cert-renew.timer" \
    "$pending/systemd/happy-agent-cert-renew.timer"
  printf '%s\n' 'NGINX_IMAGE=nginx:stable-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46' \
    >"$pending/image.env"
  chmod 0600 "$pending/image.env"
  (
    cd "$pending"
    find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | sed 's#^./##' \
      | while IFS= read -r file; do sha256sum "$file"; done >SHA256SUMS
  )
  chmod 0600 "$pending/SHA256SUMS"
  verify_local_manifest "$pending" scripts/common.sh scripts/bootstrap-host.sh nginx.conf image.env
  keep=1
  printf '%s\n%s\n' "$id" "$pending"
)

validate_local_smoke_input() {
  local raw=$1 label=$2 mode
  case "$raw" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die "$label path must be a safe absolute path";; esac
  [ ! -L "$raw" ] && [ -f "$raw" ] && [ -r "$raw" ] || die "$label must be a non-symlink regular file"
  mode=$(file_mode "$raw")
  [ "$mode" = 600 ] || die "$label must have mode 0600"
}

run_bootstrap() {
  local details
  local -a bootstrap_details=()
  "$CLOUD_SCRIPT"
  details=$(build_bootstrap_bundle)
  while IFS= read -r detail; do bootstrap_details+=("$detail"); done <<<"$details"
  [ "${#bootstrap_details[@]}" = 2 ] || die 'bootstrap builder returned invalid details'
  bootstrap_id=${bootstrap_details[0]}
  bootstrap_bundle=${bootstrap_details[1]}
  cleanup_bootstrap() {
    local status=$?
    remote_control cleanup-staging bootstrap "$bootstrap_id" >/dev/null 2>&1 || :
    if [ -d "$bootstrap_bundle" ]; then /bin/rm -rf -- "$bootstrap_bundle"; fi
    return "$status"
  }
  trap cleanup_bootstrap EXIT
  upload_directory bootstrap "$bootstrap_id" "$bootstrap_bundle"
  remote_exec "$REMOTE_ROOT/bootstrap/$bootstrap_id/scripts/bootstrap-host.sh"
  remote_control start-bootstrap "$bootstrap_id"
  trap - EXIT
  cleanup_bootstrap
  log "bootstrap applied: $bootstrap_id"
}

run_release() {
  local release release_id backup_timestamp activation_timestamp
  load_acr_credentials
  release=$(build_release)
  release_id=${release##*/}
  cleanup_release() {
    local status=$?
    remote_control cleanup-staging release "$release_id" >/dev/null 2>&1 || :
    return "$status"
  }
  trap cleanup_release EXIT
  remote_registry_login
  upload_directory release "$release_id" "$release"
  remote_control pull-release "$release_id"
  trap - EXIT
  cleanup_release
  backup_timestamp=$(date -u +%Y%m%dT%H%M%SZ)
  activation_timestamp=$(node -e '
const value = process.argv[1];
const match = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(value);
if (!match) process.exit(1);
const date = new Date(Date.UTC(...match.slice(1).map(Number).map((part, index) => index === 1 ? part - 1 : part)));
date.setUTCSeconds(date.getUTCSeconds() + 1);
const pad = number => String(number).padStart(2, "0");
process.stdout.write(`${date.getUTCFullYear()}${pad(date.getUTCMonth()+1)}${pad(date.getUTCDate())}T${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}Z\n`);
' "$backup_timestamp")
  [[ "$activation_timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] \
    && [ "$activation_timestamp" != "$backup_timestamp" ] \
    || die 'unable to allocate distinct release backup timestamps'
  remote_exec env "HAPPY_AGENT_TIMESTAMP=$backup_timestamp" "$REMOTE_ROOT/current/scripts/backup.sh"
  remote_exec env "HAPPY_AGENT_TIMESTAMP=$activation_timestamp" \
    "$REMOTE_ROOT/releases/$release_id/scripts/activate-release.sh" "$release_id"
  pull_latest_backup
  log "release deployed: $release_id"
}

run_migrate() {
  local local_marker remote_marker release release_id migration migration_id http_release
  local session_file run_id_file marker_pending
  local_marker="$MIGRATION_ROOT/first-migration.marker"
  [ ! -e "$local_marker" ] && [ ! -L "$local_marker" ] \
    || die 'initial migration already has a local completion marker'
  remote_marker=$(remote_control migration-marker-status)
  case "$remote_marker" in
    ready) ;;
    complete) die 'initial migration already has a remote completion marker';;
    unsafe) die 'remote state is not the pristine initial migration target';;
    *) die 'remote initial migration state is ambiguous';;
  esac

  load_acr_credentials
  release=$(build_release)
  release_id=${release##*/}
  session_file=${PUBLIC_SMOKE_SESSION_FILE:-"$SOURCE_STATE_ROOT/deploy/.local/production/public-smoke-session"}
  run_id_file=${PUBLIC_SMOKE_RUN_ID_FILE:-"$SOURCE_STATE_ROOT/deploy/.local/production/public-smoke-run-id"}
  validate_local_smoke_input "$session_file" 'public smoke session'
  validate_local_smoke_input "$run_id_file" 'public smoke run id'
  migration=$(SOURCE_STATE_ROOT="$SOURCE_STATE_ROOT" \
    PUBLIC_SMOKE_SESSION_FILE="$session_file" PUBLIC_SMOKE_RUN_ID_FILE="$run_id_file" \
    "$EXPORT_SCRIPT")
  migration_id=${migration##*/}
  validate_identifier "$migration_id"
  verify_local_manifest "$migration" initial.dump media.tar agent-master-key metadata.env source-validation.json
  http_release=$(prepare_http_release "$release" "$release_id")

  cleanup_migrate() {
    local status=$?
    remote_control cleanup-staging release "$release_id" >/dev/null 2>&1 || :
    remote_control cleanup-staging release "http-$release_id" >/dev/null 2>&1 || :
    remote_control cleanup-staging migration "$migration_id" >/dev/null 2>&1 || :
    remote_control cleanup-staging smoke "$release_id" >/dev/null 2>&1 || :
    if [ -d "$http_release" ]; then /bin/rm -rf -- "$http_release"; fi
    if [ -n "${marker_pending:-}" ] && [ -f "$marker_pending" ]; then /bin/rm -f -- "$marker_pending"; fi
    return "$status"
  }
  trap cleanup_migrate EXIT
  remote_registry_login
  upload_directory release "$release_id" "$release"
  upload_directory release "http-$release_id" "$http_release"
  upload_directory migration "$migration_id" "$migration"
  remote_control pull-release "$release_id"
  remote_control prepare-staging smoke "$release_id"
  scp "${SSH_OPTIONS[@]}" "$session_file" \
    "$REMOTE:$REMOTE_ROOT/staging/.pending-smoke-$release_id/public-smoke-session"
  scp "${SSH_OPTIONS[@]}" "$run_id_file" \
    "$REMOTE:$REMOTE_ROOT/staging/.pending-smoke-$release_id/public-smoke-run-id"
  remote_control install-smoke "$release_id"
  remote_control start-http-release "$release_id"
  remote_exec "$REMOTE_ROOT/current/scripts/restore-initial-data.sh" \
    "$REMOTE_ROOT/migrations/$migration_id" --initial-empty-target
  remote_control start-http-release "$release_id"
  remote_exec "$REMOTE_ROOT/current/scripts/issue-certificate.sh"
  remote_exec "$REMOTE_ROOT/releases/$release_id/scripts/activate-release.sh" "$release_id"
  pull_latest_backup

  marker_pending="$MIGRATION_ROOT/.first-migration.$$.tmp"
  [ ! -e "$marker_pending" ] || die 'local migration marker staging path already exists'
  install -m 0600 /dev/null "$marker_pending"
  printf 'release_id=%s\nmigration_id=%s\n' "$release_id" "$migration_id" >"$marker_pending"
  remote_control write-migration-marker "$release_id" "$migration_id"
  mv -- "$marker_pending" "$local_marker"
  marker_pending=''
  trap - EXIT
  cleanup_migrate
  log "initial migration deployed: $migration_id"
}

case "$command_name" in
  bootstrap) run_bootstrap;;
  migrate) run_migrate;;
  release) run_release;;
  status) remote_exec "$REMOTE_ROOT/current/scripts/status.sh";;
  backup)
    remote_exec "$REMOTE_ROOT/current/scripts/backup.sh"
    pull_latest_backup
    ;;
  rollback)
    load_acr_credentials
    remote_registry_login
    remote_exec "$REMOTE_ROOT/current/scripts/rollback.sh" "$rollback_release_id"
    ;;
esac
