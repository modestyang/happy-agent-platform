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
exec java -jar "${project_dir}/starter/target/starter-0.0.1-SNAPSHOT-exec.jar" \
  --spring.profiles.active=local
