#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <custom-format-dump>" >&2
  exit 2
fi

archive="$1"
if [ ! -f "${archive}" ]; then
  echo "Dump archive does not exist: ${archive}" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${script_dir}/../docker-compose.yml"

docker compose -f "${compose_file}" exec -T postgres \
  sh /docker-entrypoint-initdb.d/00-init.sh
docker compose -f "${compose_file}" exec -T postgres \
  pg_restore --username=postgres --dbname=happy_agent --clean --if-exists --exit-on-error < "${archive}"

printf 'Restored %s with schema ownership and ACLs.\n' "${archive}"
