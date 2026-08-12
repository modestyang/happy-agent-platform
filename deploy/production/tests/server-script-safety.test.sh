#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCRIPTS="$ROOT_DIR/scripts"
WORKTREE_ROOT=$(cd "$ROOT_DIR/../.." && pwd)
TMP=$(mktemp -d)
[ "$TMP" != / ] && [ -d "$TMP" ] || { echo "unsafe temporary directory" >&2; exit 1; }
trap 'rm -rf -- "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
assert() { "$@" || fail "$*"; }
assert_file_mode() { local mode; mode=$(stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"); [ "$mode" = "$2" ] || fail "mode for $1"; }
assert_contains() { grep -F -- "$2" "$1" >/dev/null || fail "missing $2 in $1"; }
assert_not_contains() { ! grep -F -- "$2" "$1" >/dev/null || fail "unexpected $2 in $1"; }

FAKE="$TMP/fake"
mkdir -p "$FAKE" "$TMP/root" "$TMP/systemd"
LOG="$TMP/boundary.log"
export PATH="$FAKE:/usr/bin:/bin"
export HAPPY_AGENT_ROOT="$TMP/root"
export HAPPY_AGENT_OS_RELEASE_PATH="$TMP/os-release"
export HAPPY_AGENT_FSTAB_PATH="$TMP/fstab"
export HAPPY_AGENT_SWAPFILE="$TMP/swapfile"
export HAPPY_AGENT_SYSTEMD_UNIT_DIR="$TMP/systemd"
export HAPPY_AGENT_APT_KEYRING_DIR="$TMP/apt/keyrings"
export HAPPY_AGENT_APT_SOURCES_DIR="$TMP/apt/sources.list.d"
export HAPPY_AGENT_LOCK_FILE="$TMP/operation.lock"
export HAPPY_AGENT_TIMESTAMP=20260813T000000Z
export HAPPY_AGENT_HEALTH_ATTEMPTS=1
export HAPPY_AGENT_HEALTH_INTERVAL=0
export FAKE_LOG="$LOG"
export FAKE_STATE="$TMP/state"
export HAPPY_AGENT_TEST_SYSTEM_ROOT="$TMP"
printf 'ID=ubuntu\nVERSION_ID="22.04"\n' >"$HAPPY_AGENT_OS_RELEASE_PATH"

fake() { cat >"$FAKE/$1"; chmod +x "$FAKE/$1"; }
fake id <<'EOF'
#!/usr/bin/env bash
[ "${1:-}" = -u ] && printf '%s\n' "${FAKE_UID:-0}" || /usr/bin/id "$@"
EOF
fake realpath <<'EOF'
#!/usr/bin/env bash
[ "${1:-}" = -m ] && shift
[ "${1:-}" = -- ] && shift
if [ -L "$1" ]; then link=$(/usr/bin/readlink "$1"); case "$link" in /*) printf '%s\n' "$link";; *) printf '%s/%s\n' "$(dirname "$1")" "$link";; esac; else case "$1" in /*) printf '%s\n' "$1";; *) /bin/pwd; esac; fi
EOF
fake flock <<'EOF'
#!/usr/bin/env bash
printf 'flock %s\n' "$*" >>"$FAKE_LOG"
exit 0
EOF
fake mv <<'EOF'
#!/usr/bin/env bash
args=()
for arg in "$@"; do case "$arg" in -T|-Tf|-fT) ;; --) ;; *) args+=("$arg");; esac; done
source=${args[0]}; target=${args[1]}
[ ! -L "$target" ] || /bin/rm -f -- "$target"
/bin/mv -f -- "$source" "$target"
EOF
fake sha256sum <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = --check ]; then
  shift
  [ "${1:-}" != --strict ] || shift
  while IFS=' ' read -r expected file; do
    actual=$(/usr/bin/shasum -a 256 "$file" | awk '{print $1}')
    [ "$actual" = "$expected" ] || exit 1
  done <"$1"
  exit 0
fi
[ "$#" -gt 0 ] || { /usr/bin/shasum -a 256; exit 0; }
for file in "$@"; do /usr/bin/shasum -a 256 "$file" | sed "s#  $file#  $file#"; done
EOF
fake stat <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = -c ] && [ "${2:-}" = %s ]; then printf '2147483648\n'; else /usr/bin/stat "$@"; fi
EOF
fake file <<'EOF'
#!/usr/bin/env bash
printf 'Linux swap file\n'
EOF
fake uname <<'EOF'
#!/usr/bin/env bash
[ "${1:-}" = -m ] && printf '%s\n' "${FAKE_ARCH:-x86_64}" || /usr/bin/uname "$@"
EOF
fake apt-get <<'EOF'
#!/usr/bin/env bash
printf 'apt-get %s\n' "$*" >>"$FAKE_LOG"
EOF
fake curl <<'EOF'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >>"$FAKE_LOG"
case "$*" in
  *gpg*) printf key;;
  *'acme-challenge/'*) [ "${FAKE_ACME_FAIL:-0}" = 1 ] && exit 1; url=${!#}; printf '%s' "${url##*/}";;
  *'/api/app/bootstrap'*) printf '401';;
  *'/admin'*) printf '401';;
  *'/api/app/events'*) printf '401';;
  *) printf '200';;
