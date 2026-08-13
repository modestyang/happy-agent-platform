#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"

readonly OS_RELEASE_PATH=/etc/os-release
readonly FSTAB_PATH=/etc/fstab
readonly SWAPFILE=/swapfile
readonly SYSTEMD_UNIT_DIR=/etc/systemd/system
readonly APT_KEYRING_DIR=/etc/apt/keyrings
readonly APT_SOURCES_DIR=/etc/apt/sources.list.d

bootstrap_preflight() {
  validate_root
  require_root
  require_file "$OS_RELEASE_PATH"
  local ID='' VERSION_ID=''
  source "$OS_RELEASE_PATH"
  [ "$ID" = ubuntu ] && [ "$VERSION_ID" = 22.04 ] || die 'Ubuntu 22.04 is required'
  [ "$(uname -m)" = x86_64 ] || die 'x86_64 is required'
  require_command apt-get
  require_command awk
  require_command df
  require_command ss
  df -Pk "$(dirname -- "$HAPPY_AGENT_ROOT")" | awk 'NR == 2 {exit ($4 < 3145728)}' \
    || die 'insufficient free disk'
  ! ss -ltn '( sport = :80 or sport = :443 )' | grep -q LISTEN \
    || die 'ports 80 and 443 must be free'
}

install_runtime() {
  local key_tmp
  apt-get update
  apt-get install -y ca-certificates curl file gnupg openssl tar util-linux
  require_command curl
  require_command fallocate
  require_command file
  require_command flock
  require_command gpg
  install -d -m 0755 "$APT_KEYRING_DIR" "$APT_SOURCES_DIR"
  key_tmp="$APT_KEYRING_DIR/.docker.gpg.$$"
  [ ! -e "$key_tmp" ] || die 'temporary Docker key already exists'
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor >"$key_tmp"
  chmod 0644 "$key_tmp"
  mv -Tf -- "$key_tmp" "$APT_KEYRING_DIR/docker.gpg"
  printf 'deb [arch=amd64 signed-by=%s/docker.gpg] https://download.docker.com/linux/ubuntu jammy stable\n' \
    "$APT_KEYRING_DIR" >"$APT_SOURCES_DIR/docker.list"
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
}

configure_swap() {
  if [ ! -e "$SWAPFILE" ]; then
    install -m 0600 /dev/null "$SWAPFILE"
    fallocate -l 2G "$SWAPFILE"
    mkswap "$SWAPFILE"
    swapon "$SWAPFILE"
  else
    [ -f "$SWAPFILE" ] && [ ! -L "$SWAPFILE" ] || die 'existing swapfile is not a regular file'
    [ "$(stat -c %s "$SWAPFILE")" = 2147483648 ] || die 'existing swapfile is not 2GB'
    file -b "$SWAPFILE" | grep -qi 'swap' || die 'existing swapfile has no swap signature'
    if ! swapon --show=NAME | grep -Fxq "$SWAPFILE"; then swapon "$SWAPFILE"; fi
  fi
  chmod 0600 "$SWAPFILE"
  touch "$FSTAB_PATH"
  grep -Fqx "$SWAPFILE none swap sw 0 0" "$FSTAB_PATH" \
    || printf '%s\n' "$SWAPFILE none swap sw 0 0" >>"$FSTAB_PATH"
}

create_host_layout() {
  local initial secret
  install -d -m 0750 "$HAPPY_AGENT_ROOT" "$HAPPY_AGENT_ROOT/releases" \
    "$HAPPY_AGENT_ROOT/data" "$HAPPY_AGENT_ROOT/certificates" \
    "$HAPPY_AGENT_ROOT/certificates/staging" "$HAPPY_AGENT_ROOT/certificates/production" \
    "$HAPPY_AGENT_ROOT/backups" "$HAPPY_AGENT_ROOT/logs"
  install -d -m 0755 "$HAPPY_AGENT_ROOT/data/acme-webroot"
  install -d -m 0700 "$HAPPY_AGENT_ROOT/secrets" "$HAPPY_AGENT_ROOT/state" \
    "$HAPPY_AGENT_ROOT/state/generations"

  initial=$(generation_path initial-empty)
  install -d -m 0700 "$initial" "$initial/postgres"
  install -d -m 0750 "$initial/media"
  [ ! -e "$initial/agent-master-key" ] || die 'bootstrap must not create an Agent master key'

  if [ -L "$HAPPY_AGENT_ROOT/state/current" ]; then
    current_generation >/dev/null
  elif [ -e "$HAPPY_AGENT_ROOT/state/current" ]; then
    die 'state/current exists and is not a symlink'
  else
    switch_state_current "$initial"
  fi

  for secret in postgres-password fitness-db-password agent-db-password; do
    if [ -e "$HAPPY_AGENT_ROOT/secrets/$secret" ]; then
      [ -f "$HAPPY_AGENT_ROOT/secrets/$secret" ] && [ ! -L "$HAPPY_AGENT_ROOT/secrets/$secret" ] \
        && [ -s "$HAPPY_AGENT_ROOT/secrets/$secret" ] || die 'existing database password file is invalid'
    else
      umask 077
      openssl rand -hex 32 >"$HAPPY_AGENT_ROOT/secrets/$secret"
    fi
    chmod 0600 "$HAPPY_AGENT_ROOT/secrets/$secret"
  done
  [ ! -e "$HAPPY_AGENT_ROOT/secrets/agent-master-key" ] \
    || die 'legacy Agent master key path is not supported'
}

install_systemd_units() {
  install -d -m 0755 "$SYSTEMD_UNIT_DIR"
  install -m 0644 "$SCRIPT_DIR/../systemd/happy-agent-cert-renew.service" \
    "$SYSTEMD_UNIT_DIR/happy-agent-cert-renew.service"
  install -m 0644 "$SCRIPT_DIR/../systemd/happy-agent-cert-renew.timer" \
    "$SYSTEMD_UNIT_DIR/happy-agent-cert-renew.timer"
  systemctl daemon-reload
  if [ -f "$(certificate_dir)/cert.pem" ] || [ -f "$(certificate_dir)/fullchain.pem" ] \
      || [ -f "$(certificate_dir)/chain.pem" ] || [ -f "$(certificate_dir)/privkey.pem" ]; then
    validate_certificate
    systemctl enable --now happy-agent-cert-renew.timer
  fi
}

bootstrap_core() {
  install_runtime
  configure_swap
  create_host_layout
  install_systemd_units
  log "bootstrap ready; swap=$SWAPFILE; root=$HAPPY_AGENT_ROOT"
}

bootstrap_preflight
with_lock bootstrap_core
