#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"

backup_core() (
  set -euo pipefail
  local timestamp pending complete release release_id release_manifest_hash generation generation_id media_member
  local -a media_members=()
  timestamp=${HAPPY_AGENT_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}
  [[ "$timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || die 'unsafe backup timestamp'
  install -d -m 0750 "$HAPPY_AGENT_ROOT/backups"
  pending=$(validate_descendant "$HAPPY_AGENT_ROOT/backups/.pending-$timestamp")
  complete=$(validate_descendant "$HAPPY_AGENT_ROOT/backups/$timestamp")
  [ ! -e "$pending" ] && [ ! -e "$complete" ] || die 'backup timestamp already exists'
  install -d -m 0700 "$pending"
  trap 'if [ -n "${pending:-}" ] && [ -d "$pending" ]; then rm -rf -- "$pending"; fi' EXIT

  release=$(current_release)
  verify_manifest "$release" .env compose.yml nginx.conf
  generation=$(current_generation)
  [ -z "$(find "$generation/media" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
    || die 'media tree contains a special member'
  postgres_identity "$release" >/dev/null || die 'PostgreSQL is not healthy'

  release_id=${release##*/}
  generation_id=${generation##*/}
  release_manifest_hash=$(sha256sum "$release/SHA256SUMS" | awk '{print $1}')
  compose_release "$release" exec -T postgres pg_dump -Fc -U postgres happy_agent \
    >"$pending/database.dump"
  [ -s "$pending/database.dump" ] || die 'database backup is empty'
  while IFS= read -r -d '' media_member; do
    media_member=${media_member#./}
    case "$media_member" in ''|*'\\'*|*'|'*|*$'\n'*|*$'\r'*) die 'media path cannot be represented safely in an archive';; esac
    media_members+=("$media_member")
  done < <(cd "$generation/media" && find . -type f -print0)
  if [ "${#media_members[@]}" -gt 0 ]; then
    tar -C "$generation/media" -cf "$pending/media.tar" -- "${media_members[@]}"
  else
    tar -C "$generation/media" -cf "$pending/media.tar" -T /dev/null
  fi
  if [ -f "$generation/agent-master-key" ] && [ ! -L "$generation/agent-master-key" ]; then
    [ -s "$generation/agent-master-key" ] || die 'Agent master key is empty'
    cp -- "$generation/agent-master-key" "$pending/agent-master-key"
    cmp -- "$generation/agent-master-key" "$pending/agent-master-key" >/dev/null \
      || die 'Agent master key backup differs from source bytes'
  elif [ -e "$generation/agent-master-key" ]; then
    die 'Agent master key is not a regular file'
  fi
  printf 'release_id=%s\nmanifest_sha256=%s\n' "$release_id" "$release_manifest_hash" \
    >"$pending/release-metadata"
  printf 'generation_id=%s\n' "$generation_id" >"$pending/state-metadata"
  cp -- "$release/SHA256SUMS" "$pending/release-SHA256SUMS"
  find "$pending" -type f -exec chmod 0600 {} +
  (
    cd "$pending"
    find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | sed 's#^./##' \
      | while IFS= read -r file; do sha256sum "$file"; done >SHA256SUMS
  )
  chmod 0600 "$pending/SHA256SUMS"
  verify_manifest "$pending" database.dump media.tar release-metadata state-metadata release-SHA256SUMS
  mv -Tf -- "$pending" "$complete"
  pending=''
  log "backup created at $complete"
)

if [ "${BASH_SOURCE[0]}" = "$0" ]; then with_lock backup_core; fi