esac
EOF
fake gpg <<'EOF'
#!/usr/bin/env bash
while [ "$#" -gt 0 ]; do
  if [ "$1" = -o ]; then cat >"$2"; exit 0; fi
  shift
done
cat >/dev/null
EOF
fake mkswap <<'EOF'
#!/usr/bin/env bash
printf 'mkswap %s\n' "$*" >>"$FAKE_LOG"
EOF
fake swapon <<'EOF'
#!/usr/bin/env bash
printf 'swapon %s\n' "$*" >>"$FAKE_LOG"
if [ "${1:-}" = --show=NAME ]; then [ ! -f "$FAKE_STATE/swap" ] || cat "$FAKE_STATE/swap"; else mkdir -p "$FAKE_STATE"; printf '%s\n' "$1" >"$FAKE_STATE/swap"; fi
EOF
fake openssl <<'EOF'
#!/usr/bin/env bash
printf 'openssl %s\n' "$*" >>"$FAKE_LOG"
case "$*" in
  *'rand -hex'*) printf '0123456789abcdef0123456789abcdef\n';;
  *'-ext subjectAltName'*) printf 'IP Address:39.101.65.254\n';;
  *'-enddate'*) printf 'notAfter=Dec 31 23:59:59 2099 GMT\n';;
  *'-checkend'*) exit "${FAKE_CERT_EXPIRES:-0}";;
  *) exit 0;;
esac
EOF
fake docker <<'EOF'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >>"$FAKE_LOG"
case "$*" in
  *' renew '*) if [ "${FAKE_RENEW_FAIL:-0}" = 1 ]; then exit 1; fi;;
  *'certbot'*)
    if [[ "$*" == *happy-agent-ip-staging* ]]; then d="$HAPPY_AGENT_ROOT/certificates/staging/live/happy-agent-ip-staging"; else d="$HAPPY_AGENT_ROOT/certificates/production/live/happy-agent-ip"; fi
    mkdir -p "$d"; : >"$d/fullchain.pem"; : >"$d/privkey.pem"; : >"$d/cert.pem"; : >"$d/chain.pem";;
  *' psql '* ) if [ "${FAKE_PSQL_OUTPUT:-0}" != 0 ]; then printf '%s\n' "$FAKE_PSQL_OUTPUT"; elif [[ "$*" == *flyway_schema_history* ]]; then printf '0|0|0|0\n'; elif [[ "$*" == *pg_namespace* ]]; then printf '0\n0\n0\n0\n0\n0\n2\n0\n1\n0\n0\n'; else printf '0\n'; fi;;
  *'compose '*ps*)
    [ "${FAKE_HEALTH_FAIL:-0}" = 1 ] || case "$*" in *postgres*) printf 'postgres running healthy\n';; *nginx*) printf 'nginx running healthy\n';; *app*) printf 'app running healthy\n';; *) printf 'postgres running healthy\napp running healthy\nnginx running healthy\n';; esac;;
