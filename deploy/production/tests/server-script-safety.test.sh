#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCRIPTS=${TEST_SCRIPTS:-"$ROOT_DIR/scripts"}
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
set -euo pipefail
printf 'openssl %s\n' "$*" >>"$FAKE_LOG"
fixture_value() {
  local key=$1 file=$2
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$file"
}
input=''; chain_file=''; args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  case "${args[$i]}" in
    -in) ((i+=1)); input=${args[$i]};;
    -untrusted) ((i+=1)); chain_file=${args[$i]};;
  esac
done
case " ${*:-} " in
  *' rand -hex '*) printf '0123456789abcdef0123456789abcdef\n';;
  *' x509 '*'-ext subjectAltName'*)
    printf 'X509v3 Subject Alternative Name:\n    %s\n' "$(fixture_value san "$input")"
    ;;
  *' x509 '*'-checkend '*)
    [ "${FAKE_CERT_EXPIRES:-0}" != 1 ] && [ "$(fixture_value expiry "$input")" = valid ]
    ;;
  *' x509 '*'-outform DER '*) printf 'leaf-der:%s\n' "$(fixture_value leaf "$input")";;
  *' x509 '*'-pubkey '*) printf 'key-der:%s\n' "$(fixture_value public-key "$input")";;
  *' x509 '*'-enddate'*) printf 'notAfter=Dec 31 23:59:59 2099 GMT\n';;
  *' x509 '*'-fingerprint -sha256'*) printf 'SHA256 Fingerprint=AA:BB\n';;
  *' pkey '*'-pubin '*) cat;;
  *' pkey '*'-in '*) printf 'key-der:%s\n' "$(fixture_value public-key "$input")";;
  *' verify '*)
    cert_file=${args[${#args[@]}-1]}
    [ "$(fixture_value trust "$chain_file")" = trusted ] \
      && [ "$(fixture_value issuer "$cert_file")" = "$(fixture_value chain "$chain_file")" ]
    ;;
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
header_file=''; output_file=''; write_out=''; url=''; config_file=''; authenticated=0
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  case "${args[$i]}" in
    -D|--dump-header) ((i+=1)); header_file=${args[$i]};;
    -o|--output) ((i+=1)); output_file=${args[$i]};;
    -w|--write-out) ((i+=1)); write_out=${args[$i]};;
    -K|--config) ((i+=1)); config_file=${args[$i]};;
    http://*|https://*) url=${args[$i]};;
  esac
done
if [ -n "$config_file" ] && grep -Fxq \
    'header = "Cookie: FITNESS_SESSION=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"' \
    "$config_file"; then
  authenticated=1
fi
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
  */api/v1/app/ai/runs/11111111-1111-4111-8111-111111111111/events)
    if [ "$authenticated" = 1 ]; then
      status=${FAKE_AUTH_SSE_STATUS:-200}
      content_type=${FAKE_AUTH_SSE_CONTENT_TYPE:-text/event-stream}
      body=$'id: 1\nevent: RUN_STATE\ndata: {"type":"RUN_STATE"}\n\n'
    else
      status=401; content_type='application/problem+json'; body='{"status":401}'
    fi
    ;;
esac
if [ "${FAKE_PUBLIC_SMOKE_FAIL_ONCE:-0}" = 1 ] && [ ! -e "$FAKE_STATE/smoke-failed" ]; then
  : >"$FAKE_STATE/smoke-failed"; status=503; content_type='text/plain'; body='unavailable'
