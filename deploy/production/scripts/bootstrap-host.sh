#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

preflight() {
  validate_root
  require_root
  HAPPY_AGENT_OS_RELEASE_PATH=$(validate_system_path "$HAPPY_AGENT_OS_RELEASE_PATH")
  require_file "$HAPPY_AGENT_OS_RELEASE_PATH"
  # shellcheck disable=SC1090
  source "$HAPPY_AGENT_OS_RELEASE_PATH"
  [ "${ID:-}" = ubuntu ] && [ "${VERSION_ID:-}" = 22.04 ] || die "Ubuntu 22.04 is required"
  [ "$(uname -m)" = x86_64 ] || die "x86_64 is required"
  require_command apt-get; require_command df; require_command awk; require_command ss
  df -Pk "$(dirname -- "$HAPPY_AGENT_ROOT")" | awk 'NR==2 {exit ($4 < 3145728)}' || die "insufficient free disk"
  ! ss -ltn '( sport = :80 or sport = :443 )' | grep -q LISTEN || die "ports 80 and 443 must be free"
}
bootstrap() {
  preflight
  HAPPY_AGENT_FSTAB_PATH=$(validate_system_path "$HAPPY_AGENT_FSTAB_PATH")
  HAPPY_AGENT_SWAPFILE=$(validate_system_path "$HAPPY_AGENT_SWAPFILE")
  HAPPY_AGENT_SYSTEMD_UNIT_DIR=$(validate_system_path "$HAPPY_AGENT_SYSTEMD_UNIT_DIR")
  HAPPY_AGENT_APT_KEYRING_DIR=$(validate_system_path "$HAPPY_AGENT_APT_KEYRING_DIR")
  HAPPY_AGENT_APT_SOURCES_DIR=$(validate_system_path "$HAPPY_AGENT_APT_SOURCES_DIR")
  apt-get update
  apt-get install -y ca-certificates curl file gnupg openssl tar
  require_command curl; require_command gpg; require_command file
  install -d -m 0755 "$HAPPY_AGENT_APT_KEYRING_DIR" "$HAPPY_AGENT_APT_SOURCES_DIR"
  key_tmp=$(validate_system_path "$HAPPY_AGENT_APT_KEYRING_DIR/.docker.gpg.$$")
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor >"$key_tmp"
  chmod 0644 "$key_tmp"
  mv -Tf -- "$key_tmp" "$HAPPY_AGENT_APT_KEYRING_DIR/docker.gpg"
  printf 'deb [arch=amd64 signed-by=%s/docker.gpg] https://download.docker.com/linux/ubuntu jammy stable\n' "$HAPPY_AGENT_APT_KEYRING_DIR" >"$HAPPY_AGENT_APT_SOURCES_DIR/docker.list"
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  if [ ! -e "$HAPPY_AGENT_SWAPFILE" ]; then
    install -m 0600 /dev/null "$HAPPY_AGENT_SWAPFILE"
    truncate -s 2G "$HAPPY_AGENT_SWAPFILE"
    mkswap "$HAPPY_AGENT_SWAPFILE"
    swapon "$HAPPY_AGENT_SWAPFILE"
  elif [ ! -s "$HAPPY_AGENT_SWAPFILE" ]; then
    die "existing swapfile is empty"
  elif [ "$(stat -c %s "$HAPPY_AGENT_SWAPFILE")" != 2147483648 ]; then
    die "existing swapfile is not 2GB"
  elif ! file -b "$HAPPY_AGENT_SWAPFILE" | grep -qi 'swap'; then
    die "existing swapfile has no swap signature"
  elif ! swapon --show=NAME | grep -Fxq "$HAPPY_AGENT_SWAPFILE"; then
    swapon "$HAPPY_AGENT_SWAPFILE"
  fi
  touch "$HAPPY_AGENT_FSTAB_PATH"
  grep -Fqx "$HAPPY_AGENT_SWAPFILE none swap sw 0 0" "$HAPPY_AGENT_FSTAB_PATH" || printf '%s\n' "$HAPPY_AGENT_SWAPFILE none swap sw 0 0" >>"$HAPPY_AGENT_FSTAB_PATH"
  install -d -m 0750 "$HAPPY_AGENT_ROOT" "$HAPPY_AGENT_ROOT/releases" "$HAPPY_AGENT_ROOT/data" "$HAPPY_AGENT_ROOT/data/postgres" "$HAPPY_AGENT_ROOT/data/media" "$HAPPY_AGENT_ROOT/certificates" "$HAPPY_AGENT_ROOT/certificates/staging" "$HAPPY_AGENT_ROOT/certificates/production" "$HAPPY_AGENT_ROOT/backups" "$HAPPY_AGENT_ROOT/logs"
  install -d -m 0755 "$HAPPY_AGENT_ROOT/data/acme-webroot"
  install -d -m 0700 "$HAPPY_AGENT_ROOT/secrets"
  for secret in postgres-password fitness-db-password agent-db-password; do
    if [ -e "$HAPPY_AGENT_ROOT/secrets/$secret" ] && [ ! -s "$HAPPY_AGENT_ROOT/secrets/$secret" ]; then die "existing password file is empty"; fi
    if [ ! -e "$HAPPY_AGENT_ROOT/secrets/$secret" ]; then umask 077; openssl rand -hex 32 >"$HAPPY_AGENT_ROOT/secrets/$secret"; fi
    chmod 0600 "$HAPPY_AGENT_ROOT/secrets/$secret"
  done
  install -d -m 0755 "$HAPPY_AGENT_SYSTEMD_UNIT_DIR"
  install -m 0644 "$SCRIPT_DIR/../systemd/happy-agent-cert-renew.service" "$HAPPY_AGENT_SYSTEMD_UNIT_DIR/happy-agent-cert-renew.service"
  install -m 0644 "$SCRIPT_DIR/../systemd/happy-agent-cert-renew.timer" "$HAPPY_AGENT_SYSTEMD_UNIT_DIR/happy-agent-cert-renew.timer"
  systemctl daemon-reload
  if [ -f "$(certificate_dir)/fullchain.pem" ] && [ -f "$(certificate_dir)/chain.pem" ]; then systemctl enable --now happy-agent-cert-renew.timer; fi
  log "bootstrap Docker/Compose ready; swap=$HAPPY_AGENT_SWAPFILE; root=$HAPPY_AGENT_ROOT"
}
preflight
with_lock bootstrap
