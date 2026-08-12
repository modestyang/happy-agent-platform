#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
validate_root
printf 'root: %s\n' "$HAPPY_AGENT_ROOT"
if [ -L "$HAPPY_AGENT_ROOT/current" ]; then release=$(current_release); printf 'current: %s\n' "${release##*/}"; compose_release "$release" ps; else printf 'current: none\n'; fi
if [ -f "$(certificate_dir)/fullchain.pem" ]; then openssl x509 -in "$(certificate_dir)/fullchain.pem" -noout -ext subjectAltName -enddate; else printf 'certificate: absent\n'; fi
df -h "$HAPPY_AGENT_ROOT"; free -h; swapon --show
latest=$(find "$HAPPY_AGENT_ROOT/backups" -mindepth 1 -maxdepth 1 -type d ! -name '.pending-*' -printf '%f\n' 2>/dev/null | sort | tail -n1 || true)
[ -z "$latest" ] || printf 'latest-backup: %s\n' "$latest"
