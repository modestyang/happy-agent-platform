#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORKTREE_ROOT=$(cd "$ROOT_DIR/../.." && pwd)
UBUNTU_IMAGE='ubuntu:22.04@sha256:3b06811b2afd352be909dd088a004166d665dc76d38b13eada33522a9d915c6f'
TMP_PARENT=${TMPDIR:-/tmp}
TMP=$(mktemp -d "${TMP_PARENT%/}/happy-agent-task4-runtime.XXXXXX")
case "$TMP" in "${TMP_PARENT%/}"/happy-agent-task4-runtime.*) ;; *) echo 'unsafe temporary directory' >&2; exit 1;; esac
CONTAINER="happy-agent-task4-runtime-$(basename "$TMP" | tr -cd 'a-zA-Z0-9_.-')"
cleanup() {
  local status=$? suffix
  for suffix in non-root wrong-os wrong-arch lock bootstrap; do
    docker rm -f "$CONTAINER-$suffix" >/dev/null 2>&1 || true
  done
  case "$TMP" in "${TMP_PARENT%/}"/happy-agent-task4-runtime.*) rm -rf -- "$TMP";; esac
  return "$status"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

cat >"$TMP/lock-probe.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
export HAPPY_AGENT_ROOT=/var/tmp/happy-agent-lock-root
source /workspace/deploy/production/scripts/common.sh
critical_section() {
  local worker=$1
  printf 'start:%s\n' "$worker" >>/test/lock-order
  sleep 1
  printf 'end:%s\n' "$worker" >>/test/lock-order
}
with_lock critical_section "$1"
EOF
chmod +x "$TMP/lock-probe.sh"

cat >"$TMP/run-lock-test.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
direct_worker() {
  local worker=$1
  (
    flock -x 8
    printf 'start:%s\n' "$worker" >>/test/control-order
    sleep 1
    printf 'end:%s\n' "$worker" >>/test/control-order
  ) 8>/var/tmp/happy-agent-control.lock
}
rm -f /test/control-order
direct_worker first &
control_first=$!
for _ in $(seq 1 50); do
  [ -f /test/control-order ] && grep -Fxq start:first /test/control-order && break
  sleep 0.02
done
direct_worker second &
control_second=$!
sleep 0.2
[ "$(cat /test/control-order)" = start:first ] || { echo 'FAIL: container filesystem does not support real flock' >&2; exit 1; }
wait "$control_first"
wait "$control_second"
expected=$'start:first\nend:first\nstart:second\nend:second'
[ "$(cat /test/control-order)" = "$expected" ] || { echo 'FAIL: direct flock control order is invalid' >&2; exit 1; }

rm -f /test/lock-order
/test/lock-probe.sh first &
first_pid=$!
for _ in $(seq 1 50); do
  [ -f /test/lock-order ] && grep -Fxq start:first /test/lock-order && break
  sleep 0.02
done
[ -f /test/lock-order ] && grep -Fxq start:first /test/lock-order || { echo 'FAIL: first lock process did not start' >&2; exit 1; }
/test/lock-probe.sh second &
second_pid=$!
sleep 0.2
[ "$(cat /test/lock-order)" = start:first ] || { echo 'FAIL: production lock allowed overlap' >&2; exit 1; }
wait "$first_pid"
wait "$second_pid"
[ "$(cat /test/lock-order)" = "$expected" ] || { printf 'FAIL: unexpected lock order\n%s\n' "$(cat /test/lock-order)" >&2; exit 1; }
echo 'PASS: real flock serializes production lock wrapper'
EOF
chmod +x "$TMP/run-lock-test.sh"

