#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${script_dir}/../docker-compose.yml"
output_dir="${1:-/opt/happy-agent/backups}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
output_file="${output_dir}/happy_agent-${timestamp}.dump"

mkdir -p "${output_dir}"
docker compose -f "${compose_file}" exec -T postgres \
  pg_dump --username="${POSTGRES_USER:-postgres}" --format=custom --no-owner --no-privileges \
  --dbname="${POSTGRES_DB:-happy_agent}" > "${output_file}"

printf 'Exported %s\n' "${output_file}"
