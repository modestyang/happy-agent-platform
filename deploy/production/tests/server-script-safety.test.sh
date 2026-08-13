#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCRIPTS="$ROOT_DIR/scripts"
WORKTREE_ROOT=$(cd "$ROOT_DIR/../.." && pwd)
TMP_PARENT=${TMPDIR:-/tmp}
TMP=$(mktemp -d "${TMP_PARENT%/}/happy-agent-task4-safety.XXXXXX")
case "$TMP" in "${TMP_PARENT%/}"/happy-agent-task4-safety.*) ;; *) echo 'unsafe temporary directory' >&2; exit 1;; esac
trap 'case "$TMP" in "${TMP_PARENT%/}"/happy-agent-task4-safety.*) rm -rf -- "$TMP";; esac' EXIT
CASE=${1:-all}

fail() { echo "FAIL: $*" >&2; exit 1; }
expect_fail() { if "$@"; then fail "expected failure: $*"; fi; }
assert_contains() { grep -F -- "$2" "$1" >/dev/null || fail "missing $2 in $1"; }
assert_not_contains() { ! grep -F -- "$2" "$1" >/dev/null || fail "unexpected $2 in $1"; }
assert_mode() {
  local mode
  mode=$(stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1")
  [ "$mode" = "$2" ] || fail "mode for $1 is $mode, expected $2"
}

FAKE="$TMP/fake"
mkdir -p "$FAKE"
export PATH="$FAKE:/usr/bin:/bin"

cat >"$FAKE/realpath" <<'EOF'
#!/usr/bin/env bash
[ "${1:-}" = -m ] && shift
[ "${1:-}" = -- ] && shift
if [ -L "$1" ]; then
  link=$(/usr/bin/readlink "$1")
  case "$link" in /*) printf '%s\n' "$link";; *) printf '%s/%s\n' "$(dirname "$1")" "$link";; esac
else
  case "$1" in /*) printf '%s\n' "$1";; *) printf '%s/%s\n' "$(/bin/pwd)" "$1";; esac
fi
EOF

cat >"$FAKE/sha256sum" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = --check ]; then
  shift; [ "${1:-}" != --strict ] || shift
  while IFS=' ' read -r expected file; do
    actual=$(/usr/bin/shasum -a 256 "$file" | awk '{print $1}')
    [ "$actual" = "$expected" ] || exit 1
  done <"$1"
  exit 0
fi
if [ "$#" = 0 ]; then /usr/bin/shasum -a 256; exit; fi
for file in "$@"; do /usr/bin/shasum -a 256 "$file"; done
EOF

cat >"$FAKE/flock" <<'EOF'
#!/usr/bin/env bash
printf 'flock %s\n' "$*" >>"$FAKE_LOG"
exit 0
EOF

cat >"$FAKE/mv" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
args=()
for arg in "$@"; do case "$arg" in -T|-Tf|-fT|--) ;; *) args+=("$arg");; esac; done
target=${args[${#args[@]}-1]}
if [ "${FAKE_SWITCH_FAIL_ONCE:-0}" = 1 ] && [ "$target" = "$HAPPY_AGENT_ROOT/current" ] && [ ! -e "$FAKE_STATE/switch-failed" ]; then
  : >"$FAKE_STATE/switch-failed"
  exit 1
fi
[ ! -L "$target" ] || /bin/rm -f -- "$target"
/bin/mv -f -- "${args[0]}" "$target"
EOF

cat >"$FAKE/openssl" <<'EOF'
#!/usr/bin/env bash
printf 'openssl %s\n' "$*" >>"$FAKE_LOG"
case "$*" in
  *'rand -hex'*) printf '0123456789abcdef0123456789abcdef\n';;
  *'-ext subjectAltName'*)
    if [ "${FAKE_EXTRA_SAN:-0}" = 1 ]; then
      printf 'X509v3 Subject Alternative Name:\n    IP Address:39.101.65.254, DNS:unexpected.example\n'
    else
      printf 'X509v3 Subject Alternative Name:\n    IP Address:39.101.65.254\n'
    fi
    ;;
  *'-enddate'*) printf 'notAfter=Dec 31 23:59:59 2099 GMT\n';;
  *'-fingerprint -sha256'*) printf 'SHA256 Fingerprint=AA:BB\n';;
  *'-pubkey'*) printf 'fixture-public-key\n';;
  *'-checkend'*) exit "${FAKE_CERT_EXPIRES:-0}";;
  *' verify '*) exit 0;;
  *'pkey -pubin'*) cat >/dev/null; printf 'fixture-public-key\n';;
  *'pkey '*) printf 'fixture-public-key\n';;
  *) exit 0;;
esac
EOF

cat >"$FAKE/systemctl" <<'EOF'
#!/usr/bin/env bash
printf 'systemctl %s\n' "$*" >>"$FAKE_LOG"
EOF

cat >"$FAKE/df" <<'EOF'
#!/usr/bin/env bash
printf 'Filesystem Size Used Avail Capacity Mounted on\n/dev/fake 100G 1G 99G 1%% /\n'
EOF
cat >"$FAKE/free" <<'EOF'
#!/usr/bin/env bash
printf 'Mem: 1G 1G 0\n'
EOF
cat >"$FAKE/swapon" <<'EOF'
#!/usr/bin/env bash
printf 'NAME TYPE SIZE USED PRIO\n'
EOF

cat >"$FAKE/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl %s\n' "$*" >>"$FAKE_LOG"
header_file=''; output_file=''; write_out=''; url=''
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  case "${args[$i]}" in
    -D|--dump-header) ((i+=1)); header_file=${args[$i]};;
    -o|--output) ((i+=1)); output_file=${args[$i]};;
    -w|--write-out) ((i+=1)); write_out=${args[$i]};;
    http://*|https://*) url=${args[$i]};;
  esac
done
if [[ "$url" == http://39.101.65.254/.well-known/acme-challenge/* ]]; then
  [ "${FAKE_ACME_FAIL:-0}" != 1 ] || exit 22
  printf '%s' "${url##*/}"
  exit
