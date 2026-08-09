#!/usr/bin/env bash
# ============================================================================
# Alibaba Cloud (Anolis OS / CentOS / Ubuntu) one-shot deployer.
# ----------------------------------------------------------------------------
# Installs PostgreSQL 16 with pgvector, builds the starter jar, builds the
# frontend, sets up systemd service for the backend, and configures nginx
# to serve the static frontend and reverse-proxy /api to the Spring Boot app.
#
# Run as `root` once. The script is idempotent — re-running it re-applies the
# configuration without losing data.
# ============================================================================

set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/opt/happy-agent}"
APP_USER="${APP_USER:-happy}"
APP_PORT="${APP_PORT:-8080}"
PUBLIC_HOST="${PUBLIC_HOST:-0.0.0.0}"
JAVA_VERSION="${JAVA_VERSION:-17}"
POSTGRES_VERSION="${POSTGRES_VERSION:-16}"
NGINX_PORT="${NGINX_PORT:-80}"
LOG_FILE="${LOG_FILE:-/var/log/happy-agent-deploy.log}"

PGVECTORS_VERSION="${PGVECTORS_VERSION:-0.7.4}"
PG_SHARED_BUFFERS="${PG_SHARED_BUFFERS:-256MB}"
PG_WORK_MEM="${PG_WORK_MEM:-16MB}"

exec > >(tee -a "${LOG_FILE}") 2>&1
echo "==== happy-agent Aliyun deployer started at $(date -Iseconds) ===="

log() { printf "\033[1;36m[deploy]\033[0m %s\n" "$*"; }
die() { printf "\033[1;31m[deploy:FATAL]\033[0m %s\n" "$*" >&2; exit 1; }

[ "$(id -un)" = "root" ] || die "must run as root"
[ -d "${PROJECT_DIR}" ] || die "project not found at ${PROJECT_DIR}; clone the repo first"

# ----------------------------------------------------------------------------
# 1. System packages
# ----------------------------------------------------------------------------
log "installing system packages"
if command -v dnf >/dev/null 2>&1; then
  PKG_MGR=dnf
  $PKG_MGR install -y epel-release yum-utils
  $PKG_MGR config-manager --set-enabled crb || true
  $PKG_MGR install -y bash curl wget tar gzip openssl nginx java-${JAVA_VERSION}-openjdk-headless postgresql${POSTGRES_VERSION}-server postgresql${POSTGRES_VERSION}-contrib gcc make readline-devel zlib-devel libpq-devel
elif command -v apt-get >/dev/null 2>&1; then
  PKG_MGR=apt
  apt-get update -y
  apt-get install -y bash curl wget tar gzip openssl nginx openjdk-${JAVA_VERSION}-jdk-headless postgresql-client build-essential libpq-dev
  # PostgreSQL 16 on Ubuntu from PGDG
  if ! command -v psql >/dev/null 2>&1; then
    apt-get install -y wget ca-certificates gnupg lsb-release
    echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list
    wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -
    apt-get update -y
    apt-get install -y postgresql-${POSTGRES_VERSION} postgresql-${POSTGRES_VERSION}-pgdg-pgvector
  fi
else
  die "neither dnf nor apt-get found"
fi

# ----------------------------------------------------------------------------
# 2. App user
# ----------------------------------------------------------------------------
log "creating app user ${APP_USER}"
if ! id -u "${APP_USER}" >/dev/null 2>&1; then
  useradd --system --home "${PROJECT_DIR}" --shell /bin/bash "${APP_USER}"
fi

# ----------------------------------------------------------------------------
# 3. PostgreSQL bootstrap (initdb, start, enable pgvector)
# ----------------------------------------------------------------------------
PG_DATA_DIR="/var/lib/pgsql/${POSTGRES_VERSION}/data"
PG_CONF_DIR="/var/lib/pgsql/${POSTGRES_VERSION}"
if command -v pg_isready >/dev/null 2>&1; then
  log "ensuring PostgreSQL data dir"
  if [ ! -s "${PG_DATA_DIR}/PG_VERSION" ]; then
    if command -v postgresql-${POSTGRES_VERSION}-setup >/dev/null 2>&1; then
      postgresql-${POSTGRES_VERSION}-setup initdb
    elif command -v pg_createcluster >/dev/null 2>&1; then
      pg_createcluster "${POSTGRES_VERSION}" main
    fi
  fi
  log "starting PostgreSQL"
  systemctl enable --now postgresql || true
  for _ in {1..20}; do
    if pg_isready -q; then break; fi
    sleep 1
  done
fi

# Generate the agent schema and roles the first time only.
log "initialising database role and schemas"
SECRETS_DIR="${PROJECT_DIR}/deploy/secrets"
mkdir -p "${SECRETS_DIR}"
chmod 700 "${SECRETS_DIR}"
chown "${APP_USER}:${APP_USER}" "${SECRETS_DIR}" 2>/dev/null || true

for name in postgres fitness agent; do
  file="${SECRETS_DIR}/${name}_db_password"
  if [ ! -s "${file}" ]; then
    openssl rand -hex 24 > "${file}"
    chmod 600 "${file}"
  fi
