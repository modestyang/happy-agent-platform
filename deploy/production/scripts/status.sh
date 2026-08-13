#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"

validate_root
printf 'root: %s\n' "$HAPPY_AGENT_ROOT"
if [ -L "$HAPPY_AGENT_ROOT/current" ]; then
  release=$(current_release)
  printf 'current-release: %s\n' "${release##*/}"
  compose_release "$release" ps
else
  printf 'current-release: none\n'
fi
if [ -L "$HAPPY_AGENT_ROOT/state/current" ]; then
  generation=$(current_generation)
  printf 'current-state-generation: %s\n' "${generation##*/}"
else
  printf 'current-state-generation: none\n'
fi

if [ -f "$(certificate_dir)/cert.pem" ]; then
  openssl x509 -in "$(certificate_dir)/cert.pem" -noout -ext subjectAltName -enddate
else
  printf 'certificate: absent\n'
fi
df -h "$HAPPY_AGENT_ROOT"
free -h
swapon --show

latest=''
if [ -d "$HAPPY_AGENT_ROOT/backups" ]; then
  while IFS= read -r candidate; do
    case "${candidate##*/}" in
      [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z) ;;
      *) continue;;
    esac
    if (verify_manifest "$candidate" database.dump media.tar release-metadata state-metadata \
        release-SHA256SUMS) >/dev/null 2>&1; then
      latest=$candidate
    fi
  done < <(find "$HAPPY_AGENT_ROOT/backups" -mindepth 1 -maxdepth 1 -type d \
    ! -name '.pending-*' -print | LC_ALL=C sort)
fi
if [ -n "$latest" ]; then
  printf 'latest-backup: %s\n' "${latest##*/}"
  printf 'backup-bytes: %s\n' "$(find "$latest" -type f -exec wc -c {} + | awk 'END {print $1}')"
  printf 'backup-files: %s\n' "$(find "$latest" -type f | wc -l | tr -d ' ')"
  sha256sum "$latest/SHA256SUMS"
else
  printf 'latest-backup: none\n'
fi