esac
EOF
fake systemctl <<'EOF'
#!/usr/bin/env bash
printf 'systemctl %s\n' "$*" >>"$FAKE_LOG"
EOF
fake ss <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
fake df <<'EOF'
#!/usr/bin/env bash
printf 'Filesystem 1K-blocks Used Available Use%% Mounted on\n/dev/fake 10000000 1 9000000 1 /\n'
EOF
fake pg_restore <<'EOF'
#!/usr/bin/env bash
printf 'pg_restore %s\n' "$*" >>"$FAKE_LOG"
EOF
fake pg_dump <<'EOF'
#!/usr/bin/env bash
printf 'pg_dump %s\n' "$*" >>"$FAKE_LOG"
cat /dev/null
EOF
fake psql <<'EOF'
#!/usr/bin/env bash
printf 'psql %s\n' "$*" >>"$FAKE_LOG"
printf '%s\n' "${FAKE_PSQL_OUTPUT:-0}"
EOF
fake nginx <<'EOF'
#!/usr/bin/env bash
printf 'nginx %s\n' "$*" >>"$FAKE_LOG"
EOF
fake free <<'EOF'
#!/usr/bin/env bash
printf 'Mem: 1 1 0\n'
EOF

run() { "$@"; }
expect_fail() { if "$@"; then fail "expected failure: $*"; fi; }

for script in common.sh bootstrap-host.sh issue-certificate.sh renew-certificate.sh backup.sh restore-initial-data.sh activate-release.sh rollback.sh status.sh; do
  [ -x "$SCRIPTS/$script" ] || fail "missing product script: $script"
done

: >"$LOG"
FAKE_UID=1000 expect_fail "$SCRIPTS/bootstrap-host.sh"
[ ! -s "$LOG" ] || fail "non-root bootstrap mutated a boundary"
printf 'ID=debian\nVERSION_ID="12"\n' >"$HAPPY_AGENT_OS_RELEASE_PATH"
expect_fail "$SCRIPTS/bootstrap-host.sh"
[ ! -s "$LOG" ] || fail "non-Ubuntu bootstrap mutated a boundary"
printf 'ID=ubuntu\nVERSION_ID="22.04"\n' >"$HAPPY_AGENT_OS_RELEASE_PATH"
FAKE_ARCH=aarch64 expect_fail "$SCRIPTS/bootstrap-host.sh"
[ ! -s "$LOG" ] || fail "non-x86 bootstrap mutated a boundary"

"$SCRIPTS/bootstrap-host.sh"
"$SCRIPTS/bootstrap-host.sh"
[ ! -e "$WORKTREE_ROOT/secrets" ] || fail "bootstrap wrote outside the temporary root"
assert_file_mode "$HAPPY_AGENT_SWAPFILE" 600
[ "$(grep -Fc "$HAPPY_AGENT_SWAPFILE none swap sw 0 0" "$HAPPY_AGENT_FSTAB_PATH")" = 1 ] || fail "swap fstab entry is not idempotent"
[ "$(grep -Fc "mkswap" "$LOG")" = 1 ] || fail "swap created more than once"
for d in releases data/postgres data/media data/acme-webroot secrets certificates/staging certificates/production backups logs; do
  [ -d "$HAPPY_AGENT_ROOT/$d" ] || fail "missing state directory $d"
