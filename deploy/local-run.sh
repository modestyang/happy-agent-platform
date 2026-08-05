#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "${deploy_dir}/.." && pwd)"

"${deploy_dir}/local-up.sh"
set -a
# shellcheck disable=SC1091
source "${deploy_dir}/.local/app.env"
set +a

"${project_dir}/mvnw" -f "${project_dir}/pom.xml" -pl starter -am -DskipTests package

if [[ ! -d "${project_dir}/frontend/node_modules" ]]; then
  npm --prefix "${project_dir}/frontend" ci
fi

java -jar "${project_dir}/starter/target/starter-0.0.1-SNAPSHOT-exec.jar" \
  --spring.profiles.active=local > "${deploy_dir}/.local/backend.log" 2>&1 &
backend_pid=$!

cleanup() {
  kill "${backend_pid}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

for _ in {1..60}; do
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    http://127.0.0.1:8080/api/app/bootstrap || true)
  if [[ "${status}" == "401" || "${status}" == "200" ]]; then
    break
  fi
  sleep 1
done

echo "Happy Agent Platform 已启动：http://127.0.0.1:5173"
npm --prefix "${project_dir}/frontend" run dev -- --host 0.0.0.0
