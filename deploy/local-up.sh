#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
local_dir="${deploy_dir}/.local"
secret_dir="${local_dir}/secrets"
data_dir="${local_dir}/data/postgres"
compose_env="${local_dir}/compose.env"
app_env="${local_dir}/app.env"

mkdir -p "${secret_dir}" "${data_dir}"
chmod 700 "${local_dir}" "${secret_dir}"

generate_secret() {
  secret_file="$1"
  if [[ ! -s "${secret_file}" ]]; then
    umask 077
    if command -v openssl >/dev/null 2>&1; then
      openssl rand -hex 24 >"${secret_file}"
    else
      LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c 48 >"${secret_file}"
      printf '\n' >>"${secret_file}"
    fi
  fi
  chmod 600 "${secret_file}"
}

postgres_secret="${secret_dir}/postgres_password"
fitness_secret="${secret_dir}/fitness_db_password"
agent_secret="${secret_dir}/agent_db_password"
generate_secret "${postgres_secret}"
generate_secret "${fitness_secret}"
generate_secret "${agent_secret}"

cat >"${compose_env}" <<EOF
POSTGRES_DATA_DIR=${data_dir}
POSTGRES_PORT=5432
POSTGRES_PASSWORD_FILE=${postgres_secret}
FITNESS_DB_PASSWORD_FILE=${fitness_secret}
AGENT_DB_PASSWORD_FILE=${agent_secret}
EOF
chmod 600 "${compose_env}"

cat >"${app_env}" <<EOF
HAPPY_DB_URL=jdbc:postgresql://localhost:5432/happy_agent
FITNESS_DB_PASSWORD=$(tr -d '\r\n' <"${fitness_secret}")
AGENT_DB_PASSWORD=$(tr -d '\r\n' <"${agent_secret}")
EOF
chmod 600 "${app_env}"

docker compose --env-file "${compose_env}" -f "${deploy_dir}/docker-compose.yml" up -d --wait --wait-timeout 60 postgres
docker compose --env-file "${compose_env}" -f "${deploy_dir}/docker-compose.yml" ps postgres

echo "PostgreSQL is running with project-local persistent data."
echo "Application environment: ${app_env}"
