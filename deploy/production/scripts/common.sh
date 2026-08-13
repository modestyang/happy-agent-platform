#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

: "${HAPPY_AGENT_ROOT:=/opt/happy-agent}"
if [ "${HAPPY_AGENT_PUBLIC_ORIGIN:-}" != 'https://39.101.65.254' ]; then
  unset HAPPY_AGENT_PUBLIC_ORIGIN 2>/dev/null || exit 1
  HAPPY_AGENT_PUBLIC_ORIGIN='https://39.101.65.254'
fi
readonly HAPPY_AGENT_PUBLIC_ORIGIN

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
require_root() { [ "$(id -u)" = 0 ] || die 'must run as root'; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "required command unavailable: $1"; }
require_file() { [ -f "$1" ] && [ -r "$1" ] || die "required file unavailable: $1"; }

validate_absolute_path() {
  case "$1" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die 'unsafe absolute path';; esac
  realpath -m -- "$1"
}

validate_root() {
  HAPPY_AGENT_ROOT=$(validate_absolute_path "$HAPPY_AGENT_ROOT")
  export HAPPY_AGENT_ROOT
}

validate_descendant() {
  local target root
  root=$(realpath -m -- "$HAPPY_AGENT_ROOT")
  target=$(validate_absolute_path "$1")
  case "$target" in "$root"/*) printf '%s\n' "$target";; *) die 'path must be a root descendant';; esac
}

validate_identifier() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die 'unsafe identifier'
  printf '%s\n' "$1"
}

release_path() { validate_descendant "$HAPPY_AGENT_ROOT/releases/$(validate_identifier "$1")"; }
generation_path() { validate_descendant "$HAPPY_AGENT_ROOT/state/generations/$(validate_identifier "$1")"; }

verify_manifest() {
  local directory line digest member actual listed='|'
  directory=$(validate_descendant "$1")
  require_file "$directory/SHA256SUMS"
  [ ! -L "$directory/SHA256SUMS" ] || die 'checksum manifest must be a regular file'
  [ -z "$(find "$directory" -mindepth 1 ! -type f ! -type d -print -quit)" ] || die 'directory contains a special member'

  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^([a-fA-F0-9]{64})[[:space:]][\ \*](.+)$ ]] || die 'malformed checksum manifest line'
    digest=${BASH_REMATCH[1]}
    member=${BASH_REMATCH[2]}
    case "$member" in ''|/*|*'?'*|*'['*|*'*'*|*'\\'*|*'|'*|.|..|../*|*/../*|*/..) die 'unsafe checksum manifest member';; esac
    case "$listed" in *"|$member|"*) die 'duplicate checksum manifest member';; esac
    actual=$(validate_descendant "$directory/$member")
    [ -f "$actual" ] && [ ! -L "$actual" ] || die 'checksum member is not a regular file'
    listed="${listed}${member}|"
  done <"$directory/SHA256SUMS"
  [ "$listed" != '|' ] || die 'empty checksum manifest'

  while IFS= read -r -d '' actual; do
    member=${actual#"$directory"/}
    [ "$member" = SHA256SUMS ] || case "$listed" in *"|$member|"*) ;; *) die 'unmanifested regular file';; esac
  done < <(find "$directory" -type f -print0)

  if ! (cd "$directory" && sha256sum --check --strict SHA256SUMS >/dev/null); then
    log 'ERROR: checksum manifest verification failed'
    return 1
  fi
  shift
  for member in "$@"; do
    case "$listed" in *"|$member|"*) ;; *) die "consumed file missing from checksum manifest: $member";; esac
  done
}

with_lock() {
  local lock_file
  validate_root
  mkdir -p -- "$HAPPY_AGENT_ROOT"
  lock_file=$(validate_descendant "$HAPPY_AGENT_ROOT/.operation.lock")
  exec 9>"$lock_file"
  flock -x 9
  "$@"
}

compose_release() {
  local release
  release=$(validate_descendant "$1")
  require_file "$release/.env"
  require_file "$release/compose.yml"
  HAPPY_AGENT_ROOT="$HAPPY_AGENT_ROOT" docker compose -p happy-agent \
    --env-file "$release/.env" -f "$release/compose.yml" "${@:2}"
}

compose_temporary() {
  local project runtime_root release
  project=$(validate_identifier "$1")
  runtime_root=$(validate_descendant "$2")
  release=$(validate_descendant "$3")
  HAPPY_AGENT_ROOT="$runtime_root" docker compose -p "$project" \
    --env-file "$release/.env" -f "$release/compose.yml" "${@:4}"
}