fi
if [ -n "$header_file" ]; then
  printf 'HTTP/1.1 %s fixture\r\nContent-Type: %s\r\n' "$status" "$content_type" >"$header_file"
  if [ "${FAKE_SSE_CACHE_HEADER_MISSING:-0}" != 1 ] || [[ "$url" != */api/v1/app/ai/runs/*/events ]]; then
    if [ "$authenticated" = 1 ]; then
      printf 'Cache-Control: %s\r\n' "${FAKE_AUTH_SSE_CACHE_CONTROL:-no-cache}" >>"$header_file"
    else
      printf 'Cache-Control: no-cache\r\n' >>"$header_file"
    fi
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
  if [ -n "${release_id:-}" ]; then
    mkdir -p "$FAKE_STATE/releases/$release_id"
    printf '%s\n' "$image" >"$FAKE_STATE/releases/$release_id/$service.image"
    printf '%s\n' "$status" >"$FAKE_STATE/releases/$release_id/$service.status"
    printf '%s\n' "$health" >"$FAKE_STATE/releases/$release_id/$service.health"
  fi
}

write_certificate_fixture() {
  local certificate_root=$1 certificate_name=$2 mode=${3:-valid}
  local archive_dir="$certificate_root/archive/$certificate_name"
  local live_dir="$certificate_root/live/$certificate_name"
  local leaf="leaf-$certificate_name" fullchain_leaf="leaf-$certificate_name"
  local cert_key="key-$certificate_name" private_key="key-$certificate_name"
  local chain="chain-$certificate_name" issuer="chain-$certificate_name"
  local trust=trusted expiry=valid
  local san='IP Address:39.101.65.254'
  case "$mode" in
    valid) ;;
    leaf) fullchain_leaf="different-leaf-$certificate_name";;
    key) private_key="different-key-$certificate_name";;
    chain) chain="different-chain-$certificate_name";;
    trust) trust=untrusted;;
    extra-san) san='IP Address:39.101.65.254, DNS:unexpected.example';;
    wrong-san) san='IP Address:203.0.113.9';;
    expiry) expiry=under-48h;;
    *) exit 97;;
  esac
  mkdir -p "$archive_dir" "$live_dir"
  printf 'leaf=%s\npublic-key=%s\nsan=%s\nexpiry=%s\nissuer=%s\n' \
    "$leaf" "$cert_key" "$san" "$expiry" "$issuer" >"$archive_dir/cert1.pem"
  printf 'leaf=%s\nchain=%s\n' "$fullchain_leaf" "$chain" >"$archive_dir/fullchain1.pem"
  printf 'chain=%s\ntrust=%s\n' "$chain" "$trust" >"$archive_dir/chain1.pem"
  printf 'public-key=%s\n' "$private_key" >"$archive_dir/privkey1.pem"
  ln -sfn "../../archive/$certificate_name/cert1.pem" "$live_dir/cert.pem"
  ln -sfn "../../archive/$certificate_name/fullchain1.pem" "$live_dir/fullchain.pem"
  ln -sfn "../../archive/$certificate_name/chain1.pem" "$live_dir/chain.pem"
  ln -sfn "../../archive/$certificate_name/privkey1.pem" "$live_dir/privkey.pem"
}

requested_certificate_mode() {
  if [ "${FAKE_CERT_LEAF_MISMATCH:-0}" = 1 ]; then printf 'leaf\n'
  elif [ "${FAKE_CERT_KEY_MISMATCH:-0}" = 1 ]; then printf 'key\n'
  elif [ "${FAKE_CERT_CHAIN_MISMATCH:-0}" = 1 ]; then printf 'chain\n'
  elif [ "${FAKE_CERT_TRUST_FAIL:-0}" = 1 ]; then printf 'trust\n'
  elif [ "${FAKE_EXTRA_SAN:-0}" = 1 ]; then printf 'extra-san\n'
  elif [ "${FAKE_WRONG_SAN:-0}" = 1 ]; then printf 'wrong-san\n'
  elif [ "${FAKE_CERT_EXPIRES:-0}" = 1 ]; then printf 'expiry\n'
  else printf 'valid\n'
  fi
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
    printf '%s|%s|%s|%s\n' "$(cat "$FAKE_STATE/$service.id")" "$(cat "$FAKE_STATE/$service.image")" "$(cat "$FAKE_STATE/$service.status")" "$(cat "$FAKE_STATE/$service.health")"
    ;;
  *' compose '*' ps -q postgres '*) cat "$FAKE_STATE/postgres.id";;
  *' compose '*' ps -q app '*) cat "$FAKE_STATE/app.id";;
  *' compose '*' ps -q nginx '*) cat "$FAKE_STATE/nginx.id";;
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
    if [[ "$services" == *' app '* ]]; then
      runtime_image=$app_image; runtime_health=healthy
      if [ "$release_id" = new ] && [ "${FAKE_TARGET_WRONG_IMAGE_SERVICE:-}" = app ]; then
        runtime_image='happy-agent-app:wrong'
      fi
      if [ "$release_id" = new ] && [ "${FAKE_TARGET_UNHEALTHY_SERVICE:-}" = app ]; then
        runtime_health=unhealthy
      fi
      if [ "$release_id" = old ] && [ "${FAKE_RECOVERY_WRONG_IMAGE_SERVICE:-}" = app ]; then
        runtime_image='happy-agent-app:wrong'
      fi
      if [ "$release_id" = old ] && [ "${FAKE_RECOVERY_UNHEALTHY_SERVICE:-}" = app ]; then
        runtime_health=unhealthy
      fi
      state_set app "$runtime_image" running "$runtime_health"
    fi
    if [ "${FAKE_PARTIAL_UP_ONCE:-0}" = 1 ] && [ "$release_id" = new ] && [ ! -e "$FAKE_STATE/partial-up-failed" ]; then
      : >"$FAKE_STATE/partial-up-failed"
      exit 1
    fi
    if [ "${FAKE_RECOVERY_UP_FAIL:-0}" = 1 ] && [ "$release_id" = old ]; then exit 1; fi
    if [[ "$services" == *' nginx '* ]]; then
      runtime_image=$web_image; runtime_health=healthy
      if [ "$release_id" = new ] && [ "${FAKE_TARGET_WRONG_IMAGE_SERVICE:-}" = nginx ]; then
        runtime_image='happy-agent-web:wrong'
      fi
      if [ "$release_id" = new ] && [ "${FAKE_TARGET_UNHEALTHY_SERVICE:-}" = nginx ]; then
        runtime_health=unhealthy
      fi
      if [ "$release_id" = old ] && [ "${FAKE_RECOVERY_WRONG_IMAGE_SERVICE:-}" = nginx ]; then
        runtime_image='happy-agent-web:wrong'
      fi
      if [ "$release_id" = old ] && [ "${FAKE_RECOVERY_UNHEALTHY_SERVICE:-}" = nginx ]; then
        runtime_health=unhealthy
      fi
      state_set nginx "$runtime_image" running "$runtime_health"
    fi
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
  *' run '*'certbot/certbot:'*' renew '*)
    [ "${FAKE_RENEW_FAIL:-0}" != 1 ] || exit 1
    write_certificate_fixture "$HAPPY_AGENT_ROOT/certificates/production" happy-agent-ip \
      "${FAKE_RENEW_INVALID:-valid}"
    ;;
  *' run '*'certbot/certbot:'*)
    if [[ "$*" == *happy-agent-ip-staging* ]]; then
      cert_root="$HAPPY_AGENT_ROOT/certificates/staging"
      cert_name=happy-agent-ip-staging
    else
      cert_root="$HAPPY_AGENT_ROOT/certificates/production"
      cert_name=happy-agent-ip
    fi
    write_certificate_fixture "$cert_root" "$cert_name" "$(requested_certificate_mode)"
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
  printf '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n' \
    >"$root/secrets/public-smoke-session"
  printf '11111111-1111-4111-8111-111111111111\n' >"$root/secrets/public-smoke-run-id"
  chmod 0600 "$root/state/generations/migrated/agent-master-key" "$root/secrets"/*
  ln -s generations/migrated "$root/state/current"
  make_release "$root" old
  make_release "$root" new
  ln -s releases/old "$root/current"
  printf 'happy-agent-postgres:stable\n' >"$FAKE_STATE/postgres.image"
  printf 'postgres-id\n' >"$FAKE_STATE/postgres.id"
  printf 'happy-agent-app:old\n' >"$FAKE_STATE/app.image"
  printf 'app-id\n' >"$FAKE_STATE/app.id"
  printf 'happy-agent-web:old\n' >"$FAKE_STATE/nginx.image"
  printf 'nginx-id\n' >"$FAKE_STATE/nginx.id"
  for service in postgres app nginx; do printf 'running\n' >"$FAKE_STATE/$service.status"; printf 'healthy\n' >"$FAKE_STATE/$service.health"; done
  mkdir -p "$FAKE_STATE/releases/old" "$FAKE_STATE/releases/new"
  for service in app nginx; do
    cp "$FAKE_STATE/$service.image" "$FAKE_STATE/releases/old/$service.image"
    printf 'running\n' >"$FAKE_STATE/releases/old/$service.status"
    printf 'healthy\n' >"$FAKE_STATE/releases/old/$service.health"
    case "$service" in
      app) printf 'happy-agent-app:new\n' >"$FAKE_STATE/releases/new/$service.image";;
      nginx) printf 'happy-agent-web:new\n' >"$FAKE_STATE/releases/new/$service.image";;
    esac
    printf 'stopped\n' >"$FAKE_STATE/releases/new/$service.status"
    printf 'unhealthy\n' >"$FAKE_STATE/releases/new/$service.health"
  done
  : >"$FAKE_LOG"
  unset FAKE_PARTIAL_UP_ONCE FAKE_STOP_FAIL_ONCE FAKE_RECOVERY_UP_FAIL FAKE_PUBLIC_SMOKE_FAIL_ONCE \
    FAKE_SSE_CACHE_HEADER_MISSING FAKE_SWITCH_FAIL_ONCE FAKE_AUTH_SSE_STATUS \
    FAKE_AUTH_SSE_CONTENT_TYPE FAKE_AUTH_SSE_CACHE_CONTROL FAKE_TARGET_WRONG_IMAGE_SERVICE \
    FAKE_TARGET_UNHEALTHY_SERVICE FAKE_RECOVERY_WRONG_IMAGE_SERVICE \
    FAKE_RECOVERY_UNHEALTHY_SERVICE FAKE_CERT_LEAF_MISMATCH FAKE_CERT_KEY_MISMATCH \
    FAKE_CERT_CHAIN_MISMATCH FAKE_CERT_TRUST_FAIL FAKE_EXTRA_SAN FAKE_WRONG_SAN FAKE_CERT_EXPIRES \
    FAKE_RENEW_INVALID FAKE_RENEW_FAIL
}

assert_old_runtime_restored() {
  [ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/old ] || fail 'recovery did not restore old release symlink'
  [ "$(cat "$FAKE_STATE/postgres.image")" = happy-agent-postgres:stable ] || fail 'activation changed PostgreSQL identity'
  [ "$(cat "$FAKE_STATE/postgres.status")" = running ] || fail 'recovery did not preserve running PostgreSQL'
  [ "$(cat "$FAKE_STATE/postgres.health")" = healthy ] || fail 'recovery did not preserve healthy PostgreSQL'
  [ ! -e "$FAKE_STATE/postgres.touched" ] || fail 'activation operated on PostgreSQL'
  [ "$(cat "$FAKE_STATE/app.image")" = happy-agent-app:old ] || fail 'recovery did not restore old App image'
  [ "$(cat "$FAKE_STATE/nginx.image")" = happy-agent-web:old ] || fail 'recovery did not restore old Nginx image'
  [ "$(cat "$FAKE_STATE/app.status")" = running ] || fail 'recovered App is not running'
  [ "$(cat "$FAKE_STATE/nginx.status")" = running ] || fail 'recovered Nginx is not running'
  [ "$(cat "$FAKE_STATE/app.health")" = healthy ] || fail 'recovered App is unhealthy'
  [ "$(cat "$FAKE_STATE/nginx.health")" = healthy ] || fail 'recovered Nginx is unhealthy'
  [ "$(cat "$FAKE_STATE/releases/old/app.image")" = happy-agent-app:old ] \
    || fail 'old release did not persist exact App identity'
  [ "$(cat "$FAKE_STATE/releases/old/nginx.image")" = happy-agent-web:old ] \
    || fail 'old release did not persist exact Nginx identity'
  [ "$(cat "$FAKE_STATE/releases/old/app.status")" = running ] \
    || fail 'old release did not persist running App status'
  [ "$(cat "$FAKE_STATE/releases/old/nginx.status")" = running ] \
    || fail 'old release did not persist running Nginx status'
  [ "$(cat "$FAKE_STATE/releases/old/app.health")" = healthy ] \
    || fail 'old release did not persist healthy App state'
  [ "$(cat "$FAKE_STATE/releases/old/nginx.health")" = healthy ] \
    || fail 'old release did not persist healthy Nginx state'
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

assert_postgres_unchanged() {
  [ "$(cat "$FAKE_STATE/postgres.id")" = postgres-id ] \
    || fail 'release transaction changed PostgreSQL container identity'
  [ "$(cat "$FAKE_STATE/postgres.image")" = happy-agent-postgres:stable ] \
    || fail 'release transaction changed PostgreSQL image'
  [ "$(cat "$FAKE_STATE/postgres.status")" = running ] \
    || fail 'release transaction changed PostgreSQL status'
  [ "$(cat "$FAKE_STATE/postgres.health")" = healthy ] \
    || fail 'release transaction changed PostgreSQL health'
  [ ! -e "$FAKE_STATE/postgres.touched" ] || fail 'release transaction operated on PostgreSQL'
}

inject_attempt_failure() {
  case "$1" in
    stop) export FAKE_STOP_FAIL_ONCE=1;;
    partial) export FAKE_PARTIAL_UP_ONCE=1;;
    target-app-image) export FAKE_TARGET_WRONG_IMAGE_SERVICE=app;;
    target-nginx-image) export FAKE_TARGET_WRONG_IMAGE_SERVICE=nginx;;
    target-app-health) export FAKE_TARGET_UNHEALTHY_SERVICE=app;;
    target-nginx-health) export FAKE_TARGET_UNHEALTHY_SERVICE=nginx;;
    smoke) export FAKE_PUBLIC_SMOKE_FAIL_ONCE=1;;
    switch) export FAKE_SWITCH_FAIL_ONCE=1;;
    *) fail "unknown release attempt failure: $1";;
  esac
}

inject_recovery_failure() {
  case "$1" in
    none) ;;
    up) export FAKE_RECOVERY_UP_FAIL=1;;
    app-image) export FAKE_RECOVERY_WRONG_IMAGE_SERVICE=app;;
    nginx-image) export FAKE_RECOVERY_WRONG_IMAGE_SERVICE=nginx;;
    app-health) export FAKE_RECOVERY_UNHEALTHY_SERVICE=app;;
    nginx-health) export FAKE_RECOVERY_UNHEALTHY_SERVICE=nginx;;
    *) fail "unknown release recovery failure: $1";;
  esac
}

assert_attempt_reached_failure() {
  case "$1" in
    stop) [ -e "$FAKE_STATE/stop-failed" ] || fail 'stop failure injection was not reached';;
    partial) [ -e "$FAKE_STATE/partial-up-failed" ] || fail 'partial target up injection was not reached';;
    target-app-image)
      [ "$(cat "$FAKE_STATE/releases/new/app.image")" = happy-agent-app:wrong ] \
        || fail 'target App wrong-image state was not persisted'
      ;;
    target-nginx-image)
      [ "$(cat "$FAKE_STATE/releases/new/nginx.image")" = happy-agent-web:wrong ] \
        || fail 'target Nginx wrong-image state was not persisted'
      ;;
    target-app-health)
      [ "$(cat "$FAKE_STATE/releases/new/app.health")" = unhealthy ] \
        || fail 'target App unhealthy state was not persisted'
      ;;
    target-nginx-health)
      [ "$(cat "$FAKE_STATE/releases/new/nginx.health")" = unhealthy ] \
        || fail 'target Nginx unhealthy state was not persisted'
      ;;
    smoke) [ -e "$FAKE_STATE/smoke-failed" ] || fail 'public smoke failure injection was not reached';;
    switch) [ -e "$FAKE_STATE/switch-failed" ] || fail 'symlink switch failure injection was not reached';;
  esac
}

assert_recovery_order() {
  local up_line identity_line smoke_line
  up_line=$(awk '/releases\/old\/.env/ && / compose / && / up -d --no-deps app nginx/ {line=NR} END {print line}' "$FAKE_LOG")
  [ -n "$up_line" ] || fail 'recovery did not start old App/Nginx'
  identity_line=$(awk -v start="$up_line" 'NR > start && /docker inspect .*app-id/ {print NR; exit}' "$FAKE_LOG")
  [ -n "$identity_line" ] || fail 'recovery did not verify old App identity/health after start'
  smoke_line=$(awk -v start="$identity_line" \
    'NR > start && /curl .*https:\/\/39[.]101[.]65[.]254\/$/ {print NR; exit}' "$FAKE_LOG")
  [ -n "$smoke_line" ] || fail 'recovery did not run public smoke after identity/health checks'
}

run_rollback_recovery_case() {
  local label=$1 trigger=$2 recovery_failure=$3 output="$TMP/$1/output"
  setup_operation "$label" 010119
  mkdir -p "$(dirname "$output")"
  inject_attempt_failure "$trigger"
  inject_recovery_failure "$recovery_failure"
  if "$SCRIPTS/rollback.sh" new >"$output" 2>&1; then
    fail "rollback accepted $trigger failure with $recovery_failure recovery"
  fi
  assert_attempt_reached_failure "$trigger"
  [ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/old ] \
    || fail "$label did not restore the old release link"
  assert_postgres_unchanged
  assert_only_app_nginx_up
  if [ "$recovery_failure" = none ]; then
    assert_old_runtime_restored
    assert_recovery_order
    assert_contains "$output" 'rollback failed; previous release recovered'
  else
    assert_contains "$output" 'rollback failed; previous release recovery failed'
  fi
}

run_release_tests() {
  local output backup_line up_line

  setup_operation partial 010101
  export FAKE_PARTIAL_UP_ONCE=1
  expect_fail "$SCRIPTS/activate-release.sh" new
  unset FAKE_PARTIAL_UP_ONCE
  [ -e "$FAKE_STATE/partial-up-failed" ] || fail 'activation did not reach target partial up'
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

  setup_operation authenticated-stream-status 010113
  export FAKE_AUTH_SSE_STATUS=503
  expect_fail "$SCRIPTS/activate-release.sh" new
  unset FAKE_AUTH_SSE_STATUS
  assert_old_runtime_restored

  setup_operation authenticated-stream-type 010114
  export FAKE_AUTH_SSE_CONTENT_TYPE=application/json
  expect_fail "$SCRIPTS/activate-release.sh" new
  unset FAKE_AUTH_SSE_CONTENT_TYPE
  assert_old_runtime_restored

  setup_operation authenticated-stream-cache 010115
  export FAKE_AUTH_SSE_CACHE_CONTROL='public, max-age=60'
  expect_fail "$SCRIPTS/activate-release.sh" new
  unset FAKE_AUTH_SSE_CACHE_CONTROL
  assert_old_runtime_restored

  setup_operation missing-stream-session 010120
  /bin/mv "$HAPPY_AGENT_ROOT/secrets/public-smoke-session" \
    "$HAPPY_AGENT_ROOT/secrets/public-smoke-session.absent"
  expect_fail "$SCRIPTS/activate-release.sh" new
  assert_old_runtime_restored

  setup_operation missing-stream-run 010121
  /bin/mv "$HAPPY_AGENT_ROOT/secrets/public-smoke-run-id" \
    "$HAPPY_AGENT_ROOT/secrets/public-smoke-run-id.absent"
  expect_fail "$SCRIPTS/rollback.sh" new
  assert_old_runtime_restored

  setup_operation authenticated-stream-rollback-status 010116
  export FAKE_AUTH_SSE_STATUS=503
  expect_fail "$SCRIPTS/rollback.sh" new
  unset FAKE_AUTH_SSE_STATUS
  assert_old_runtime_restored

  setup_operation authenticated-stream-rollback-type 010117
  export FAKE_AUTH_SSE_CONTENT_TYPE=application/json
  expect_fail "$SCRIPTS/rollback.sh" new
  unset FAKE_AUTH_SSE_CONTENT_TYPE
  assert_old_runtime_restored

  setup_operation authenticated-stream-rollback-cache 010118
  export FAKE_AUTH_SSE_CACHE_CONTROL='public, max-age=60'
  expect_fail "$SCRIPTS/rollback.sh" new
  unset FAKE_AUTH_SSE_CACHE_CONTROL
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
  assert_contains "$FAKE_LOG" '--config'
  assert_contains "$FAKE_LOG" '/api/v1/app/ai/runs/11111111-1111-4111-8111-111111111111/events'
  assert_not_contains "$FAKE_LOG" '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
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

run_rollback_recovery_tests() {
  local trigger recovery_failure
  if [ -n "${MUTATION_PROBE:-}" ]; then
    case "$MUTATION_PROBE" in
      recovery) run_rollback_recovery_case mutation-recovery partial none;;
      target-image) run_rollback_recovery_case mutation-target-image target-app-image none;;
      target-health) run_rollback_recovery_case mutation-target-health target-app-health none;;
      recovered-image) run_rollback_recovery_case mutation-recovered-image partial app-image;;
      recovered-health) run_rollback_recovery_case mutation-recovered-health partial app-health;;
      *) fail "unknown mutation probe: $MUTATION_PROBE";;
    esac
    return
  fi
  for trigger in stop partial target-app-image target-nginx-image \
    target-app-health target-nginx-health smoke switch; do
    run_rollback_recovery_case "rollback-$trigger-recovered" "$trigger" none
    run_rollback_recovery_case "rollback-$trigger-recovery-failed" "$trigger" up
  done
  for recovery_failure in app-image nginx-image app-health nginx-health; do
    run_rollback_recovery_case "rollback-recovered-$recovery_failure" partial "$recovery_failure"
  done
  echo 'PASS: rollback recovery identity and health matrix'
}

run_release_mutation_check() {
  local mutation_root mutated output

  mutation_root="$TMP/mutation-no-rollback-recovery"
  mutated="$mutation_root/scripts"
  output="$mutation_root/output"
  mkdir -p "$mutated"
  cp -R "$SCRIPTS/." "$mutated/"
  awk '
    /^  if _recover_previous_release / {
      print "  die '\''rollback failed; previous release recovery failed'\''"
      skipping = 1
      next
    }
    skipping && /^  die '\''rollback failed; previous release recovery failed'\''/ {
      skipping = 0
      next
    }
    !skipping { print }
  ' "$SCRIPTS/rollback.sh" >"$mutated/rollback.sh"
  chmod +x "$mutated/rollback.sh"
  bash -n "$mutated/rollback.sh"
  if SKIP_MUTATION_CHECK=1 MUTATION_PROBE=recovery TEST_SCRIPTS="$mutated" \
      bash "$0" rollback-recovery \
      >"$output" 2>&1; then
    fail 'rollback recovery mutation survived the stateful release suite'
  fi
  assert_contains "$output" 'did not restore the old release link'

  mutation_root="$TMP/mutation-no-target-identity"
  mutated="$mutation_root/scripts"
  output="$mutation_root/output"
  mkdir -p "$mutated"
  cp -R "$SCRIPTS/." "$mutated/"
  awk '
    /^  \[ -n "\$id" \] && \[ "\$image" = "\$expected" \] && \[ "\$status" = running \]/ {
      print "  [ -n \"$id\" ] && [ \"$status\" = running ] && [ \"$health\" = healthy ]"
      next
    }
    { print }
  ' "$SCRIPTS/common.sh" >"$mutated/common.sh"
  bash -n "$mutated/common.sh"
  if SKIP_MUTATION_CHECK=1 MUTATION_PROBE=target-image TEST_SCRIPTS="$mutated" \
      bash "$0" rollback-recovery >"$output" 2>&1; then
    fail 'target identity-check mutation survived the stateful release suite'
  fi
  assert_contains "$output" 'rollback accepted target-app-image failure'

  mutation_root="$TMP/mutation-no-target-health"
  mutated="$mutation_root/scripts"
  output="$mutation_root/output"
  mkdir -p "$mutated"
  cp -R "$SCRIPTS/." "$mutated/"
  awk '
    /^  \[ -n "\$id" \] && \[ "\$image" = "\$expected" \] && \[ "\$status" = running \]/ {
      print "  [ -n \"$id\" ] && [ \"$image\" = \"$expected\" ] && [ \"$status\" = running ]"
      next
    }
    { print }
  ' "$SCRIPTS/common.sh" >"$mutated/common.sh"
  bash -n "$mutated/common.sh"
  if SKIP_MUTATION_CHECK=1 MUTATION_PROBE=target-health TEST_SCRIPTS="$mutated" \
      bash "$0" rollback-recovery >"$output" 2>&1; then
    fail 'target health-check mutation survived the stateful release suite'
  fi
  assert_contains "$output" 'rollback accepted target-app-health failure'

  for recovery_probe in recovered-image recovered-health; do
    mutation_root="$TMP/mutation-no-$recovery_probe-check"
    mutated="$mutation_root/scripts"
    output="$mutation_root/output"
    mkdir -p "$mutated"
    cp -R "$SCRIPTS/." "$mutated/"
    awk '
      /^  wait_application_runtime "\$previous" / { print "  :"; next }
      { print }
    ' "$SCRIPTS/common.sh" >"$mutated/common.sh"
    bash -n "$mutated/common.sh"
    if SKIP_MUTATION_CHECK=1 MUTATION_PROBE="$recovery_probe" TEST_SCRIPTS="$mutated" \
        bash "$0" rollback-recovery >"$output" 2>&1; then
      fail "$recovery_probe check mutation survived the stateful release suite"
    fi
    assert_contains "$output" 'missing rollback failed; previous release recovery failed'
  done
  echo 'PASS: rollback recovery mutation checks'
}

run_certificate_backup_status_tests() {
  local challenge_dir status secret backup invalid_backup invalid_case invalid_variable
  setup_operation certificates 010107

  export FAKE_ACME_FAIL=1
  expect_fail "$SCRIPTS/issue-certificate.sh"
  unset FAKE_ACME_FAIL
  challenge_dir="$HAPPY_AGENT_ROOT/data/acme-webroot/.well-known/acme-challenge"
  [ ! -d "$challenge_dir" ] || [ -z "$(find "$challenge_dir" -type f -print -quit)" ] || fail 'failed ACME proof leaked its challenge'
  assert_not_contains "$FAKE_LOG" 'certbot/certbot:'

  for invalid_case in leaf key chain trust extra-san wrong-san expiry; do
    : >"$FAKE_LOG"
    case "$invalid_case" in
      leaf) invalid_variable=FAKE_CERT_LEAF_MISMATCH;;
      key) invalid_variable=FAKE_CERT_KEY_MISMATCH;;
      chain) invalid_variable=FAKE_CERT_CHAIN_MISMATCH;;
      trust) invalid_variable=FAKE_CERT_TRUST_FAIL;;
      extra-san) invalid_variable=FAKE_EXTRA_SAN;;
      wrong-san) invalid_variable=FAKE_WRONG_SAN;;
      expiry) invalid_variable=FAKE_CERT_EXPIRES;;
    esac
    export "$invalid_variable=1"
    expect_fail "$SCRIPTS/issue-certificate.sh"
    unset "$invalid_variable"
    assert_not_contains "$FAKE_LOG" 'systemctl enable --now happy-agent-cert-renew.timer'
  done

  : >"$FAKE_LOG"
  "$SCRIPTS/issue-certificate.sh"
  assert_contains "$FAKE_LOG" 'certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4'
  assert_contains "$FAKE_LOG" '--ip-address 39.101.65.254'
  assert_contains "$FAKE_LOG" '--email modest_yang@126.com'
  assert_contains "$FAKE_LOG" '--preferred-profile shortlived'
  assert_contains "$FAKE_LOG" 'systemctl enable --now happy-agent-cert-renew.timer'
  for certificate_member in cert fullchain chain privkey; do
    [ -L "$HAPPY_AGENT_ROOT/certificates/production/live/happy-agent-ip/$certificate_member.pem" ] \
      || fail "happy certificate fixture omitted Certbot live symlink: $certificate_member.pem"
    [ "$(readlink "$HAPPY_AGENT_ROOT/certificates/production/live/happy-agent-ip/$certificate_member.pem")" \
        = "../../archive/happy-agent-ip/${certificate_member}1.pem" ] \
      || fail "happy certificate fixture has an invalid Certbot target: $certificate_member.pem"
    [ -s "$HAPPY_AGENT_ROOT/certificates/production/archive/happy-agent-ip/${certificate_member}1.pem" ] \
      || fail "happy certificate fixture omitted archive bytes: ${certificate_member}1.pem"
  done
  staging_line=$(awk '/happy-agent-ip-staging/ {print NR; exit}' "$FAKE_LOG")
  production_line=$(awk '/happy-agent-ip/ && !/staging/ {print NR; exit}' "$FAKE_LOG")
  [ "$staging_line" -lt "$production_line" ] || fail 'production certificate was attempted before staging'

  : >"$FAKE_LOG"
  export FAKE_RENEW_INVALID=leaf
  expect_fail "$SCRIPTS/renew-certificate.sh"
  unset FAKE_RENEW_INVALID
  assert_not_contains "$FAKE_LOG" 'nginx -t'
  assert_not_contains "$FAKE_LOG" 'nginx -s reload'

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
  release) run_release_tests; run_rollback_recovery_tests; [ "${SKIP_MUTATION_CHECK:-0}" = 1 ] || run_release_mutation_check;;
  rollback-recovery) run_rollback_recovery_tests;;
  mutation) run_release_mutation_check;;
  certificate) run_certificate_backup_status_tests;;
  all) run_release_tests; run_rollback_recovery_tests; [ "${SKIP_MUTATION_CHECK:-0}" = 1 ] || run_release_mutation_check; run_certificate_backup_status_tests;;
  *) fail "unknown test case: $CASE";;
esac

[ ! -e "$WORKTREE_ROOT/secrets" ] || fail 'test wrote secrets at the worktree root'
echo 'PASS: server script safety'