mkdir -p "$TMP/preflight-fake"
cat >"$TMP/preflight-fake/apt-get" <<'EOF'
#!/usr/bin/env bash
printf 'apt-called\n' >>/tmp/preflight-boundary.log
exit 99
EOF
cat >"$TMP/preflight-fake/uname" <<'EOF'
#!/usr/bin/env bash
[ "${1:-}" = -m ] && printf 'aarch64\n' || /usr/bin/uname "$@"
EOF
chmod +x "$TMP/preflight-fake"/*

cat >"$TMP/run-preflight-test.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
mode=$1
: >/tmp/preflight-boundary.log
case "$mode" in
  non-root) ;;
  wrong-os) printf 'ID=debian\nVERSION_ID="12"\n' >/etc/os-release;;
  wrong-arch) export PATH="/test/preflight-fake:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";;
  *) exit 2;;
esac
if /workspace/deploy/production/scripts/bootstrap-host.sh; then
  echo "FAIL: bootstrap accepted $mode" >&2
  exit 1
fi
[ ! -e /opt/happy-agent ] || { echo "FAIL: $mode bootstrap mutated /opt" >&2; exit 1; }
[ ! -s /tmp/preflight-boundary.log ] || { echo "FAIL: $mode bootstrap reached apt" >&2; exit 1; }
echo "PASS: bootstrap rejects $mode before mutation"
EOF
chmod +x "$TMP/run-preflight-test.sh"

docker run --rm --name "$CONTAINER-non-root" --user 65534:65534 \
  -v "$WORKTREE_ROOT:/workspace:ro" -v "$TMP:/test" \
  "$UBUNTU_IMAGE" /test/run-preflight-test.sh non-root
docker run --rm --name "$CONTAINER-wrong-os" \
  -v "$WORKTREE_ROOT:/workspace:ro" -v "$TMP:/test" \
  "$UBUNTU_IMAGE" /test/run-preflight-test.sh wrong-os
docker run --rm --name "$CONTAINER-wrong-arch" \
  -v "$WORKTREE_ROOT:/workspace:ro" -v "$TMP:/test" \
  "$UBUNTU_IMAGE" /test/run-preflight-test.sh wrong-arch

docker run --rm --name "$CONTAINER-lock" \
  -v "$WORKTREE_ROOT:/workspace:ro" \
  -v "$TMP:/test" \
  "$UBUNTU_IMAGE" /test/run-lock-test.sh

bash "$ROOT_DIR/tests/server-script-safety.test.sh" release

cat >"$TMP/run-bootstrap-test.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p /test/fake /test/fake-state
export PATH="/test/fake:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export FAKE_LOG=/test/bootstrap-boundaries.log

cat >/test/fake/apt-get <<'FAKE'
#!/usr/bin/env bash
printf 'apt-get %s\n' "$*" >>"$FAKE_LOG"
FAKE
cat >/test/fake/ss <<'FAKE'
#!/usr/bin/env bash
exit 0
FAKE
cat >/test/fake/curl <<'FAKE'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >>"$FAKE_LOG"
printf 'docker-key-fixture'
FAKE
cat >/test/fake/gpg <<'FAKE'
#!/usr/bin/env bash
cat
FAKE
cat >/test/fake/file <<'FAKE'
#!/usr/bin/env bash
printf 'Linux swap file\n'
FAKE
cat >/test/fake/mkswap <<'FAKE'
#!/usr/bin/env bash
printf 'mkswap %s\n' "$*" >>"$FAKE_LOG"
FAKE
cat >/test/fake/fallocate <<'FAKE'
#!/usr/bin/env bash
printf 'fallocate %s\n' "$*" >>"$FAKE_LOG"
[ "${1:-}" = -l ] && [ "${2:-}" = 2G ] && [ -n "${3:-}" ] || exit 2
truncate -s 2G "$3"
FAKE
cat >/test/fake/swapon <<'FAKE'
#!/usr/bin/env bash
if [ "${1:-}" = --show=NAME ]; then
  [ ! -f /test/fake-state/swapon ] || cat /test/fake-state/swapon
else
  printf 'swapon %s\n' "$*" >>"$FAKE_LOG"
  printf '%s\n' "$1" >/test/fake-state/swapon
fi
FAKE
cat >/test/fake/systemctl <<'FAKE'
#!/usr/bin/env bash
printf 'systemctl %s\n' "$*" >>"$FAKE_LOG"
FAKE
cat >/test/fake/openssl <<'FAKE'
#!/usr/bin/env bash
if [ "${1:-}" = rand ]; then
  count=0
  [ ! -f /test/fake-state/openssl-count ] || count=$(cat /test/fake-state/openssl-count)
  count=$((count + 1))
  printf '%s\n' "$count" >/test/fake-state/openssl-count
  printf '%064x\n' "$count"
else
  /usr/bin/openssl "$@"
fi
FAKE
chmod +x /test/fake/*

: >/etc/fstab
: >"$FAKE_LOG"
/workspace/deploy/production/scripts/bootstrap-host.sh
password_hash=$(sha256sum /opt/happy-agent/secrets/postgres-password | awk '{print $1}')
/workspace/deploy/production/scripts/bootstrap-host.sh

[ -d /opt/happy-agent/state/generations/initial-empty/postgres ] || { echo 'FAIL: bootstrap omitted initial PostgreSQL generation' >&2; exit 1; }
[ -d /opt/happy-agent/state/generations/initial-empty/media ] || { echo 'FAIL: bootstrap omitted initial media generation' >&2; exit 1; }
[ -L /opt/happy-agent/state/current ] || { echo 'FAIL: bootstrap omitted state/current' >&2; exit 1; }
[ "$(readlink /opt/happy-agent/state/current)" = generations/initial-empty ] || { echo 'FAIL: bootstrap selected an unexpected initial generation' >&2; exit 1; }
[ ! -e /opt/happy-agent/state/current/agent-master-key ] || { echo 'FAIL: bootstrap invented an Agent master key' >&2; exit 1; }
[ ! -e /opt/happy-agent/secrets/agent-master-key ] || { echo 'FAIL: bootstrap wrote the legacy Agent master key path' >&2; exit 1; }
[ "$(stat -c %a /opt/happy-agent/secrets)" = 700 ] || { echo 'FAIL: bootstrap Secret directory mode is not 0700' >&2; exit 1; }
for secret in postgres-password fitness-db-password agent-db-password; do
  [ -s "/opt/happy-agent/secrets/$secret" ] || { echo "FAIL: bootstrap generated empty $secret" >&2; exit 1; }
  [ "$(stat -c %a "/opt/happy-agent/secrets/$secret")" = 600 ] || { echo "FAIL: bootstrap mode for $secret is not 0600" >&2; exit 1; }
  [ "$(stat -c %u:%g "/opt/happy-agent/secrets/$secret")" = 70:70 ] || { echo "FAIL: bootstrap owner for $secret is not the pinned PostgreSQL runtime" >&2; exit 1; }
done
[ "$(sha256sum /opt/happy-agent/secrets/postgres-password | awk '{print $1}')" = "$password_hash" ] || { echo 'FAIL: bootstrap overwrote an existing password' >&2; exit 1; }
[ "$(stat -c %a /swapfile)" = 600 ] || { echo 'FAIL: bootstrap swap mode is not 0600' >&2; exit 1; }
[ "$(grep -Fc '/swapfile none swap sw 0 0' /etc/fstab)" = 1 ] || { echo 'FAIL: bootstrap fstab entry is not idempotent' >&2; exit 1; }
[ "$(grep -Fc 'mkswap /swapfile' "$FAKE_LOG")" = 1 ] || { echo 'FAIL: bootstrap created swap more than once' >&2; exit 1; }
[ "$(grep -Fc 'fallocate -l 2G /swapfile' "$FAKE_LOG")" = 1 ] || { echo 'FAIL: bootstrap did not allocate a non-sparse swapfile exactly once' >&2; exit 1; }
grep -Fq 'download.docker.com/linux/ubuntu/gpg' "$FAKE_LOG" || { echo 'FAIL: bootstrap omitted Docker official repository' >&2; exit 1; }
if grep -Fq 'enable --now happy-agent-cert-renew.timer' "$FAKE_LOG"; then echo 'FAIL: bootstrap enabled timer without a production certificate' >&2; exit 1; fi
[ -z "${HAPPY_AGENT_OS_RELEASE_PATH+x}${HAPPY_AGENT_FSTAB_PATH+x}${HAPPY_AGENT_SWAPFILE+x}${HAPPY_AGENT_SYSTEMD_UNIT_DIR+x}${HAPPY_AGENT_APT_KEYRING_DIR+x}${HAPPY_AGENT_APT_SOURCES_DIR+x}${HAPPY_AGENT_TEST_SYSTEM_ROOT+x}" ] || { echo 'FAIL: bootstrap sandbox relied on test-only path overrides' >&2; exit 1; }
echo 'PASS: fixed-path bootstrap in disposable Ubuntu 22.04 sandbox'
EOF
chmod +x "$TMP/run-bootstrap-test.sh"

docker run --rm --name "$CONTAINER-bootstrap" \
  -v "$WORKTREE_ROOT:/workspace:ro" \
  -v "$TMP:/test" \
  "$UBUNTU_IMAGE" /test/run-bootstrap-test.sh

[ ! -e "$WORKTREE_ROOT/secrets" ] || fail 'runtime test wrote secrets at the worktree root'
echo 'PASS: server script runtime safety'
