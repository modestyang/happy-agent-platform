#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TMP_PARENT=${TMPDIR:-/tmp}
TMP=$(mktemp -d "${TMP_PARENT%/}/happy-agent-web-runtime.XXXXXX")
IMAGE="happy-agent-web-runtime-$(basename "$TMP" | tr -cd 'a-z0-9')"

cleanup() {
  local status=$?
  docker image rm "$IMAGE" >/dev/null 2>&1 || true
  case "$TMP" in "${TMP_PARENT%/}"/happy-agent-web-runtime.*) rm -rf -- "$TMP";; esac
  return "$status"
}
trap cleanup EXIT

mkdir -p "$TMP/frontend/dist"
printf '<!doctype html><title>runtime probe</title>\n' >"$TMP/frontend/dist/index.html"
chmod 0600 "$TMP/frontend/dist/index.html"

docker build -q -f "$ROOT_DIR/web.Dockerfile" -t "$IMAGE" "$TMP" >/dev/null
docker run --rm --user nginx "$IMAGE" sh -ec \
  'test -r /usr/share/nginx/html/index.html && grep -Fq "runtime probe" /usr/share/nginx/html/index.html' \
  || { echo 'FAIL: Nginx runtime cannot read restrictive frontend artifacts' >&2; exit 1; }

echo 'PASS: production Web image serves restrictive build artifacts'
