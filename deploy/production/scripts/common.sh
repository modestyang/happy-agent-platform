#!/usr/bin/env bash
# Shared non-secret safety primitives for production server operations.
set -euo pipefail

: "${HAPPY_AGENT_ROOT:=/opt/happy-agent}"
: "${HAPPY_AGENT_LOCK_FILE:=$HAPPY_AGENT_ROOT/.operation.lock}"
: "${HAPPY_AGENT_OS_RELEASE_PATH:=/etc/os-release}"
: "${HAPPY_AGENT_FSTAB_PATH:=/etc/fstab}"
: "${HAPPY_AGENT_SWAPFILE:=/swapfile}"
: "${HAPPY_AGENT_SYSTEMD_UNIT_DIR:=/etc/systemd/system}"
: "${HAPPY_AGENT_APT_KEYRING_DIR:=/etc/apt/keyrings}"
: "${HAPPY_AGENT_APT_SOURCES_DIR:=/etc/apt/sources.list.d}"
export HAPPY_AGENT_ROOT HAPPY_AGENT_LOCK_FILE HAPPY_AGENT_APT_KEYRING_DIR HAPPY_AGENT_APT_SOURCES_DIR

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
require_root() { [ "$(id -u)" = 0 ] || die "must run as root"; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "required command unavailable: $1"; }
require_file() { [ -f "$1" ] && [ -r "$1" ] || die "required file unavailable: $1"; }

validate_root() {
  case "$HAPPY_AGENT_ROOT" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die "unsafe HAPPY_AGENT_ROOT";; esac
  HAPPY_AGENT_ROOT=$(realpath -m -- "$HAPPY_AGENT_ROOT")
  export HAPPY_AGENT_ROOT
}
validate_descendant() {
  local target root
  root=$(realpath -m -- "$HAPPY_AGENT_ROOT")
  case "$1" in ''|/|~*|*'?'*|*'['*|*'*'*|!/*) die "unsafe path";; esac
  target=$(realpath -m -- "$1")
  case "$target" in "$root"|"$root"/*) printf '%s\n' "$target";; *) die "path escapes configured root";; esac
}
validate_release_id() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "unsafe release id"
  printf '%s\n' "$1"
}
release_path() { validate_descendant "$HAPPY_AGENT_ROOT/releases/$(validate_release_id "$1")"; }
verify_manifest() {
  local directory
  directory=$(validate_descendant "$1")
  require_file "$directory/SHA256SUMS"
  (cd "$directory" && sha256sum --check SHA256SUMS >/dev/null)
}
with_lock() {
  if [ "${HAPPY_AGENT_LOCK_HELD:-}" = 1 ]; then "$@"; return; fi
  validate_root
  mkdir -p -- "$HAPPY_AGENT_ROOT"
  local lock_parent
  lock_parent=$(dirname -- "$HAPPY_AGENT_LOCK_FILE")
  mkdir -p -- "$lock_parent"
  exec 9>"$HAPPY_AGENT_LOCK_FILE"
  flock -x 9
  (
    export HAPPY_AGENT_LOCK_HELD=1
    "$@"
  )
}
compose_release() {
  local release
  release=$(validate_descendant "$1")
  require_file "$release/.env"
  require_file "$release/compose.yml"
  docker compose -p happy-agent --env-file "$release/.env" -f "$release/compose.yml" "${@:2}"
}
current_release() {
  [ -L "$HAPPY_AGENT_ROOT/current" ] || die "no active release"
  local resolved
  resolved=$(realpath -m -- "$HAPPY_AGENT_ROOT/current")
  validate_descendant "$resolved"
}
switch_current() {
  local release parent temporary relative
  release=$(validate_descendant "$1")
  parent="$HAPPY_AGENT_ROOT"
  relative=${release#"$parent"/}
  temporary="$parent/.current.$$.tmp"
  rm -f -- "$temporary"
  ln -s -- "$relative" "$temporary"
  mv -Tf -- "$temporary" "$parent/current"
}
certificate_dir() { printf '%s/certificates/production/live/happy-agent-ip\n' "$HAPPY_AGENT_ROOT"; }
validate_certificate() {
  local directory
  directory=$(certificate_dir)
  require_file "$directory/fullchain.pem"
  require_file "$directory/privkey.pem"
  openssl x509 -in "$directory/fullchain.pem" -noout -ext subjectAltName | grep -F 'IP Address:39.101.65.254' >/dev/null
  openssl x509 -in "$directory/fullchain.pem" -noout -checkend 172800
  openssl verify "$directory/fullchain.pem" >/dev/null
}
