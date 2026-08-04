#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${script_dir}/../docker-compose.yml"
compose=(docker compose -f "${compose_file}")
test_secret_dir="$(mktemp -d)"
trap 'rm -rf "${test_secret_dir}"' EXIT
printf 'postgres-test-password' > "${test_secret_dir}/postgres_password"
printf 'fitness-test-password' > "${test_secret_dir}/fitness_db_password"
printf 'agent-test-password' > "${test_secret_dir}/agent_db_password"
export POSTGRES_PASSWORD_FILE="${POSTGRES_PASSWORD_FILE:-${test_secret_dir}/postgres_password}"
export FITNESS_DB_PASSWORD_FILE="${FITNESS_DB_PASSWORD_FILE:-${test_secret_dir}/fitness_db_password}"
export AGENT_DB_PASSWORD_FILE="${AGENT_DB_PASSWORD_FILE:-${test_secret_dir}/agent_db_password}"
psql=("${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d happy_agent)

"${compose[@]}" up -d --wait postgres
"${psql[@]}" -c "INSERT INTO fitness.users (user_id, external_subject, status) VALUES ('00000000-0000-0000-0000-000000000031', 'persistence-probe', 'ACTIVE') ON CONFLICT (user_id) DO NOTHING;"
"${psql[@]}" -c "INSERT INTO agent.agent_versions (agent_version_id, agent_key, version, status, configuration) VALUES ('00000000-0000-0000-0000-000000000032', 'persistence-probe', 1, 'PUBLISHED', '{}') ON CONFLICT (agent_version_id) DO NOTHING;"
"${compose[@]}" restart postgres
"${compose[@]}" up -d --wait postgres
fitness_after_restart="$("${psql[@]}" -Atc "SELECT count(*) FROM fitness.users WHERE user_id = '00000000-0000-0000-0000-000000000031';")"
agent_after_restart="$("${psql[@]}" -Atc "SELECT count(*) FROM agent.agent_versions WHERE agent_version_id = '00000000-0000-0000-0000-000000000032';")"
test "${fitness_after_restart}" = "1"
test "${agent_after_restart}" = "1"
"${compose[@]}" up -d --wait --force-recreate postgres

fitness_count="$("${psql[@]}" -Atc "SELECT count(*) FROM fitness.users WHERE user_id = '00000000-0000-0000-0000-000000000031';")"
agent_count="$("${psql[@]}" -Atc "SELECT count(*) FROM agent.agent_versions WHERE agent_version_id = '00000000-0000-0000-0000-000000000032';")"

test "${fitness_count}" = "1"
test "${agent_count}" = "1"
printf 'Persistence verified for fitness and agent schemas.\n'