done
assert_file_mode "$HAPPY_AGENT_ROOT/secrets" 700
for secret in postgres-password fitness-db-password agent-db-password; do
  [ -s "$HAPPY_AGENT_ROOT/secrets/$secret" ] || fail "empty generated secret file"
  assert_file_mode "$HAPPY_AGENT_ROOT/secrets/$secret" 600
done
before=$(sha256sum "$HAPPY_AGENT_ROOT/secrets/postgres-password")
"$SCRIPTS/bootstrap-host.sh"
after=$(sha256sum "$HAPPY_AGENT_ROOT/secrets/postgres-password")
[ "$before" = "$after" ] || fail "bootstrap overwrote an existing password"
assert_contains "$LOG" "download.docker.com"

make_release() {
  local id=$1 release
  release="$HAPPY_AGENT_ROOT/releases/$id"
  mkdir -p "$release/images"
  cp "$ROOT_DIR/compose.yml" "$release/compose.yml"
  printf 'RELEASE_ID=%s\nAPP_IMAGE=app:%s\nWEB_IMAGE=web:%s\n' "$id" "$id" "$id" >"$release/.env"
  printf 'worker\n' >"$release/images/$id.tar"
  printf 'server {}\n' >"$release/nginx.conf"
  (cd "$release" && sha256sum .env compose.yml nginx.conf "images/$id.tar" >SHA256SUMS)
}
make_release old
make_release new
ln -s releases/old "$HAPPY_AGENT_ROOT/current"

bundle="$TMP/bundle"
mkdir -p "$bundle"
printf 'dump' >"$bundle/initial.dump"
mkdir "$TMP/media-source"; printf 'media' >"$TMP/media-source/file"
tar -C "$TMP/media-source" -cf "$bundle/media.tar" .
printf 'master-key-fixture\000bytes' >"$bundle/agent-master-key"
media_hash=$(sha256sum "$bundle/media.tar" | awk '{print $1}')
key_hash=$(sha256sum "$bundle/agent-master-key" | awk '{print $1}')
tree_hash=$(cd "$TMP/media-source" && find . -type f -print | LC_ALL=C sort | while IFS= read -r file; do sha256sum "$file"; done | sha256sum | awk '{print $1}')
printf 'fitness_history_count=0\nagent_history_count=0\napplication_table_count=0\nkey_object_count=0\nmedia_sha256=%s\nmedia_tree_sha256=%s\nmaster_key_sha256=%s\n' "$media_hash" "$tree_hash" "$key_hash" >"$bundle/metadata.env"
(cd "$bundle" && sha256sum initial.dump media.tar agent-master-key metadata.env >SHA256SUMS)
expect_fail "$SCRIPTS/restore-initial-data.sh" "$TMP/no-bundle" --initial-empty-target
expect_fail "$SCRIPTS/restore-initial-data.sh" "$bundle"
printf 'tampered' >>"$bundle/initial.dump"
expect_fail "$SCRIPTS/restore-initial-data.sh" "$bundle" --initial-empty-target
printf 'dump' >"$bundle/initial.dump"; (cd "$bundle" && sha256sum initial.dump media.tar agent-master-key metadata.env >SHA256SUMS)
FAKE_PSQL_OUTPUT=1 expect_fail "$SCRIPTS/restore-initial-data.sh" "$bundle" --initial-empty-target
FAKE_PSQL_OUTPUT=0 "$SCRIPTS/restore-initial-data.sh" "$bundle" --initial-empty-target
assert_not_contains "$LOG" "pg_restore --clean"
assert_not_contains "$LOG" "pg_restore --create"
assert_contains "$LOG" "pg_restore --exit-on-error"
assert_contains "$LOG" "n.nspname not in ('information_schema','public','fitness','agent')"
assert_contains "$LOG" "pg_default_acl"
assert_file_mode "$HAPPY_AGENT_ROOT/secrets/agent-master-key" 600
cmp "$bundle/agent-master-key" "$HAPPY_AGENT_ROOT/secrets/agent-master-key" || fail "master key was not byte copied"
[ -f "$HAPPY_AGENT_ROOT/data/media/file" ] || fail "staged media was not committed"

