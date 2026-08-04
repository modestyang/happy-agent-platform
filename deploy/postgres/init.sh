#!/usr/bin/env sh
set -eu

read_secret() {
  secret_file="$1"
  if [ ! -r "${secret_file}" ]; then
    echo "Secret file is not readable: ${secret_file}" >&2
    exit 1
  fi
  tr -d '\r\n' < "${secret_file}"
}

fitness_password="$(read_secret "${FITNESS_DB_PASSWORD_FILE:?FITNESS_DB_PASSWORD_FILE is required}")"
agent_password="$(read_secret "${AGENT_DB_PASSWORD_FILE:?AGENT_DB_PASSWORD_FILE is required}")"

psql --username="${POSTGRES_USER}" --dbname="${POSTGRES_DB}" --set=ON_ERROR_STOP=1 \
  --set="fitness_password=${fitness_password}" \
  --set="agent_password=${agent_password}" \
  --file=/docker-entrypoint-initdb.d/01-init.sql
