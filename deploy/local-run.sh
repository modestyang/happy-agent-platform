#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "${deploy_dir}/.." && pwd)"

is_project_path() {
  local path="$1"
  [[ "${path}" == "${project_dir}" || "${path}" == "${project_dir}/"* ]]
}

port_owned_by_project() {
  local port="$1"
  local pid cwd command
  while IFS= read -r pid; do
    [[ -n "${pid}" ]] || continue
    cwd="$(lsof -a -p "${pid}" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)"
    command="$(ps -p "${pid}" -o command=)"
    if is_project_path "${cwd}" || [[ "${command}" == *"${project_dir}/"* ]]; then
      return 0
    fi
    echo "错误：端口 ${port} 已被其他进程占用（PID ${pid}）：${command}" >&2
    return 1
  done < <(lsof -nP -t -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)
  return 2
}

backend_reused=false
frontend_reused=false
for port in 8080 5173; do
  if port_owned_by_project "${port}"; then
    if [[ "${port}" == "8080" ]]; then backend_reused=true; else frontend_reused=true; fi
    echo "复用本项目已监听的端口 ${port}。"
  else
    status=$?
    if [[ "${status}" -eq 1 ]]; then exit 1; fi
  fi
done

cd "${project_dir}"

"${deploy_dir}/local-up.sh"
set -a
# shellcheck disable=SC1091
source "${deploy_dir}/.local/app.env"
set +a

"${project_dir}/mvnw" -f "${project_dir}/pom.xml" -pl starter -am -DskipTests package

if [[ ! -d "${project_dir}/frontend/node_modules" ]]; then
  npm --prefix "${project_dir}/frontend" ci
fi

backend_pid=""
if [[ "${backend_reused}" == false ]]; then
  java -jar "${project_dir}/starter/target/starter-0.0.1-SNAPSHOT-exec.jar" \
    --spring.profiles.active=local > "${deploy_dir}/.local/backend.log" 2>&1 &
  backend_pid=$!
fi

cleanup() {
  [[ -z "${backend_pid}" ]] || kill "${backend_pid}" 2>/dev/null || true
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
if [[ "${frontend_reused}" == true ]]; then
  if [[ -n "${backend_pid}" ]]; then wait "${backend_pid}"; fi
  exit 0
fi
npm --prefix "${project_dir}/frontend" run dev -- --host 0.0.0.0