done

POSTGRES_PASSWORD="$(cat "${SECRETS_DIR}/postgres_db_password")"
FITNESS_PASSWORD="$(cat "${SECRETS_DIR}/fitness_db_password")"
AGENT_PASSWORD="$(cat "${SECRETS_DIR}/agent_db_password")"

sudo -u postgres psql -v ON_ERROR_STOP=1 -tAc "SELECT 1 FROM pg_roles WHERE rolname='postgres'" | grep -q 1 || \
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "ALTER ROLE postgres WITH PASSWORD '${POSTGRES_PASSWORD}'"
sudo -u postgres psql -v ON_ERROR_STOP=1 -tAc "SELECT 1 FROM pg_roles WHERE rolname='fitness_app'" | grep -q 1 || \
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE ROLE fitness_app LOGIN PASSWORD '${FITNESS_PASSWORD}' NOINHERIT"
sudo -u postgres psql -v ON_ERROR_STOP=1 -tAc "SELECT 1 FROM pg_roles WHERE rolname='agent_app'" | grep -q 1 || \
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE ROLE agent_app LOGIN PASSWORD '${AGENT_PASSWORD}' NOINHERIT"
sudo -u postgres psql -v ON_ERROR_STOP=1 -c "ALTER ROLE fitness_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT" >/dev/null
sudo -u postgres psql -v ON_ERROR_STOP=1 -c "ALTER ROLE agent_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT" >/dev/null
sudo -u postgres psql -v ON_ERROR_STOP=1 -tAc "SELECT 1 FROM pg_database WHERE datname='happy_agent'" | grep -q 1 || \
  sudo -u postgres createdb happy_agent

sudo -u postgres psql -v ON_ERROR_STOP=1 -d happy_agent <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='fitness') THEN
    CREATE SCHEMA fitness AUTHORIZATION fitness_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='agent') THEN
    CREATE SCHEMA agent AUTHORIZATION agent_app;
  END IF;
END$$;
ALTER SCHEMA fitness OWNER TO fitness_app;
ALTER SCHEMA agent OWNER TO agent_app;
REVOKE ALL ON SCHEMA fitness FROM PUBLIC, agent_app;
REVOKE ALL ON SCHEMA agent FROM PUBLIC, fitness_app;
GRANT CONNECT ON DATABASE happy_agent TO fitness_app, agent_app;
GRANT USAGE, CREATE ON SCHEMA fitness TO fitness_app;
GRANT USAGE, CREATE ON SCHEMA agent TO agent_app;
ALTER ROLE fitness_app IN DATABASE happy_agent SET search_path = fitness;
ALTER ROLE agent_app IN DATABASE happy_agent SET search_path = agent;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent REVOKE ALL ON TABLES FROM PUBLIC;
SQL

# ----------------------------------------------------------------------------
# 4. pgvector extension
# ----------------------------------------------------------------------------
log "installing pgvector (if missing)"
if command -v dnf >/dev/null 2>&1; then
  if ! rpm -q "postgresql${POSTGRES_VERSION}-pgvector" >/dev/null 2>&1; then
    dnf install -y "postgresql${POSTGRES_VERSION}-pgvector" 2>/dev/null || {
      log "falling back to source build of pgvector ${PGVECTORS_VERSION}"
      tmp_dir="$(mktemp -d)"
      cd "${tmp_dir}"
      wget -q "https://github.com/pgvector/pgvector/archive/refs/tags/v${PGVECTORS_VERSION}.tar.gz"
      tar -xzf "v${PGVECTORS_VERSION}.tar.gz"
      make -C "pgvector-${PGVECTORS_VERSION}" PG_CONFIG="$(command -v pg_config)"
      make -C "pgvector-${PGVECTORS_VERSION}" install
      cd / && rm -rf "${tmp_dir}"
    }
  fi
fi

# Create both extensions in the agent schema.
sudo -u postgres psql -v ON_ERROR_STOP=1 -d happy_agent -c 'CREATE EXTENSION IF NOT EXISTS vector SCHEMA agent' >/dev/null 2>&1 || \
  sudo -u postgres psql -v ON_ERROR_STOP=1 -d happy_agent -c 'CREATE EXTENSION IF NOT EXISTS vector' >/dev/null
sudo -u postgres psql -v ON_ERROR_STOP=1 -d happy_agent -c 'CREATE EXTENSION IF NOT EXISTS pg_trgm' >/dev/null 2>&1 || true

# Tune PostgreSQL a little if we can edit the config.
PG_CONF="$(command -v pg_config | xargs -I{} dirname {}/../data/postgresql.conf 2>/dev/null || true)"
if [ -f "${PG_CONF_DIR}/data/postgresql.conf" ]; then
  log "tuning PostgreSQL (shared_buffers=${PG_SHARED_BUFFERS}, work_mem=${PG_WORK_MEM})"
  sed -i.bak \
    -e "s/^#shared_buffers.*/shared_buffers = ${PG_SHARED_BUFFERS}/" \
    -e "s/^#work_mem.*/work_mem = ${PG_WORK_MEM}/" \
    "${PG_CONF_DIR}/data/postgresql.conf"
  systemctl reload postgresql || systemctl restart postgresql || true