fi
status=200; content_type='text/html; charset=utf-8'; body='<html>Happy Agent</html>'
case "$url" in
  */api/v1/app/home|*/api/v1/admin/frameworks)
    status=401; content_type='application/problem+json'; body='{"status":401}'
    ;;
  */api/v1/app/ai/runs/00000000-0000-0000-0000-000000000000/events)
    status=401; content_type='application/problem+json'; body='{"status":401}'
    ;;
esac
if [ "${FAKE_PUBLIC_SMOKE_FAIL_ONCE:-0}" = 1 ] && [ ! -e "$FAKE_STATE/smoke-failed" ]; then
  : >"$FAKE_STATE/smoke-failed"; status=503; content_type='text/plain'; body='unavailable'
fi
if [ -n "$header_file" ]; then
  printf 'HTTP/1.1 %s fixture\r\nContent-Type: %s\r\n' "$status" "$content_type" >"$header_file"
  if [ "${FAKE_SSE_CACHE_HEADER_MISSING:-0}" != 1 ] \
      || [[ "$url" != */api/v1/app/ai/runs/*/events ]]; then
    printf 'Cache-Control: no-cache\r\n' >>"$header_file"
  fi
  printf '\r\n' >>"$header_file"
fi
if [ -n "$output_file" ] && [ "$output_file" != /dev/null ]; then printf '%s' "$body" >"$output_file"; fi
if [ -n "$write_out" ]; then printf '%s' "$status"; elif [ -z "$output_file" ]; then printf '%s' "$body"; fi
EOF

cat >"$FAKE/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >>"$FAKE_LOG"

state_set() {
  local service=$1 image=$2 status=${3:-running} health=${4:-healthy}
  printf '%s\n' "$image" >"$FAKE_STATE/$service.image"
  printf '%s\n' "$status" >"$FAKE_STATE/$service.status"
  printf '%s\n' "$health" >"$FAKE_STATE/$service.health"
}
release_env=''
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  if [ "${args[$i]}" = --env-file ]; then ((i+=1)); release_env=${args[$i]}; fi
done
release_id=''; app_image=''; web_image=''
if [ -n "$release_env" ] && [ -f "$release_env" ]; then
  release_id=$(sed -n 's/^RELEASE_ID=//p' "$release_env")
  app_image=$(sed -n 's/^APP_IMAGE=//p' "$release_env")
  web_image=$(sed -n 's/^WEB_IMAGE=//p' "$release_env")
fi

case " $* " in
  *' inspect '*'postgres-id'*|*' inspect '*'app-id'*|*' inspect '*'nginx-id'*)
    id=${!#}; service=${id%-id}
    printf '%s|%s|%s|%s\n' "$id" "$(cat "$FAKE_STATE/$service.image")" "$(cat "$FAKE_STATE/$service.status")" "$(cat "$FAKE_STATE/$service.health")"
    ;;
  *' compose '*' ps -q postgres '*) printf 'postgres-id\n';;
  *' compose '*' ps -q app '*) printf 'app-id\n';;
  *' compose '*' ps -q nginx '*) printf 'nginx-id\n';;
  *' compose '*' config app '*) printf 'services:\n  app:\n    image: %s\n' "$app_image";;
  *' compose '*' config nginx '*) printf 'services:\n  nginx:\n    image: %s\n' "$web_image";;
  *' compose '*' config postgres '*) printf 'services:\n  postgres:\n    image: happy-agent-postgres:%s\n' "$release_id";;
  *' compose '*' ps '*'--format'*' postgres '*) printf 'postgres running healthy\n';;
  *' compose '*' ps '*'--format'*' app '*) printf 'app running healthy\n';;
  *' compose '*' ps '*'--format'*' nginx '*) printf 'nginx running healthy\n';;
  *' compose '*' up '*)
    services=" $* "
    if [[ "$services" == *' postgres '* ]]; then
      state_set postgres "happy-agent-postgres:$release_id"
      : >"$FAKE_STATE/postgres.touched"
    fi
    if [[ "$services" == *' app '* ]]; then state_set app "$app_image"; fi
    if [ "${FAKE_PARTIAL_UP_ONCE:-0}" = 1 ] && [ "$release_id" = new ] && [ ! -e "$FAKE_STATE/partial-up-failed" ]; then
      : >"$FAKE_STATE/partial-up-failed"
      exit 1
    fi
    if [ "${FAKE_RECOVERY_UP_FAIL:-0}" = 1 ] && [ "$release_id" = old ]; then exit 1; fi
    if [[ "$services" == *' nginx '* ]]; then state_set nginx "$web_image"; fi
    ;;
  *' compose '*' stop '*)
    if [ "${FAKE_STOP_FAIL_ONCE:-0}" = 1 ] && [ ! -e "$FAKE_STATE/stop-failed" ]; then
      : >"$FAKE_STATE/stop-failed"
      state_set app "$(cat "$FAKE_STATE/app.image")" stopped unhealthy
      exit 1
    fi
    [[ " $* " != *' app '* ]] || state_set app "$(cat "$FAKE_STATE/app.image")" stopped unhealthy
    [[ " $* " != *' nginx '* ]] || state_set nginx "$(cat "$FAKE_STATE/nginx.image")" stopped unhealthy
    ;;
  *' compose '*' exec '*' pg_dump '*)
    [ "${FAKE_PG_DUMP_FAIL:-0}" != 1 ] || exit 1
    printf 'custom-dump-fixture\n'
    ;;
  *' compose '*' exec '*' nginx '*) :;;
  *' compose '*' ps '*)
    printf 'postgres running healthy\napp running healthy\nnginx running healthy\n'
    ;;
  *' run '*'certbot/certbot:'*' renew '*) [ "${FAKE_RENEW_FAIL:-0}" != 1 ] || exit 1;;
  *' run '*'certbot/certbot:'*)
    if [[ "$*" == *happy-agent-ip-staging* ]]; then
      cert_dir="$HAPPY_AGENT_ROOT/certificates/staging/live/happy-agent-ip-staging"
    else
      cert_dir="$HAPPY_AGENT_ROOT/certificates/production/live/happy-agent-ip"
    fi
    mkdir -p "$cert_dir"
    : >"$cert_dir/cert.pem"; : >"$cert_dir/fullchain.pem"; : >"$cert_dir/chain.pem"; : >"$cert_dir/privkey.pem"
    ;;
  *' load '*) :;;
esac
EOF

chmod +x "$FAKE"/*

for script in common.sh bootstrap-host.sh issue-certificate.sh renew-certificate.sh backup.sh restore-initial-data.sh activate-release.sh rollback.sh status.sh; do
  [ -x "$SCRIPTS/$script" ] || fail "missing product script: $script"
done

make_release() {
  local root=$1 id=$2 release="$1/releases/$2"
  mkdir -p "$release/images" "$release/postgres"
  cp "$ROOT_DIR/compose.yml" "$release/compose.yml"
  cp "$ROOT_DIR/postgres/init-roles.sh" "$release/postgres/init-roles.sh"
  cp "$ROOT_DIR/postgres/init-roles.sql" "$release/postgres/init-roles.sql"
  cp "$ROOT_DIR/postgres/enforce-isolation.sql" "$release/postgres/enforce-isolation.sql"
  [ ! -f "$ROOT_DIR/postgres/assert-initial-empty-target.sql" ] || cp "$ROOT_DIR/postgres/assert-initial-empty-target.sql" "$release/postgres/assert-initial-empty-target.sql"
  printf 'RELEASE_ID=%s\nAPP_IMAGE=happy-agent-app:%s\nWEB_IMAGE=happy-agent-web:%s\n' "$id" "$id" "$id" >"$release/.env"
  printf 'image-%s\n' "$id" >"$release/images/$id.tar"
  printf 'server { # %s\n}\n' "$id" >"$release/nginx.conf"
  (cd "$release" && find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | sed 's#^./##' | while IFS= read -r file; do sha256sum "$file"; done >SHA256SUMS)
}

setup_operation() {
  local name=$1 root="$TMP/$1/root"
  export HAPPY_AGENT_ROOT="$root"
  export FAKE_STATE="$TMP/$name/state"
  export FAKE_LOG="$TMP/$name/boundary.log"
  export HAPPY_AGENT_TIMESTAMP="20260813T${2}Z"
  export HAPPY_AGENT_HEALTH_ATTEMPTS=1
  export HAPPY_AGENT_HEALTH_INTERVAL=0
  mkdir -p "$FAKE_STATE" "$root/releases" "$root/state/generations/migrated/postgres" \
    "$root/state/generations/migrated/media" "$root/secrets" "$root/data/media" "$root/data/acme-webroot" \
    "$root/certificates/staging" "$root/certificates/production" "$root/backups" "$root/logs"
  printf 'media\n' >"$root/state/generations/migrated/media/file"
  printf 'master-key-fixture\000bytes' >"$root/state/generations/migrated/agent-master-key"
  printf 'postgres-password\n' >"$root/secrets/postgres-password"
  printf 'fitness-password\n' >"$root/secrets/fitness-db-password"
  printf 'agent-password\n' >"$root/secrets/agent-db-password"
  chmod 0600 "$root/state/generations/migrated/agent-master-key" "$root/secrets"/*
  ln -s generations/migrated "$root/state/current"
  make_release "$root" old
  make_release "$root" new
  ln -s releases/old "$root/current"
  printf 'happy-agent-postgres:stable\n' >"$FAKE_STATE/postgres.image"
  printf 'happy-agent-app:old\n' >"$FAKE_STATE/app.image"
  printf 'happy-agent-web:old\n' >"$FAKE_STATE/nginx.image"
  for service in postgres app nginx; do printf 'running\n' >"$FAKE_STATE/$service.status"; printf 'healthy\n' >"$FAKE_STATE/$service.health"; done
  : >"$FAKE_LOG"
  unset FAKE_PARTIAL_UP_ONCE FAKE_STOP_FAIL_ONCE FAKE_RECOVERY_UP_FAIL FAKE_PUBLIC_SMOKE_FAIL_ONCE \
    FAKE_SSE_CACHE_HEADER_MISSING FAKE_SWITCH_FAIL_ONCE
}

assert_old_runtime_restored() {
  [ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/old ] || fail 'recovery did not restore old release symlink'
  [ "$(cat "$FAKE_STATE/postgres.image")" = happy-agent-postgres:stable ] || fail 'activation changed PostgreSQL identity'
  [ ! -e "$FAKE_STATE/postgres.touched" ] || fail 'activation operated on PostgreSQL'
  [ "$(cat "$FAKE_STATE/app.image")" = happy-agent-app:old ] || fail 'recovery did not restore old App image'
  [ "$(cat "$FAKE_STATE/nginx.image")" = happy-agent-web:old ] || fail 'recovery did not restore old Nginx image'
  [ "$(cat "$FAKE_STATE/app.health")" = healthy ] || fail 'recovered App is unhealthy'
  [ "$(cat "$FAKE_STATE/nginx.health")" = healthy ] || fail 'recovered Nginx is unhealthy'
}

assert_only_app_nginx_up() {
  local line
  while IFS= read -r line; do
    case "$line" in *' compose '*' up '*)
      [[ "$line" == *' --no-deps '* ]] || fail 'release up omitted --no-deps'
      [[ "$line" != *' postgres '* ]] || fail 'release up included PostgreSQL'
      ;;
    esac
  done <"$FAKE_LOG"
}

run_release_tests() {
  local output backup_line up_line

  setup_operation partial 010101
  export FAKE_PARTIAL_UP_ONCE=1 FAKE_STOP_FAIL_ONCE=1
  expect_fail "$SCRIPTS/activate-release.sh" new
  unset FAKE_PARTIAL_UP_ONCE FAKE_STOP_FAIL_ONCE
  assert_old_runtime_restored
  assert_only_app_nginx_up
  assert_contains "$FAKE_LOG" '--no-buffer'
  assert_contains "$FAKE_LOG" 'Accept: text/event-stream'
  assert_contains "$FAKE_LOG" 'Cache-Control: no-cache'
  [ "$(grep -Fc 'https://39.101.65.254/' "$FAKE_LOG")" -ge 1 ] || fail 'recovery did not rerun public smoke'

  setup_operation smoke 010102
  export FAKE_PUBLIC_SMOKE_FAIL_ONCE=1
  expect_fail "$SCRIPTS/activate-release.sh" new
  unset FAKE_PUBLIC_SMOKE_FAIL_ONCE
  assert_old_runtime_restored
  [ "$(grep -Fc 'https://39.101.65.254/' "$FAKE_LOG")" -ge 2 ] || fail 'smoke failure recovery did not rerun public smoke'

  setup_operation stream-header 010112
  export FAKE_SSE_CACHE_HEADER_MISSING=1
  expect_fail "$SCRIPTS/activate-release.sh" new
  unset FAKE_SSE_CACHE_HEADER_MISSING
  assert_old_runtime_restored

  setup_operation switch 010103
  export FAKE_SWITCH_FAIL_ONCE=1
  expect_fail "$SCRIPTS/activate-release.sh" new
  unset FAKE_SWITCH_FAIL_ONCE
  assert_old_runtime_restored

  setup_operation recovery-failure 010104
  output="$TMP/recovery-failure/output"
  mkdir -p "$(dirname "$output")"
  export FAKE_PARTIAL_UP_ONCE=1 FAKE_RECOVERY_UP_FAIL=1
  if "$SCRIPTS/activate-release.sh" new >"$output" 2>&1; then fail 'activation succeeded when recovery failed'; fi
  unset FAKE_PARTIAL_UP_ONCE FAKE_RECOVERY_UP_FAIL
  [ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/old ] || fail 'failed recovery did not restore old symlink first'
  assert_contains "$output" 'previous release recovery failed'

  setup_operation activation 010105
  "$SCRIPTS/activate-release.sh" new
  [ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/new ] || fail 'healthy activation did not select target release'
  [ "$(cat "$FAKE_STATE/postgres.image")" = happy-agent-postgres:stable ] || fail 'healthy activation changed PostgreSQL identity'
  [ "$(cat "$FAKE_STATE/app.image")" = happy-agent-app:new ] || fail 'healthy activation did not select target App image'
  [ "$(cat "$FAKE_STATE/nginx.image")" = happy-agent-web:new ] || fail 'healthy activation did not select target Nginx image'
  assert_only_app_nginx_up
  backup_line=$(awk '/pg_dump/ {print NR; exit}' "$FAKE_LOG")
  up_line=$(awk '/ compose .* up / {print NR; exit}' "$FAKE_LOG")
  [ "$backup_line" -lt "$up_line" ] || fail 'activation backup did not precede replacement'
  assert_contains "$HAPPY_AGENT_ROOT/backups/$HAPPY_AGENT_TIMESTAMP/state-metadata" 'generation_id=migrated'

  setup_operation rollback 010106
  "$SCRIPTS/rollback.sh" new
  [ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/new ] || fail 'healthy rollback did not select target release'
  [ "$(cat "$FAKE_STATE/postgres.image")" = happy-agent-postgres:stable ] || fail 'rollback changed PostgreSQL identity'
  assert_not_contains "$FAKE_LOG" 'pg_restore'
  assert_not_contains "$FAKE_LOG" 'pg_dump'
  assert_only_app_nginx_up
  echo 'PASS: stateful release and rollback transactions'
}

run_certificate_backup_status_tests() {
  local challenge_dir status secret backup invalid_backup
  setup_operation certificates 010107

  export FAKE_ACME_FAIL=1
  expect_fail "$SCRIPTS/issue-certificate.sh"
  unset FAKE_ACME_FAIL
  challenge_dir="$HAPPY_AGENT_ROOT/data/acme-webroot/.well-known/acme-challenge"
  [ ! -d "$challenge_dir" ] || [ -z "$(find "$challenge_dir" -type f -print -quit)" ] || fail 'failed ACME proof leaked its challenge'
  assert_not_contains "$FAKE_LOG" 'certbot/certbot:'

  : >"$FAKE_LOG"
  export FAKE_EXTRA_SAN=1
  expect_fail "$SCRIPTS/issue-certificate.sh"
  unset FAKE_EXTRA_SAN
  assert_not_contains "$FAKE_LOG" 'systemctl enable --now happy-agent-cert-renew.timer'

  : >"$FAKE_LOG"
  "$SCRIPTS/issue-certificate.sh"
  assert_contains "$FAKE_LOG" 'certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4'
  assert_contains "$FAKE_LOG" '--ip-address 39.101.65.254'
  assert_contains "$FAKE_LOG" '--email modest_yang@126.com'
  assert_contains "$FAKE_LOG" '--preferred-profile shortlived'
  assert_contains "$FAKE_LOG" 'systemctl enable --now happy-agent-cert-renew.timer'
  staging_line=$(awk '/happy-agent-ip-staging/ {print NR; exit}' "$FAKE_LOG")
  production_line=$(awk '/happy-agent-ip/ && !/staging/ {print NR; exit}' "$FAKE_LOG")
  [ "$staging_line" -lt "$production_line" ] || fail 'production certificate was attempted before staging'

  export FAKE_RENEW_FAIL=1 FAKE_CERT_EXPIRES=1
  expect_fail "$SCRIPTS/renew-certificate.sh"
  unset FAKE_RENEW_FAIL
  assert_contains "$HAPPY_AGENT_ROOT/logs/cert-renew.log" 'renewal-failed-expiring'
  export FAKE_CERT_EXPIRES=0
  "$SCRIPTS/renew-certificate.sh"
  unset FAKE_CERT_EXPIRES
  assert_contains "$FAKE_LOG" 'renew --preferred-profile shortlived'
  assert_contains "$FAKE_LOG" 'nginx -t'

  export HAPPY_AGENT_TIMESTAMP=20260813T010108Z
  "$SCRIPTS/backup.sh"
  backup="$HAPPY_AGENT_ROOT/backups/$HAPPY_AGENT_TIMESTAMP"
  [ -d "$backup" ] || fail 'backup did not atomically publish its complete directory'
  for file in "$backup"/*; do assert_mode "$file" 600; done
  assert_contains "$backup/state-metadata" 'generation_id=migrated'
  assert_contains "$backup/release-metadata" 'release_id=old'
  cmp "$HAPPY_AGENT_ROOT/state/current/agent-master-key" "$backup/agent-master-key" >/dev/null \
    || fail 'backup did not preserve Agent master-key bytes'

  export HAPPY_AGENT_TIMESTAMP=20260813T010109Z FAKE_PG_DUMP_FAIL=1
  expect_fail "$SCRIPTS/backup.sh"
  unset FAKE_PG_DUMP_FAIL
  [ ! -e "$HAPPY_AGENT_ROOT/backups/$HAPPY_AGENT_TIMESTAMP" ] \
    || fail 'failed backup published a complete directory'
  [ ! -e "$HAPPY_AGENT_ROOT/backups/.pending-$HAPPY_AGENT_TIMESTAMP" ] \
    || fail 'failed backup leaked its pending directory'

  invalid_backup="$HAPPY_AGENT_ROOT/backups/20260813T010110Z"
  cp -R "$backup" "$invalid_backup"
  printf 'tampered\n' >>"$invalid_backup/database.dump"
  cp -R "$backup" "$HAPPY_AGENT_ROOT/backups/.pending-20260813T010111Z"
  secret='master-key-fixture'
  status=$($SCRIPTS/status.sh)
  case "$status" in *"$secret"*) fail 'status leaked secret content';; esac
  case "$status" in
    *'latest-backup: 20260813T010108Z'*) ;;
    *) printf 'status output:\n%s\n' "$status" >&2; fail 'status selected an invalid or pending backup';;
  esac

  assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.service" 'Type=oneshot'
  assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.service" 'WorkingDirectory=/opt/happy-agent/current'
  assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.service" 'Environment=HAPPY_AGENT_ROOT=/opt/happy-agent'
  assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.service" 'ExecStart=/opt/happy-agent/current/scripts/renew-certificate.sh'
  assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.timer" 'OnBootSec=10min'
  assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.timer" 'OnUnitActiveSec=12h'
  assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.timer" 'Persistent=true'
  echo 'PASS: certificate, backup, and status safety'
}

case "$CASE" in
  release) run_release_tests;;
  certificate) run_certificate_backup_status_tests;;
  all) run_release_tests; run_certificate_backup_status_tests;;
  *) fail "unknown test case: $CASE";;
esac

[ ! -e "$WORKTREE_ROOT/secrets" ] || fail 'test wrote secrets at the worktree root'
echo 'PASS: server script safety'