current_release() {
  local resolved relative
  [ -L "$HAPPY_AGENT_ROOT/current" ] || die 'no active release'
  resolved=$(realpath -m -- "$HAPPY_AGENT_ROOT/current")
  resolved=$(validate_descendant "$resolved")
  relative=${resolved#"$HAPPY_AGENT_ROOT/releases/"}
  [ "$relative" != "$resolved" ] && [[ "$relative" != */* ]] || die 'active release link escapes releases'
  [ -d "$resolved" ] || die 'active release is missing'
  printf '%s\n' "$resolved"
}

current_generation() {
  local resolved relative
  [ -L "$HAPPY_AGENT_ROOT/state/current" ] || die 'no active state generation'
  resolved=$(realpath -m -- "$HAPPY_AGENT_ROOT/state/current")
  resolved=$(validate_descendant "$resolved")
  relative=${resolved#"$HAPPY_AGENT_ROOT/state/generations/"}
  [ "$relative" != "$resolved" ] && [[ "$relative" != */* ]] || die 'active state link escapes generations'
  validate_identifier "$relative" >/dev/null
  [ -d "$resolved/postgres" ] && [ ! -L "$resolved/postgres" ] \
    && [ -d "$resolved/media" ] && [ ! -L "$resolved/media" ] \
    || die 'active state generation is incomplete or indirect'
  printf '%s\n' "$resolved"
}

atomic_switch_link() {
  local requested_link=$1 target=$2 parent root link_name link_path temporary relative
  case "$requested_link" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) log 'ERROR: unsafe symlink path'; return 1;; esac
  link_name=${requested_link##*/}
  if ! [[ "$link_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    log 'ERROR: unsafe symlink name'
    return 1
  fi
  parent=$(validate_absolute_path "$(dirname -- "$requested_link")") || return 1
  root=$(realpath -m -- "$HAPPY_AGENT_ROOT")
  case "$parent" in "$root"|"$root"/*) ;; *) log 'ERROR: symlink parent escapes root'; return 1;; esac
  link_path="$parent/$link_name"
  target=$(validate_descendant "$target") || return 1
  temporary="$parent/.$link_name.$$.tmp"
  if [ -e "$temporary" ] || [ -L "$temporary" ]; then
    log 'ERROR: temporary link already exists'
    return 1
  fi
  relative=${target#"$parent"/}
  if [ "$relative" = "$target" ]; then
    log 'ERROR: link target must share its parent tree'
    return 1
  fi
  ln -s -- "$relative" "$temporary" || return 1
  if ! mv -Tf -- "$temporary" "$link_path"; then
    rm -f -- "$temporary"
    return 1
  fi
}

switch_current() { atomic_switch_link "$HAPPY_AGENT_ROOT/current" "$1"; }
switch_state_current() { atomic_switch_link "$HAPPY_AGENT_ROOT/state/current" "$1"; }

declared_service_image() {
  local release=$1 service=$2 image
  case "$service" in app|nginx|postgres) ;; *) die 'unsupported Compose service';; esac
  image=$(compose_release "$release" config "$service" \
    | awk '$1 == "image:" {image = $2; count++} END {if (count == 1) print image; else exit 1}')
  [ -n "$image" ] && [[ "$image" != *[[:space:]]* ]] || die "unable to resolve normalized image for $service"
  printf '%s\n' "$image"
}

service_runtime() {
  local release=$1 service=$2 container_id lines
  container_id=$(compose_release "$release" ps -q "$service")
  lines=$(printf '%s\n' "$container_id" | sed '/^$/d' | wc -l | tr -d ' ')
  [ "$lines" = 1 ] || return 1
  docker inspect --format '{{.Id}}|{{.Config.Image}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id"
}

service_matches_release() {
  local release=$1 service=$2 expected runtime id image status health
  expected=$(declared_service_image "$release" "$service") || return 1
  runtime=$(service_runtime "$release" "$service") || return 1
  IFS='|' read -r id image status health <<<"$runtime"
  [ -n "$id" ] && [ "$image" = "$expected" ] && [ "$status" = running ] && [ "$health" = healthy ]
}

postgres_identity() {
  local release=$1 runtime id image status health
  runtime=$(service_runtime "$release" postgres) || return 1
  IFS='|' read -r id image status health <<<"$runtime"
  [ -n "$id" ] && [ "$status" = running ] && [ "$health" = healthy ] || return 1
  printf '%s|%s\n' "$id" "$image"
}

postgres_unchanged_healthy() {
  local release=$1 expected=$2 runtime id image status health
  runtime=$(service_runtime "$release" postgres) || return 1
  IFS='|' read -r id image status health <<<"$runtime"
  [ "$id|$image" = "$expected" ] && [ "$status" = running ] && [ "$health" = healthy ]
}

wait_application_runtime() {
  local release=$1 postgres_before=$2 attempts=${3:-12} interval=${4:-5} attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if postgres_unchanged_healthy "$release" "$postgres_before" \
        && service_matches_release "$release" app \
        && service_matches_release "$release" nginx; then
      return 0
    fi
    sleep "$interval"
  done
  return 1
}

_recover_previous_release() {
  local attempted=$1 previous=$2 postgres_before=$3 attempts=${4:-12} interval=${5:-5}
  [ -n "$previous" ] || return 1
  if ! compose_release "$attempted" stop app nginx; then
    log 'recovery could not fully stop the attempted App/Nginx; continuing'
  fi
  if ! switch_current "$previous"; then
    log 'recovery could not restore the previous release link'
    return 1
  fi
  compose_release "$previous" up -d --no-deps app nginx || return 1
  wait_application_runtime "$previous" "$postgres_before" "$attempts" "$interval" || return 1
  public_smoke || return 1
  log 'previous release identity, health, PostgreSQL continuity, and public smoke recovered'
}

public_smoke() (
  set -euo pipefail
  local temporary headers body code
  install -d -m 0750 "$HAPPY_AGENT_ROOT/logs"
  temporary=$(mktemp -d "$HAPPY_AGENT_ROOT/logs/.public-smoke.XXXXXX")
  temporary=$(validate_descendant "$temporary")
  trap 'rm -rf -- "$temporary"' EXIT

  smoke_request() {
    local path=$1 expected_code=$2 expected_type=$3 accept=${4:-}
    headers="$temporary/headers"
    body="$temporary/body"
    if [ -n "$accept" ]; then
      code=$(curl --silent --show-error --no-buffer --max-time 15 --http1.1 \
        --header "Accept: $accept" --header 'Cache-Control: no-cache' \
        --dump-header "$headers" --output "$body" --write-out '%{http_code}' \
        "$HAPPY_AGENT_PUBLIC_ORIGIN$path") || return 1
    else
      code=$(curl --silent --show-error --max-time 15 --http1.1 \
        --dump-header "$headers" --output "$body" --write-out '%{http_code}' \
        "$HAPPY_AGENT_PUBLIC_ORIGIN$path") || return 1
    fi
    [ "$code" = "$expected_code" ] || return 1
    tr -d '\r' <"$headers" | grep -Eiq "^content-type:[[:space:]]*$expected_type([[:space:]]*;|[[:space:]]*$)" || return 1
  }

  smoke_request / 200 'text/html' || return 1
  [ -s "$temporary/body" ] || return 1
  smoke_request /api/v1/app/home 401 'application/problem\+json' || return 1
  smoke_request /api/v1/admin/frameworks 401 'application/problem\+json' || return 1
  smoke_request /api/v1/app/ai/runs/00000000-0000-0000-0000-000000000000/events \
    401 'application/problem\+json' 'text/event-stream' || return 1
  tr -d '\r' <"$headers" \
    | grep -Eiq '^cache-control:[[:space:]]*([^,]+,[[:space:]]*)*no-cache([[:space:]]*,|[[:space:]]*$)' \
    || return 1
)

certificate_dir() { printf '%s/certificates/production/live/happy-agent-ip\n' "$HAPPY_AGENT_ROOT"; }

validate_certificate() {
  local directory san cert_hash leaf_hash cert_key_hash private_key_hash
  directory=$(certificate_dir)
  require_file "$directory/cert.pem"
  require_file "$directory/fullchain.pem"
  require_file "$directory/chain.pem"
  require_file "$directory/privkey.pem"
  san=$(openssl x509 -in "$directory/cert.pem" -noout -ext subjectAltName \
    | sed '1d' | tr ',' '\n' \
    | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; /^[[:space:]]*$/d')
  [ "$san" = 'IP Address:39.101.65.254' ] || die 'certificate SAN set is not the exact production IP'
  openssl x509 -in "$directory/cert.pem" -noout -checkend 172800 >/dev/null \
    || die 'certificate expires within 48 hours'
  cert_hash=$(openssl x509 -in "$directory/cert.pem" -outform DER | sha256sum | awk '{print $1}')
  leaf_hash=$(openssl x509 -in "$directory/fullchain.pem" -outform DER | sha256sum | awk '{print $1}')
  [ "$cert_hash" = "$leaf_hash" ] || die 'fullchain leaf differs from certificate'
  cert_key_hash=$(openssl x509 -in "$directory/cert.pem" -noout -pubkey \
    | openssl pkey -pubin -outform DER | sha256sum | awk '{print $1}')
  private_key_hash=$(openssl pkey -in "$directory/privkey.pem" -pubout -outform DER \
    | sha256sum | awk '{print $1}')
  [ "$cert_key_hash" = "$private_key_hash" ] || die 'certificate and private key do not match'
  openssl verify -CApath /etc/ssl/certs -untrusted "$directory/chain.pem" "$directory/cert.pem" >/dev/null \
    || die 'certificate chain validation failed'
}
