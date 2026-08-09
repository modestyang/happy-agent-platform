#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script_path="${script_dir}/local-run.sh"
project_dir="$(cd "${script_dir}/.." && pwd)"
fake_bin="$(mktemp -d)"
trap 'rm -rf "${fake_bin}"' EXIT
function_file="${fake_bin}/port-functions.sh"
sed -n '/^port_owned_by_project()/,/^}/p' "${script_path}" >"${function_file}"

cat >"${fake_bin}/lsof" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *"-iTCP:"* ]]; then printf '%s\n' "${FAKE_PID}"; else printf 'n%s\n' "${FAKE_CWD}"; fi
EOF
cat >"${fake_bin}/ps" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "${FAKE_COMMAND}"
EOF
chmod +x "${fake_bin}/lsof" "${fake_bin}/ps"

assert_status() {
  local name="$1" expected="$2" cwd="$3" command="$4" result
  result="$(PATH="${fake_bin}:$PATH" FAKE_PID=4242 FAKE_CWD="${cwd}" FAKE_COMMAND="${command}" PROJECT_DIR="${project_dir}" FUNCTION_FILE="${function_file}" bash -c '
    project_dir="$PROJECT_DIR"
    source "$FUNCTION_FILE"
    set +e
    port_owned_by_project 5173 >/dev/null 2>&1
    echo $?
  ')"
  [[ "${result}" == "${expected}" ]] || { echo "${name}: expected ${expected}, got ${result}" >&2; exit 1; }
}

assert_status "frontend vite cwd" 0 "${project_dir}/frontend" "node ${project_dir}/frontend/node_modules/.bin/vite"
assert_status "absolute backend jar" 0 "/tmp" "java -jar ${project_dir}/starter/target/starter-0.0.1-SNAPSHOT-exec.jar"
assert_status "external similarly named project" 1 "${project_dir}-other/frontend" "node ${project_dir}-other/frontend/node_modules/.bin/vite"

echo "local-run port ownership tests passed"