: >"$LOG"
"$SCRIPTS/activate-release.sh" new
[ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/new ] || fail "activation did not select healthy release"
assert_contains "$LOG" "pg_dump"
backup_line=$(grep -n 'pg_dump' "$LOG" | head -n1 | cut -d: -f1)
replacement_line=$(grep -n ' up -d postgres app nginx' "$LOG" | head -n1 | cut -d: -f1)
[ "$backup_line" -lt "$replacement_line" ] || fail "backup did not precede replacement"
 [ "$(grep -Fc 'flock -x 9' "$LOG")" = 1 ] || fail "activate to backup lock was not re-entrant"
assert_contains "$LOG" "compose -p happy-agent --env-file $HAPPY_AGENT_ROOT/releases/new/.env -f $HAPPY_AGENT_ROOT/releases/new/compose.yml up -d"
ln -sfn releases/old "$HAPPY_AGENT_ROOT/current"
FAKE_HEALTH_FAIL=1 expect_fail "$SCRIPTS/activate-release.sh" new
[ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/old ] || fail "failed activation changed current"
unset FAKE_HEALTH_FAIL

: >"$LOG"
"$SCRIPTS/rollback.sh" new
[ "$(readlink "$HAPPY_AGENT_ROOT/current")" = releases/new ] || fail "rollback did not select target"
assert_not_contains "$LOG" "pg_restore"
assert_not_contains "$LOG" "pg_dump"
assert_not_contains "$LOG" " postgres "

: >"$LOG"
FAKE_ACME_FAIL=1 expect_fail "$SCRIPTS/issue-certificate.sh"
[ -z "$(find "$HAPPY_AGENT_ROOT/data/acme-webroot/.well-known/acme-challenge" -type f -print -quit)" ] || fail "failed ACME proof leaked challenge"
assert_not_contains "$LOG" 'certbot'
: >"$LOG"
"$SCRIPTS/issue-certificate.sh"
staging_line=$(grep -n 'happy-agent-ip-staging' "$LOG" | head -n1 | cut -d: -f1)
production_line=$(grep -n 'happy-agent-ip' "$LOG" | grep -v staging | head -n1 | cut -d: -f1)
[ "$staging_line" -lt "$production_line" ] || fail "production cert was not issued after staging"
assert_contains "$LOG" 'certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4'
assert_contains "$LOG" '--ip-address 39.101.65.254'
assert_contains "$LOG" '--email modest_yang@126.com'
assert_contains "$LOG" 'systemctl enable --now happy-agent-cert-renew.timer'
: >"$LOG"
FAKE_RENEW_FAIL=1 FAKE_CERT_EXPIRES=1 expect_fail "$SCRIPTS/renew-certificate.sh"
assert_contains "$HAPPY_AGENT_ROOT/logs/cert-renew.log" 'renewal-failed-expiring'
unset FAKE_RENEW_FAIL
FAKE_CERT_EXPIRES=0 "$SCRIPTS/renew-certificate.sh"
assert_contains "$LOG" 'renew --preferred-profile shortlived'
assert_contains "$LOG" 'nginx -t'

assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.service" 'Type=oneshot'
assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.service" 'WorkingDirectory=/opt/happy-agent/current'
assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.service" 'ExecStart=/opt/happy-agent/current/scripts/renew-certificate.sh'
assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.timer" 'OnBootSec=10min'
assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.timer" 'OnUnitActiveSec=12h'
assert_contains "$ROOT_DIR/systemd/happy-agent-cert-renew.timer" 'Persistent=true'
status=$($SCRIPTS/status.sh)
case "$status" in *master-key-fixture*) fail "status leaked secret";; esac
echo "PASS: server script safety"
