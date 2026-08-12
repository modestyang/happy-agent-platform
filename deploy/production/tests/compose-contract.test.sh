#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
compose_file="${project_root}/deploy/production/compose.yml"

if grep -q '/release/current\|}/media\|}/acme-webroot' "${compose_file}"; then
  echo "Compose must use canonical current and data layout" >&2
  exit 1
fi
temp_parent="${TMPDIR:-/tmp}"
temp_root="$(mktemp -d "${temp_parent%/}/happy-agent-compose.XXXXXX")"

case "${temp_root}" in
  "${temp_parent%/}"/*) ;;
  *)
    echo "mktemp returned a path outside TMPDIR" >&2
    exit 1
    ;;
esac

cleanup() {
  if [ -n "${temp_root:-}" ] && [ -d "${temp_root}" ]; then
    rm -rf -- "${temp_root}"
  fi
}
trap cleanup EXIT

mkdir -p \
  "${temp_root}/data/postgres" \
  "${temp_root}/data/media" \
  "${temp_root}/data/acme-webroot" \
  "${temp_root}/certificates" \
  "${temp_root}/current/postgres" \
  "${temp_root}/secrets"

printf 'fake-postgres-password\n' > "${temp_root}/secrets/postgres-password"
printf 'fake-fitness-password\n' > "${temp_root}/secrets/fitness-db-password"
printf 'fake-agent-password\n' > "${temp_root}/secrets/agent-db-password"
printf 'fake-master-key\n' > "${temp_root}/secrets/agent-master-key"
printf 'events {}\n' > "${temp_root}/current/nginx.conf"
printf '#!/bin/sh\n' > "${temp_root}/current/postgres/init-roles.sh"
printf 'SELECT 1;\n' > "${temp_root}/current/postgres/init-roles.sql"
printf 'SELECT 1;\n' > "${temp_root}/current/postgres/enforce-isolation.sql"

export HAPPY_AGENT_ROOT="${temp_root}"
export POSTGRES_PASSWORD_FILE="${temp_root}/secrets/postgres-password"
export FITNESS_DB_PASSWORD_FILE="${temp_root}/secrets/fitness-db-password"
export AGENT_DB_PASSWORD_FILE="${temp_root}/secrets/agent-db-password"

cd "${project_root}"
docker compose \
  --env-file deploy/production/.env.example \
  -f deploy/production/compose.yml config --quiet

config_json_path="${temp_root}/compose.json"
docker compose \
  --env-file deploy/production/.env.example \
  -f deploy/production/compose.yml config --format json > "${config_json_path}"

node - "${config_json_path}" "${HAPPY_AGENT_ROOT}" <<'NODE'
const fs = require('fs');
const [configPath, happyAgentRoot] = process.argv.slice(2);
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const services = config.services;

function assert(condition, message) {
  if (!condition) {
    throw new Error(`Compose contract assertion failed: ${message}`);
  }
}

assert(config.name === 'happy-agent', 'project name');
assert(JSON.stringify(Object.keys(services).sort()) === JSON.stringify(['app', 'nginx', 'postgres']), 'service names');
assert((services.postgres.ports ?? []).length === 0 && (services.app.ports ?? []).length === 0, 'only nginx publishes ports');
assert(JSON.stringify(services.nginx.ports.map(({ published, target }) => `${published}:${target}`).sort()) === JSON.stringify(['443:443', '80:80']), 'nginx published ports');

for (const [name, service] of Object.entries(services)) {
  assert(service.restart === 'unless-stopped', `${name} restart policy`);
  assert(service.healthcheck != null, `${name} healthcheck`);
  assert(service.logging?.driver === 'json-file', `${name} JSON logging driver`);
  assert(service.logging?.options?.['max-size'] != null && service.logging?.options?.['max-file'] != null, `${name} log rotation`);
}

assert(['1800m', '1887436800'].includes(String(services.app.deploy.resources.limits.memory)), 'app memory limit');
assert(['768m', '805306368'].includes(String(services.postgres.deploy.resources.limits.memory)), 'postgres memory limit');
assert(['128m', '134217728'].includes(String(services.nginx.deploy.resources.limits.memory)), 'nginx memory limit');
const postgresCommand = services.postgres.command.join(' ');
assert(postgresCommand.includes('shared_buffers=128MB') && postgresCommand.includes('work_mem=4MB'), 'postgres tuning');

for (const service of Object.values(services)) {
  for (const volume of service.volumes ?? []) {
    if (volume.type === 'bind') {
      assert(volume.source.startsWith(happyAgentRoot), `bind source beneath temp root: ${volume.source}`);
    }
  }
}

assert(services.app.volumes.some((volume) => volume.target === '/app/deploy/.local/media'), 'app media mount');
assert(services.app.volumes.some((volume) => volume.target === '/run/secrets/agent-master-key' && volume.read_only), 'app read-only master key');
assert(JSON.stringify(services.app.secrets.map(({ source }) => source).sort()) === JSON.stringify(['agent_db_password', 'fitness_db_password']), 'app database secrets');
assert(JSON.stringify(services.postgres.secrets.map(({ source }) => source).sort()) === JSON.stringify(['agent_db_password', 'fitness_db_password', 'postgres_password']), 'postgres database secrets');
assert(services.app.environment.FITNESS_DB_PASSWORD_FILE === '/run/secrets/fitness_db_password', 'app fitness password file');
assert(services.app.environment.AGENT_DB_PASSWORD_FILE === '/run/secrets/agent_db_password', 'app agent password file');
for (const secret of Object.values(config.secrets)) {
  assert(secret.file.startsWith(happyAgentRoot), `secret source beneath temp root: ${secret.file}`);
}
assert(services.nginx.volumes.some((volume) => volume.target === '/etc/nginx/conf.d/default.conf' && volume.read_only), 'nginx active config mount');
function bindSource(service, target) {
  const volume = service.volumes.find((item) => item.type === 'bind' && item.target === target);
  assert(volume != null, `missing bind mount for ${target}`);
  return volume.source;
}
assert(bindSource(services.postgres, '/docker-entrypoint-initdb.d/00-init-roles.sh') === `${happyAgentRoot}/current/postgres/init-roles.sh`, 'postgres init role script canonical source');
assert(bindSource(services.postgres, '/usr/local/share/happy-agent-init-roles.sql') === `${happyAgentRoot}/current/postgres/init-roles.sql`, 'postgres init role SQL canonical source');
assert(bindSource(services.postgres, '/usr/local/share/happy-agent-enforce-isolation.sql') === `${happyAgentRoot}/current/postgres/enforce-isolation.sql`, 'postgres isolation SQL canonical source');
assert(bindSource(services.app, '/app/deploy/.local/media') === `${happyAgentRoot}/data/media`, 'app media canonical source');
assert(bindSource(services.nginx, '/etc/nginx/conf.d/default.conf') === `${happyAgentRoot}/current/nginx.conf`, 'nginx config canonical source');
assert(bindSource(services.nginx, '/var/www/acme') === `${happyAgentRoot}/data/acme-webroot`, 'ACME webroot canonical source');
assert(services.nginx.depends_on.app.condition === 'service_healthy', 'nginx app dependency');
assert(services.app.depends_on.postgres.condition === 'service_healthy', 'app postgres dependency');
const appHealthcheck = services.app.healthcheck.test.join(' ');
assert(
  appHealthcheck.includes('FITNESS_SESSION=invalid-healthcheck-session')
    && appHealthcheck.includes(' 401 ')
    && !appHealthcheck.includes(' 200 '),
  'app invalid-session healthcheck accepts only 401',
);
NODE

if grep -nF 'certbot/certbot:' deploy/production/compose.yml; then
  echo "Certbot must not be a permanent Compose service" >&2
  exit 1
fi

proxy_pass_lines="$(
  {
    grep '^[[:space:]]*proxy_pass ' deploy/production/nginx/ip-https.conf.template || true
    grep '^[[:space:]]*proxy_pass ' deploy/production/nginx/ip-http.conf.template || true
  }
)"
if [ "${proxy_pass_lines}" != "        proxy_pass http://app:8080;" ]; then
  echo "Nginx must proxy only to app:8080" >&2
  exit 1
fi

if grep -n 'proxy_pass' deploy/production/nginx/ip-http.conf.template; then
  echo "HTTP-stage Nginx config must not define an upstream" >&2
  exit 1
fi

grep -qF 'proxy_buffering off;' deploy/production/nginx/ip-https.conf.template
grep -qF 'proxy_set_header Host $host;' deploy/production/nginx/ip-https.conf.template
grep -qF 'proxy_set_header X-Real-IP $remote_addr;' deploy/production/nginx/ip-https.conf.template
grep -qF 'proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;' deploy/production/nginx/ip-https.conf.template
grep -qF 'proxy_set_header X-Forwarded-Proto $scheme;' deploy/production/nginx/ip-https.conf.template
grep -qF 'proxy_set_header X-Forwarded-Host $host;' deploy/production/nginx/ip-https.conf.template
grep -qF 'proxy_connect_timeout 5s;' deploy/production/nginx/ip-https.conf.template
grep -qF 'proxy_send_timeout 3600s;' deploy/production/nginx/ip-https.conf.template
grep -qF 'proxy_read_timeout 3600s;' deploy/production/nginx/ip-https.conf.template
grep -qF 'client_max_body_size 20m;' deploy/production/nginx/ip-https.conf.template
grep -qF 'try_files $uri $uri/ /index.html;' deploy/production/nginx/ip-https.conf.template
grep -qF 'try_files $uri =404;' deploy/production/nginx/ip-http.conf.template
if grep -ni 'strict-transport-security' deploy/production/nginx/ip-*.conf.template; then
  echo "HSTS must remain disabled for the IP-stage configuration" >&2
  exit 1
fi

echo "Compose production contract passed"
