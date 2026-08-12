#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
backup() {
  local timestamp pending complete release release_id manifest_hash
  timestamp=${HAPPY_AGENT_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}
  [[ "$timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || die "unsafe backup timestamp"
  install -d -m 0750 "$HAPPY_AGENT_ROOT/backups"
  pending=$(validate_descendant "$HAPPY_AGENT_ROOT/backups/.pending-$timestamp")
  complete=$(validate_descendant "$HAPPY_AGENT_ROOT/backups/$timestamp")
  [ ! -e "$pending" ] && [ ! -e "$complete" ] || die "backup timestamp already exists"
  install -d -m 0700 "$pending"
  release=$(current_release)
  verify_manifest "$release" .env compose.yml nginx.conf
  release_id=${release##*/}
  manifest_hash=$(sha256sum "$release/SHA256SUMS" | awk '{print $1}')
  compose_release "$release" exec -T postgres pg_dump -Fc -U postgres happy_agent >"$pending/database.dump"
  tar -C "$HAPPY_AGENT_ROOT/data/media" -cf "$pending/media.tar" .
  [ ! -f "$HAPPY_AGENT_ROOT/secrets/agent-master-key" ] || cp -- "$HAPPY_AGENT_ROOT/secrets/agent-master-key" "$pending/agent-master-key"
  printf 'release_id=%s\nmanifest_sha256=%s\n' "$release_id" "$manifest_hash" >"$pending/release-metadata"
  cp -- "$release/SHA256SUMS" "$pending/release-SHA256SUMS"
  chmod 0600 "$pending"/*
  (cd "$pending" && sha256sum * >SHA256SUMS)
  chmod 0600 "$pending"/SHA256SUMS
  mv -Tf -- "$pending" "$complete"
  log "backup created at $complete"
}
with_lock backup