fi

# ----------------------------------------------------------------------------
# 5. Backend build
# ----------------------------------------------------------------------------
log "building backend (./mvnw package -DskipTests)"
cd "${PROJECT_DIR}"
./mvnw -B -DskipTests -pl starter -am package

mkdir -p "${PROJECT_DIR}/deploy/secrets"
chmod 700 "${PROJECT_DIR}/deploy/secrets"

# Master key for credential encryption.
MASTER_KEY="${PROJECT_DIR}/deploy/secrets/agent-master-key"
if [ ! -s "${MASTER_KEY}" ]; then
  openssl rand -hex 32 > "${MASTER_KEY}"
  chmod 600 "${MASTER_KEY}"
fi

# ----------------------------------------------------------------------------
# 6. systemd service
# ----------------------------------------------------------------------------
log "installing systemd service: happy-agent.service"
cat >/etc/systemd/system/happy-agent.service <<EOF
[Unit]
Description=Happy Agent Platform Backend
After=network.target postgresql.service
Requires=postgresql.service

[Service]
Type=simple
User=${APP_USER}
WorkingDirectory=${PROJECT_DIR}
EnvironmentFile=-${PROJECT_DIR}/deploy/secrets/app.env
Environment=HAPPY_DB_URL=jdbc:postgresql://127.0.0.1:5432/happy_agent
Environment=FITNESS_DB_PASSWORD=${FITNESS_PASSWORD}
Environment=AGENT_DB_PASSWORD=${AGENT_PASSWORD}
Environment=HAPPY_AGENT_MASTER_KEY_FILE=${MASTER_KEY}
Environment=JAVA_TOOL_OPTIONS=-Xms256m -Xmx512m
ExecStart=/usr/bin/java -jar ${PROJECT_DIR}/starter/target/starter-0.0.1-SNAPSHOT-exec.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=5
StandardOutput=append:/var/log/happy-agent/backend.log
StandardError=append:/var/log/happy-agent/backend.log

[Install]
WantedBy=multi-user.target
EOF
mkdir -p /var/log/happy-agent
chown -R "${APP_USER}:${APP_USER}" /var/log/happy-agent

systemctl daemon-reload
systemctl enable --now happy-agent.service

# Wait for the backend to come up.
log "waiting for backend /api/app/bootstrap"
for _ in {1..60}; do
  if curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:${APP_PORT}/api/app/bootstrap 2>/dev/null | grep -qE '200|401'; then
    backend_ok=1
    break
  fi
  sleep 1
done
[ "${backend_ok:-0}" = "1" ] || { journalctl -u happy-agent -n 50 --no-pager; die "backend failed to start"; }

# ----------------------------------------------------------------------------
# 7. Frontend build + nginx
# ----------------------------------------------------------------------------
log "building frontend"
cd "${PROJECT_DIR}/frontend"
if [ ! -d node_modules ]; then
  npm ci --no-audit --no-fund
fi
npm run build

log "configuring nginx"
cat >/etc/nginx/conf.d/happy-agent.conf <<EOF
server {
  listen ${NGINX_PORT};
  server_name _;

  client_max_body_size 20m;

  root ${PROJECT_DIR}/frontend/dist;
  index index.html;

  # SPA fallback — admin and mobile both route via BrowserRouter.
  location / {
    try_files \$uri \$uri/ /index.html;
  }

  location /api/ {
    proxy_pass http://127.0.0.1:${APP_PORT};
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_buffering off;
    proxy_read_timeout 300s;
  }

  location /healthz {
    proxy_pass http://127.0.0.1:${APP_PORT}/actuator/health;
    proxy_set_header Host \$host;
  }
}
EOF
nginx -t
systemctl enable --now nginx
systemctl reload nginx

# ----------------------------------------------------------------------------
# 8. Friendly smoke test
# ----------------------------------------------------------------------------
log "smoke test"
curl -fsS -o /dev/null -w 'admin workbench: %{http_code}\n' \
  -H 'Cookie: SESSION=invalid' http://127.0.0.1:${APP_PORT}/api/admin/workbench || true
curl -fsS -o /dev/null -w 'app bootstrap: %{http_code}\n' \
  http://127.0.0.1:${APP_PORT}/api/app/bootstrap || true

cat <<EOF

==== Happy Agent Platform deployed ====

- Frontend:       http://${PUBLIC_HOST}/                (admin at /admin)
- Backend:        http://127.0.0.1:${APP_PORT}/
- PostgreSQL:     127.0.0.1:5432 / happy_agent (vector + pg_trgm enabled)
- Service log:    /var/log/happy-agent/backend.log
- Deploy log:     ${LOG_FILE}

Day-to-day:
  systemctl status happy-agent
  journalctl -u happy-agent -f
  systemctl restart happy-agent

To rotate the master key:
  mv ${MASTER_KEY} ${MASTER_KEY}.old && openssl rand -hex 32 > ${MASTER_KEY} && systemctl restart happy-agent
EOF
