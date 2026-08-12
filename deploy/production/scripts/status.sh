#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
validate_root
printf 'root: %s\n' "$HAPPY_AGENT_ROOT"
if [ -L "$HAPPY_AGENT_ROOT/current" ]; then release=$(current_release); printf 'current: %s\n' "${release##*/}"; compose_release "$release" ps; else printf 'current: none\n'; fi
if [ -f "$(certificate_dir)/fullchain.pem" ]; then openssl x509 -in "$(certificate_dir)/fullchain.pem" -noout -ext subjectAltName -enddate; else printf 'certificate: absent\n'; fi
df -h "$HAPPY_AGENT_ROOT"; free -h; swapon --show
latest=''
while IFS= read -r candidate; do
  [ -f "$candidate/SHA256SUMS" ] || continue
  [ -f "$candidate/database.dump" ] && [ -f "$candidate/media.tar" ] && [ -f "$candidate/release-metadata" ] || continue
  (cd "$candidate" && sha256sum --check --strict SHA256SUMS >/dev/null) || continue
  latest=$candidate
done < <(find "$HAPPY_AGENT_ROOT/backups" -mindepth 1 -maxdepth 1 -type d ! -name '.pending-*' -print | sort)
if [ -n "$latest" ]; then
  printf 'latest-backup: %s\n' "${latest##*/}"
  printf 'backup-bytes: %s\n' "$(find "$latest" -type f -exec wc -c {} + | awk 'END {print $1}')"
  printf 'backup-files: %s\n' "$(find "$latest" -type f | wc -l)"
  sha256sum "$latest/SHA256SUMS"
fi
