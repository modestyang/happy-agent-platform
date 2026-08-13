#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

PRODUCTION_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORKTREE_ROOT=$(cd "$PRODUCTION_ROOT/../.." && pwd)
TMP_PARENT=${TMPDIR:-/tmp}
TMP=$(mktemp -d "${TMP_PARENT%/}/happy-agent-task5-local.XXXXXX")
case "$TMP" in
  "${TMP_PARENT%/}"/happy-agent-task5-local.*) ;;
  *) echo 'unsafe temporary directory' >&2; exit 1;;
esac
cleanup() {
  local status=$?
  case "$TMP" in
    "${TMP_PARENT%/}"/happy-agent-task5-local.*) /bin/rm -rf -- "$TMP";;
  esac
  return "$status"
}
trap cleanup EXIT
CASE=${1:-all}

fail() { echo "FAIL: $*" >&2; exit 1; }
expect_fail() { if "$@"; then fail "expected failure: $*"; fi; }
assert_exit_2() {
  local status
  set +e
  "$@" >/dev/null 2>&1
  status=$?
  set -e
  [ "$status" = 2 ] || fail "expected exit 2, got $status: $*"
}
assert_contains() { grep -F -- "$2" "$1" >/dev/null || fail "missing $2 in $1"; }
assert_not_contains() { ! grep -F -- "$2" "$1" >/dev/null || fail "unexpected $2 in $1"; }
assert_mode() {
  local mode
  mode=$(stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1")
  [ "$mode" = "$2" ] || fail "mode for $1 is $mode, expected $2"
}
assert_before() {
  local first second
  first=$(grep -nF -- "$2" "$1" | head -n1 | cut -d: -f1)
  second=$(grep -nF -- "$3" "$1" | head -n1 | cut -d: -f1)
  [ -n "$first" ] && [ -n "$second" ] && [ "$first" -lt "$second" ] \
    || fail "expected '$2' before '$3' in $1"
}
verify_closed_manifest() {
  local directory=$1 expected actual
  [ -f "$directory/SHA256SUMS" ] && [ ! -L "$directory/SHA256SUMS" ] \
    || fail "missing regular manifest in $directory"
  expected=$(sed -E 's/^[a-f0-9]{64} [ *]//' "$directory/SHA256SUMS" | LC_ALL=C sort)
  actual=$(cd "$directory" && find . -type f ! -name SHA256SUMS -print \
    | sed 's#^./##' | LC_ALL=C sort)
  [ "$expected" = "$actual" ] || fail "manifest is not closed in $directory"
  (cd "$directory" && sha256sum --check --strict SHA256SUMS >/dev/null) \
    || fail "manifest checksum failed in $directory"
  [ -z "$(find "$directory" -mindepth 1 ! -type f ! -type d -print -quit)" ] \
    || fail "special member in $directory"
}

case "$CASE" in
  build) required_products='scripts/build-release.sh';;
  export) required_products='scripts/export-initial-data.sh';;
  cloud) required_products='scripts/cloud-guardrails.sh';;
  deploy) required_products='deploy.sh scripts/build-release.sh scripts/export-initial-data.sh scripts/cloud-guardrails.sh';;
  all) required_products='deploy.sh scripts/build-release.sh scripts/export-initial-data.sh scripts/cloud-guardrails.sh';;
  *) fail "unknown test case: $CASE";;
esac
for product in $required_products; do
  [ -x "$PRODUCTION_ROOT/$product" ] || fail "missing product script: $product"
done

FIXTURE_REPO="$TMP/build-repository"
SOURCE_ROOT="$TMP/source-state"
FAKE="$TMP/fake-bin"
FAKE_STATE="$TMP/fake-state"
BOUNDARY_LOG="$TMP/boundary.log"
mkdir -p "$FIXTURE_REPO/deploy" "$FIXTURE_REPO/agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent" \
  "$FIXTURE_REPO/starter/target" "$FIXTURE_REPO/frontend/dist" "$FAKE" "$FAKE_STATE"
cp -R "$PRODUCTION_ROOT" "$FIXTURE_REPO/deploy/production"
cp -R "$WORKTREE_ROOT/deploy/postgres" "$FIXTURE_REPO/deploy/postgres"
cp "$WORKTREE_ROOT/agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql" \
  "$FIXTURE_REPO/agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql"
mkdir -p "$FIXTURE_REPO/.git"
printf 'jar-fixture\n' >"$FIXTURE_REPO/starter/target/starter-0.0.1-SNAPSHOT-exec.jar"
printf '<html>fixture</html>\n' >"$FIXTURE_REPO/frontend/dist/index.html"
printf 'tracked\n' >"$FIXTURE_REPO/tracked.txt"
printf 'untracked\n' >"$FIXTURE_REPO/untracked-source.txt"
: >"$BOUNDARY_LOG"
export FAKE_STATE BOUNDARY_LOG

cat >"$FIXTURE_REPO/mvnw" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'mvnw %s\n' "$*" >>"$BOUNDARY_LOG"
case " $* " in
  ' --version ') printf 'Apache Maven 3.9.9 fixture\n';;
  *' package '*) mkdir -p starter/target; printf 'jar-fixture\n' >starter/target/starter-0.0.1-SNAPSHOT-exec.jar;;
esac
EOF
chmod +x "$FIXTURE_REPO/mvnw"

cat >"$FAKE/sha256sum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ "${1:-}" = --check ]; then
  shift
  [ "${1:-}" != --strict ] || shift
  while IFS=' ' read -r expected file || [ -n "${expected:-}${file:-}" ]; do
    file=${file#\*}
    actual=$(/usr/bin/shasum -a 256 "$file" | awk '{print $1}')
    [ "$actual" = "$expected" ] || exit 1
  done <"$1"
  exit 0
fi
if [ "$#" = 0 ]; then /usr/bin/shasum -a 256; exit; fi
for file in "$@"; do /usr/bin/shasum -a 256 "$file"; done
EOF

cat >"$FAKE/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'git %s\n' "$*" >>"$BOUNDARY_LOG"
git_root=$FAKE_GIT_ROOT
if [ "${1:-}" = -C ]; then git_root=$2; shift 2; fi
case " $* " in
  ' rev-parse --show-toplevel ') printf '%s\n' "$git_root";;
  ' rev-parse --short HEAD ') printf 'abc1234\n';;
  ' rev-parse HEAD ') printf 'abcdef0123456789abcdef0123456789abcdef01\n';;
  ' status --porcelain=v1 --untracked-files=all ') printf ' M tracked.txt\n?? untracked-source.txt\n';;
  ' diff --binary HEAD ') printf 'diff --git a/tracked.txt b/tracked.txt\n+dirty fixture\n';;
  *) exit 64;;
esac
EOF

cat >"$FAKE/npm" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'npm %s\n' "$*" >>"$BOUNDARY_LOG"
case " $* " in
  ' --version ') printf '10.8.2\n';;
  *' run build '*) mkdir -p frontend/dist; printf '<html>built</html>\n' >frontend/dist/index.html;;
esac
EOF

cat >"$FAKE/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >>"$BOUNDARY_LOG"
case " $* " in
  ' --version ') printf 'Docker version 27.1.1, build fixture\n'; exit;;
  ' compose version ') printf 'Docker Compose version v2.29.1\n'; exit;;
esac
if [ "${1:-}" = buildx ] && [ "${2:-}" = build ]; then
  tag=''; dockerfile=''; args=("$@")
  for ((i=0; i<${#args[@]}; i++)); do
    case "${args[$i]}" in
      -t|--tag) ((i+=1)); tag=${args[$i]};;
      -f|--file) ((i+=1)); dockerfile=${args[$i]};;
    esac
  done
  [ -n "$tag" ] && [ -f "$dockerfile" ] || exit 65
  if [ -n "${FAKE_DOCKER_FAIL_TAG:-}" ] && [ "$tag" = "$FAKE_DOCKER_FAIL_TAG" ]; then exit 66; fi
  case "$tag" in
    happy-agent-postgres:*) grep -F 'postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777' "$dockerfile" >/dev/null || exit 67;;
  esac
  printf '%s\n' "$tag" >>"$FAKE_STATE/built-images"
  exit
fi
if [ "${1:-}" = image ] && [ "${2:-}" = inspect ]; then
  format=${4:-}; image=${5:-}
  case "$format" in
    *RepoDigests*) printf '["%s@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]\n' "${image%%:*}";;
    *Id*) printf 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n';;
    *) exit 68;;
  esac
  exit
fi
if [ "${1:-}" = image ] && [ "${2:-}" = save ]; then
  [ "${3:-}" = -o ] || exit 69
  printf 'archive:%s\n' "$5" >"$4"
  exit
fi
case " $* " in
  *' compose '*'-f '*' ps -q postgres '*) printf 'postgres-fixture\n';;
  *' compose '*'-f '*' exec -T postgres psql '*'happy_agent_operation=happy-agent-smoke-validation'*) printf '1\n';;
  *' compose '*'-f '*' exec -T postgres psql '*'happy_agent_operation=happy-agent-source-validation'*)
    printf '%s\n' \
      'postgres_server_version=16.14' \
      'fitness_history_count=3' \
      'agent_history_count=2' \
      'application_table_count=17' \
      'key_object_count=5' \
      'fitness_schema_count=1' \
      'agent_schema_count=1' \
      'fitness_table_count=10' \
      'agent_table_count=7' \
      'fitness_history_checksums=111,222,333' \
      'agent_history_checksums=444,555' \
      'fitness_user_count=7' \
      'agent_run_count=9'
    ;;
  *' compose '*'-f '*' exec -T postgres psql '*'SHOW server_version'*) printf '16.14\n';;
  *' compose '*'-f '*' exec -T postgres pg_dump --version '*) printf 'pg_dump (PostgreSQL) 16.14\n';;
  *' compose '*'-f '*' exec -T postgres psql '*'CHECKPOINT'*) :;;
  *' compose '*'-f '*' exec -T postgres pg_dump '*)
    [ "${FAKE_PG_DUMP_FAIL:-0}" != 1 ] || exit 70
    printf 'custom-format-dump-fixture\n'
    ;;
  *) exit 71;;
esac
EOF

cat >"$FAKE/lsof" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'lsof %s\n' "$*" >>"$BOUNDARY_LOG"
case " $* " in
  *' -iTCP:8080 '*'-t '*) [ ! -e "$FAKE_STATE/listener-active" ] || printf '4242\n';;
  *' -p 4242 '*'-d cwd '*)
    if [ "${FAKE_LISTENER_CWD_FOREIGN:-0}" = 1 ]; then
      printf 'p4242\nfcwd\nn%s\n' "$FAKE_STATE/foreign-cwd"
    else
      printf 'p4242\nfcwd\nn%s\n' "$(cat "$FAKE_STATE/listener-cwd")"
    fi
    ;;
  *) exit 1;;
esac
EOF

cat >"$FAKE/ps" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'ps %s\n' "$*" >>"$BOUNDARY_LOG"
if [ "${FAKE_FOREIGN_LISTENER:-0}" = 1 ]; then
  printf '/usr/bin/python3 foreign-server.py\n'
else
  printf '/usr/bin/java -jar %s/starter/target/starter-0.0.1-SNAPSHOT-exec.jar\n' "$SOURCE_STATE_ROOT"
fi
EOF

cat >"$FAKE/kill" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'kill %s\n' "$*" >>"$BOUNDARY_LOG"
[ "${1:-}" = -TERM ] && [ "${2:-}" = 4242 ] || exit 72
/bin/rm -f -- "$FAKE_STATE/listener-active"
EOF

cat >"$FAKE/sleep" <<'EOF'
#!/usr/bin/env bash
printf 'sleep %s\n' "$*" >>"$BOUNDARY_LOG"
EOF

cat >"$FAKE/aliyun" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aliyun %s\n' "$*" >>"$BOUNDARY_LOG"
action=${2:-}
case "$action" in
  DescribeInstances)
    ip=$(cat "$FAKE_STATE/aliyun-ip")
    deletion=$(cat "$FAKE_STATE/aliyun-deletion")
    group_json=''
    while IFS= read -r group; do
      [ -z "$group_json" ] || group_json="$group_json,"
      group_json="$group_json\"$group\""
    done <"$FAKE_STATE/aliyun-groups"
    printf '{"Instances":{"Instance":[{"InstanceId":"i-0jlfb8o4hqpjekoudg4x","RegionId":"cn-wulanchabu","PublicIpAddress":{"IpAddress":["%s"]},"SecurityGroupIds":{"SecurityGroupId":[%s]},"DeletionProtection":%s}]}}\n' "$ip" "$group_json" "$deletion"
    ;;
  DescribeSecurityGroupAttribute)
    group=''
    args=("$@")
    for ((i=0; i<${#args[@]}; i++)); do
      [ "${args[$i]}" != --SecurityGroupId ] || { ((i+=1)); group=${args[$i]}; }
    done
    ports_file="$FAKE_STATE/aliyun-ports"
    [ "$group" != sg-extra-public ] || ports_file="$FAKE_STATE/aliyun-extra-ports"
    first=1
    printf '{"SecurityGroupId":"%s","Permissions":{"Permission":[' "$group"
    while IFS= read -r port; do
      [ -n "$port" ] || continue
      [ "$first" = 1 ] || printf ','
      first=0
      printf '{"IpProtocol":"tcp","PortRange":"%s/%s","SourceCidrIp":"0.0.0.0/0","Policy":"Accept"}' "$port" "$port"
    done <"$ports_file"
    [ "$first" = 1 ] || printf ','
    printf '%s' '{"IpProtocol":"icmp","PortRange":"-1/-1","SourceCidrIp":"0.0.0.0/0","Policy":"Accept"},{"IpProtocol":"tcp","PortRange":"5432/5432","SourceCidrIp":"10.0.0.0/8","Policy":"Accept"}]}}'
    printf '\n'
    ;;
  AuthorizeSecurityGroup|RevokeSecurityGroup)
    if [ "${FAKE_ALIYUN_FAIL_ACTION:-}" = "$action" ]; then exit 73; fi
    port=''
    args=("$@")
    for ((i=0; i<${#args[@]}; i++)); do
      [ "${args[$i]}" != --PortRange ] || { ((i+=1)); port=${args[$i]%%/*}; }
    done
    if [ "$action" = AuthorizeSecurityGroup ]; then
      grep -Fxq "$port" "$FAKE_STATE/aliyun-ports" || printf '%s\n' "$port" >>"$FAKE_STATE/aliyun-ports"
    else
      awk -v port="$port" '$0 != port' "$FAKE_STATE/aliyun-ports" >"$FAKE_STATE/aliyun-ports.next"
      /bin/mv "$FAKE_STATE/aliyun-ports.next" "$FAKE_STATE/aliyun-ports"
    fi
    printf '{}\n'
    ;;
  ModifyInstanceAttribute)
    if [ "${FAKE_ALIYUN_FAIL_ACTION:-}" = "$action" ]; then exit 74; fi
    printf 'true\n' >"$FAKE_STATE/aliyun-deletion"
    printf '{}\n'
    ;;
  *) exit 75;;
esac
EOF

cat >"$FAKE/ssh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'ssh %s\n' "$*" >>"$BOUNDARY_LOG"
/bin/cat >/dev/null || :
case " $* " in
  *' latest-backup '*)
    count=0
    [ ! -f "$FAKE_STATE/backup-counter" ] || count=$(cat "$FAKE_STATE/backup-counter")
    count=$((count + 1))
    printf '%s\n' "$count" >"$FAKE_STATE/backup-counter"
    printf '/opt/happy-agent/backups/20260813T13%04dZ\n' "$count"
    ;;
  *' migration-marker-status '*)
    if [ -e "$FAKE_STATE/remote-migration-marker" ]; then
      printf 'complete\n'
    elif [ -e "$FAKE_STATE/remote-migration-state-unsafe" ]; then
      printf 'unsafe\n'
    else
      printf 'ready\n'
    fi
    ;;
  *' write-migration-marker '*) : >"$FAKE_STATE/remote-migration-marker";;
esac
EOF

cat >"$FAKE/scp" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'scp %s\n' "$*" >>"$BOUNDARY_LOG"
args=("$@")
source_arg=${args[${#args[@]}-2]}
destination=${args[${#args[@]}-1]}
case "$source_arg" in
  root@39.101.65.254:/opt/happy-agent/backups/*)
    mkdir -p "$destination"
    printf 'receipt-fixture\n' >"$destination/receipt"
    (cd "$destination" && sha256sum receipt >SHA256SUMS)
    chmod 0600 "$destination/receipt" "$destination/SHA256SUMS"
    ;;
esac
EOF

chmod +x "$FAKE"/*
ORIGINAL_PATH=$PATH
export PATH="$FAKE:$ORIGINAL_PATH"
export FAKE FAKE_GIT_ROOT="$FIXTURE_REPO"
kill() { "$FAKE/kill" "$@"; }
export -f kill

setup_source_state() {
  /bin/rm -rf -- "$SOURCE_ROOT"
  mkdir -p "$SOURCE_ROOT/deploy/.local/media/nested" "$SOURCE_ROOT/deploy/secrets" "$SOURCE_ROOT/.git"
  cp "$WORKTREE_ROOT/deploy/docker-compose.yml" "$SOURCE_ROOT/deploy/docker-compose.yml"
  printf 'media-a\n' >"$SOURCE_ROOT/deploy/.local/media/a.txt"
  printf 'media-b\n' >"$SOURCE_ROOT/deploy/.local/media/nested/b.txt"
  printf 'master-key-fixture\000bytes' >"$SOURCE_ROOT/deploy/secrets/agent-master-key"
  printf '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n' \
    >"$SOURCE_ROOT/deploy/.local/production-public-smoke-session.tmp"
  mkdir -p "$SOURCE_ROOT/deploy/.local/production"
  /bin/mv "$SOURCE_ROOT/deploy/.local/production-public-smoke-session.tmp" \
    "$SOURCE_ROOT/deploy/.local/production/public-smoke-session"
  printf '11111111-1111-4111-8111-111111111111\n' \
    >"$SOURCE_ROOT/deploy/.local/production/public-smoke-run-id"
  chmod 0600 "$SOURCE_ROOT/deploy/secrets/agent-master-key" \
    "$SOURCE_ROOT/deploy/.local/production/public-smoke-session" \
    "$SOURCE_ROOT/deploy/.local/production/public-smoke-run-id"
  printf '%s\n' "$SOURCE_ROOT" >"$FAKE_STATE/listener-cwd"
}

reset_aliyun_state() {
  printf '39.101.65.254\n' >"$FAKE_STATE/aliyun-ip"
  printf 'false\n' >"$FAKE_STATE/aliyun-deletion"
  printf 'sg-0jlb5v2njkb2jbzrvurr\n' >"$FAKE_STATE/aliyun-groups"
  printf '22\n3389\n' >"$FAKE_STATE/aliyun-ports"
  : >"$FAKE_STATE/aliyun-extra-ports"
  /bin/rm -f -- "$FAKE_STATE/remote-migration-marker" "$FAKE_STATE/remote-migration-state-unsafe"
}

run_build_tests() {
  local release failed_release first_build line
  : >"$BOUNDARY_LOG"
  (
    cd "$FIXTURE_REPO"
    HAPPY_AGENT_BUILD_TIMESTAMP=20260813T120000Z \
      deploy/production/scripts/build-release.sh
  )
  release="$FIXTURE_REPO/deploy/.local/production/releases/20260813T120000Z-abc1234"
  [ -d "$release" ] || fail 'release was not atomically published'
  assert_mode "$release" 700
  verify_closed_manifest "$release"
  for file in "$release/images/app.tar" "$release/images/web.tar" "$release/images/postgres.tar" \
    "$release/.env" "$release/build-metadata.env" "$release/build-metadata.json"; do
    [ -f "$file" ] || fail "missing release file: ${file#$release/}"
    assert_mode "$file" 600
  done
  assert_contains "$release/.env" 'RELEASE_ID=20260813T120000Z-abc1234'
  assert_contains "$release/.env" 'APP_IMAGE=happy-agent-app:20260813T120000Z-abc1234'
  assert_contains "$release/.env" 'WEB_IMAGE=happy-agent-web:20260813T120000Z-abc1234'
  assert_contains "$release/build-metadata.env" 'source_dirty=true'
  assert_contains "$release/build-metadata.env" 'target_platform=linux/amd64'
  assert_contains "$release/build-metadata.env" 'app_image_id=sha256:bbbb'
  node -e 'const fs=require("fs"); const m=JSON.parse(fs.readFileSync(process.argv[1],"utf8")); if(m.releaseId!=="20260813T120000Z-abc1234" || m.targetPlatform!=="linux/amd64" || !m.files["compose.yml"]) process.exit(1)' \
    "$release/build-metadata.json" || fail 'invalid structured build metadata'
  assert_before "$BOUNDARY_LOG" 'mvnw test' 'mvnw spotless:check'
  assert_before "$BOUNDARY_LOG" 'mvnw spotless:check' 'npm --prefix frontend test'
  assert_before "$BOUNDARY_LOG" 'npm --prefix frontend test' 'npm --prefix frontend run typecheck'
  assert_before "$BOUNDARY_LOG" 'npm --prefix frontend run typecheck' 'npm --prefix frontend run build'
  assert_before "$BOUNDARY_LOG" 'npm --prefix frontend run build' 'mvnw -DskipTests -pl starter -am package'
  first_build=$(grep -nF 'docker buildx build' "$BOUNDARY_LOG" | head -n1 | cut -d: -f1)
  line=$(grep -nF 'mvnw -DskipTests -pl starter -am package' "$BOUNDARY_LOG" | head -n1 | cut -d: -f1)
  [ "$line" -lt "$first_build" ] || fail 'image build started before required checks/builds'
  [ "$(grep -Fc 'docker buildx build --platform linux/amd64' "$BOUNDARY_LOG")" = 3 ] \
    || fail 'expected three linux/amd64 image builds'
  [ "$(grep -Fc 'docker image save -o' "$BOUNDARY_LOG")" = 3 ] \
    || fail 'expected three image archives'
  assert_not_contains "$BOUNDARY_LOG" '--load .'
  assert_not_contains "$release/SHA256SUMS" 'public-smoke-session'
  assert_not_contains "$release/SHA256SUMS" 'agent-master-key'
  [ -z "$(find "$release" \( -type l -o ! -type f ! -type d \) -print -quit)" ] \
    || fail 'release contains a link or special member'

  : >"$BOUNDARY_LOG"
  failed_release="$FIXTURE_REPO/deploy/.local/production/releases/20260813T120100Z-abc1234"
  expect_fail bash -c 'cd "$1" && HAPPY_AGENT_BUILD_TIMESTAMP=20260813T120100Z FAKE_DOCKER_FAIL_TAG=happy-agent-web:20260813T120100Z-abc1234 deploy/production/scripts/build-release.sh' _ "$FIXTURE_REPO"
  [ ! -e "$failed_release" ] || fail 'failed build published a complete release'
  [ ! -e "$FIXTURE_REPO/deploy/.local/production/releases/.pending-20260813T120100Z-abc1234" ] \
    || fail 'failed build leaked its pending release'
  echo 'PASS: local release build transaction'
}

run_export_tests() {
  local bundle output
  setup_source_state
  : >"$BOUNDARY_LOG"
  : >"$FAKE_STATE/listener-active"
  mkdir -p "$FAKE_STATE/foreign-cwd"
  output="$TMP/export.output"
  if ! (
    cd "$FIXTURE_REPO"
    SOURCE_STATE_ROOT="$SOURCE_ROOT" FAKE_LISTENER_CWD_FOREIGN=1 \
      HAPPY_AGENT_EXPORT_TIMESTAMP=20260813T121000Z \
      deploy/production/scripts/export-initial-data.sh
  ) >"$output" 2>&1; then
    sed -n '1,240p' "$output" >&2
    fail 'happy-path export failed'
  fi
  bundle="$FIXTURE_REPO/deploy/.local/production/migrations/initial-20260813T121000Z-abc1234"
  [ -d "$bundle" ] || fail 'migration bundle was not atomically published'
  assert_mode "$bundle" 700
  verify_closed_manifest "$bundle"
  expected='SHA256SUMS
agent-master-key
initial.dump
media.tar
metadata.env
source-validation.json'
  actual=$(cd "$bundle" && find . -type f -print | sed 's#^./##' | LC_ALL=C sort)
  [ "$actual" = "$expected" ] || fail 'migration bundle has an unexpected member set'
  for file in "$bundle"/*; do assert_mode "$file" 600; done
  cmp "$SOURCE_ROOT/deploy/secrets/agent-master-key" "$bundle/agent-master-key" >/dev/null \
    || fail 'migration bundle changed master-key bytes'
  assert_contains "$bundle/metadata.env" 'fitness_history_count=3'
  assert_contains "$bundle/metadata.env" 'agent_history_count=2'
  assert_contains "$bundle/metadata.env" 'application_table_count=17'
  assert_contains "$bundle/metadata.env" 'key_object_count=5'
  node -e 'const fs=require("fs"); const m=JSON.parse(fs.readFileSync(process.argv[1],"utf8")); if(m.postgres.serverVersion!=="16.14" || m.postgres.dumpVersion!=="16.14" || m.criticalCounts.fitnessUserCount!==7) process.exit(1)' \
    "$bundle/source-validation.json" || fail 'invalid source-validation JSON'
  assert_before "$BOUNDARY_LOG" 'happy-agent-smoke-validation' 'kill -TERM 4242'
  assert_before "$BOUNDARY_LOG" 'CHECKPOINT' 'pg_dump --format=custom --dbname=happy_agent'
  assert_not_contains "$BOUNDARY_LOG" '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
  assert_not_contains "$BOUNDARY_LOG" 'master-key-fixture'
  assert_not_contains "$bundle/SHA256SUMS" 'public-smoke'

  setup_source_state
  : >"$BOUNDARY_LOG"
  : >"$FAKE_STATE/listener-active"
  expect_fail bash -c 'cd "$1" && SOURCE_STATE_ROOT="$2" FAKE_FOREIGN_LISTENER=1 HAPPY_AGENT_EXPORT_TIMESTAMP=20260813T121100Z deploy/production/scripts/export-initial-data.sh' _ "$FIXTURE_REPO" "$SOURCE_ROOT"
  assert_not_contains "$BOUNDARY_LOG" 'kill -TERM'
  assert_not_contains "$BOUNDARY_LOG" 'pg_dump --format=custom'

  setup_source_state
  : >"$BOUNDARY_LOG"
  expect_fail bash -c 'cd "$1" && SOURCE_STATE_ROOT="$2" FAKE_PG_DUMP_FAIL=1 HAPPY_AGENT_EXPORT_TIMESTAMP=20260813T121200Z deploy/production/scripts/export-initial-data.sh' _ "$FIXTURE_REPO" "$SOURCE_ROOT"
  [ ! -e "$FIXTURE_REPO/deploy/.local/production/migrations/initial-20260813T121200Z-abc1234" ] \
    || fail 'failed dump published a migration bundle'
  [ ! -e "$FIXTURE_REPO/deploy/.local/production/migrations/.pending-initial-20260813T121200Z-abc1234" ] \
    || fail 'failed dump leaked a pending migration bundle'
  echo 'PASS: initial data export transaction'
}

run_cloud_tests() {
  local first_mutations second_mutations
  reset_aliyun_state
  : >"$BOUNDARY_LOG"
  "$FIXTURE_REPO/deploy/production/scripts/cloud-guardrails.sh"
  grep -Fxq 22 "$FAKE_STATE/aliyun-ports" || fail 'guardrails removed SSH'
  grep -Fxq 80 "$FAKE_STATE/aliyun-ports" || fail 'guardrails omitted HTTP'
  grep -Fxq 443 "$FAKE_STATE/aliyun-ports" || fail 'guardrails omitted HTTPS'
  ! grep -Fxq 3389 "$FAKE_STATE/aliyun-ports" || fail 'guardrails retained public RDP'
  [ "$(cat "$FAKE_STATE/aliyun-deletion")" = true ] || fail 'deletion protection was not enabled'
  first_mutations=$(grep -Ec 'aliyun ecs (AuthorizeSecurityGroup|RevokeSecurityGroup|ModifyInstanceAttribute)' "$BOUNDARY_LOG")
  "$FIXTURE_REPO/deploy/production/scripts/cloud-guardrails.sh"
  second_mutations=$(grep -Ec 'aliyun ecs (AuthorizeSecurityGroup|RevokeSecurityGroup|ModifyInstanceAttribute)' "$BOUNDARY_LOG")
  [ "$first_mutations" = "$second_mutations" ] || fail 'idempotent guardrail run mutated cloud state'

  reset_aliyun_state
  printf '203.0.113.10\n' >"$FAKE_STATE/aliyun-ip"
  : >"$BOUNDARY_LOG"
  expect_fail "$FIXTURE_REPO/deploy/production/scripts/cloud-guardrails.sh"
  ! grep -Eq 'AuthorizeSecurityGroup|RevokeSecurityGroup|ModifyInstanceAttribute' "$BOUNDARY_LOG" \
    || fail 'target drift did not halt before mutation'

  reset_aliyun_state
  : >"$BOUNDARY_LOG"
  expect_fail env FAKE_ALIYUN_FAIL_ACTION=AuthorizeSecurityGroup \
    "$FIXTURE_REPO/deploy/production/scripts/cloud-guardrails.sh"
  [ "$(grep -Ec 'aliyun ecs (AuthorizeSecurityGroup|RevokeSecurityGroup|ModifyInstanceAttribute)' "$BOUNDARY_LOG")" = 1 ] \
    || fail 'failed guardrail action did not halt later calls'

  reset_aliyun_state
  printf 'sg-0jlb5v2njkb2jbzrvurr\nsg-extra-public\n' >"$FAKE_STATE/aliyun-groups"
  printf '5432\n8080\n' >"$FAKE_STATE/aliyun-extra-ports"
  : >"$BOUNDARY_LOG"
  expect_fail "$FIXTURE_REPO/deploy/production/scripts/cloud-guardrails.sh"
  ! grep -Eq 'AuthorizeSecurityGroup|RevokeSecurityGroup|ModifyInstanceAttribute' "$BOUNDARY_LOG" \
    || fail 'extra security group did not halt before mutation'
  echo 'PASS: cloud guardrails'
}

assert_transport_options() {
  local line
  while IFS= read -r line; do
    case "$line" in ssh\ *|scp\ *)
      case "$line" in *'BatchMode=yes'*'IdentitiesOnly=yes'*'StrictHostKeyChecking=yes'*'UserKnownHostsFile='*) ;;
        *) fail "unsafe transport options: $line";;
      esac
      case "$line" in *'-i '*'root@39.101.65.254'*) ;; *) fail "transport omitted identity or fixed target: $line";; esac
      ;;
    esac
  done <"$BOUNDARY_LOG"
}

run_deploy_tests() {
  local identity known_hosts marker release_line upload_line backup_line activate_line
  local backup_timestamp activation_timestamp
  local smoke_override session_override run_id_override
  setup_source_state
  reset_aliyun_state
  identity="$TMP/id_ed25519"
  known_hosts="$SOURCE_ROOT/deploy/.local/production/known_hosts"
  printf 'private-key-fixture\n' >"$identity"
  printf '39.101.65.254 ssh-ed25519 AAAAC3NzaFixture\n' >"$known_hosts"
  chmod 0600 "$identity" "$known_hosts"
  export SOURCE_STATE_ROOT="$SOURCE_ROOT" HAPPY_AGENT_SSH_IDENTITY="$identity"
  smoke_override="$TMP/smoke-overrides"
  session_override="$smoke_override/session.hex"
  run_id_override="$smoke_override/run.uuid"
  mkdir -m 0700 "$smoke_override"
  cp "$SOURCE_ROOT/deploy/.local/production/public-smoke-session" "$session_override"
  cp "$SOURCE_ROOT/deploy/.local/production/public-smoke-run-id" "$run_id_override"
  chmod 0600 "$session_override" "$run_id_override"

  assert_exit_2 "$FIXTURE_REPO/deploy/production/deploy.sh"
  assert_exit_2 "$FIXTURE_REPO/deploy/production/deploy.sh" unknown
  assert_exit_2 "$FIXTURE_REPO/deploy/production/deploy.sh" release extra
  assert_exit_2 "$FIXTURE_REPO/deploy/production/deploy.sh" rollback

  : >"$BOUNDARY_LOG"
  "$FIXTURE_REPO/deploy/production/deploy.sh" status
  assert_contains "$BOUNDARY_LOG" 'ssh '
  assert_not_contains "$BOUNDARY_LOG" 'scp '
  assert_transport_options

  : >"$BOUNDARY_LOG"
  HAPPY_AGENT_BUILD_TIMESTAMP=20260813T122000Z \
    "$FIXTURE_REPO/deploy/production/deploy.sh" release
  release_line=$(grep -nF 'mvnw test' "$BOUNDARY_LOG" | head -n1 | cut -d: -f1)
  upload_line=$(grep -nF 'scp ' "$BOUNDARY_LOG" | head -n1 | cut -d: -f1)
  backup_line=$(grep -nF '/scripts/backup.sh' "$BOUNDARY_LOG" | head -n1 | cut -d: -f1)
  activate_line=$(grep -nF '/scripts/activate-release.sh 20260813T122000Z-abc1234' "$BOUNDARY_LOG" | head -n1 | cut -d: -f1)
  [ "$release_line" -lt "$upload_line" ] && [ "$upload_line" -lt "$backup_line" ] \
    && [ "$backup_line" -lt "$activate_line" ] || fail 'release orchestration order is wrong'
  assert_contains "$BOUNDARY_LOG" '/.env '
  backup_timestamp=$(grep -F '/scripts/backup.sh' "$BOUNDARY_LOG" | head -n1 \
    | sed -E 's/.*HAPPY_AGENT_TIMESTAMP=([^ ]+).*/\1/')
  activation_timestamp=$(grep -F '/scripts/activate-release.sh 20260813T122000Z-abc1234' \
    "$BOUNDARY_LOG" | head -n1 | sed -E 's/.*HAPPY_AGENT_TIMESTAMP=([^ ]+).*/\1/')
  [[ "$backup_timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] \
    && [[ "$activation_timestamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] \
    && [ "$backup_timestamp" != "$activation_timestamp" ] \
    || fail 'release backup and activation did not use distinct safe timestamps'
  assert_contains "$BOUNDARY_LOG" 'latest-backup'
  assert_transport_options

  : >"$BOUNDARY_LOG"
  HAPPY_AGENT_BUILD_TIMESTAMP=20260813T123000Z HAPPY_AGENT_EXPORT_TIMESTAMP=20260813T123100Z \
    PUBLIC_SMOKE_SESSION_FILE="$session_override" PUBLIC_SMOKE_RUN_ID_FILE="$run_id_override" \
    "$FIXTURE_REPO/deploy/production/deploy.sh" migrate
  marker="$FIXTURE_REPO/deploy/.local/production/migrations/first-migration.marker"
  [ -f "$marker" ] && [ ! -L "$marker" ] || fail 'local migration marker missing'
  assert_mode "$marker" 600
  assert_contains "$BOUNDARY_LOG" 'public-smoke-session'
  assert_contains "$BOUNDARY_LOG" 'restore-initial-data.sh'
  assert_contains "$BOUNDARY_LOG" 'issue-certificate.sh'
  assert_contains "$BOUNDARY_LOG" 'activate-release.sh 20260813T123000Z-abc1234'
  assert_contains "$BOUNDARY_LOG" 'write-migration-marker'
  assert_contains "$BOUNDARY_LOG" "$session_override root@39.101.65.254:/opt/happy-agent/staging/.pending-smoke-20260813T123000Z-abc1234/public-smoke-session"
  assert_contains "$BOUNDARY_LOG" "$run_id_override root@39.101.65.254:/opt/happy-agent/staging/.pending-smoke-20260813T123000Z-abc1234/public-smoke-run-id"
  assert_transport_options
  : >"$BOUNDARY_LOG"
  expect_fail env HAPPY_AGENT_BUILD_TIMESTAMP=20260813T123200Z HAPPY_AGENT_EXPORT_TIMESTAMP=20260813T123300Z \
    "$FIXTURE_REPO/deploy/production/deploy.sh" migrate
  assert_not_contains "$BOUNDARY_LOG" 'pg_dump --format=custom'

  /bin/rm -f -- "$marker" "$FAKE_STATE/remote-migration-marker"
  : >"$FAKE_STATE/remote-migration-state-unsafe"
  : >"$BOUNDARY_LOG"
  expect_fail env HAPPY_AGENT_BUILD_TIMESTAMP=20260813T123400Z HAPPY_AGENT_EXPORT_TIMESTAMP=20260813T123500Z \
    "$FIXTURE_REPO/deploy/production/deploy.sh" migrate
  assert_not_contains "$BOUNDARY_LOG" 'mvnw test'
  assert_not_contains "$BOUNDARY_LOG" 'pg_dump --format=custom'

  : >"$BOUNDARY_LOG"
  "$FIXTURE_REPO/deploy/production/deploy.sh" rollback 20260813T122000Z-abc1234
  assert_contains "$BOUNDARY_LOG" '/scripts/rollback.sh 20260813T122000Z-abc1234'
  assert_not_contains "$BOUNDARY_LOG" 'scp '

  : >"$BOUNDARY_LOG"
  "$FIXTURE_REPO/deploy/production/deploy.sh" backup
  assert_contains "$BOUNDARY_LOG" '/scripts/backup.sh'
  assert_contains "$BOUNDARY_LOG" 'latest-backup'
  assert_transport_options

  : >"$BOUNDARY_LOG"
  "$FIXTURE_REPO/deploy/production/deploy.sh" bootstrap
  assert_before "$BOUNDARY_LOG" 'aliyun ecs DescribeInstances' 'scp '
  assert_contains "$BOUNDARY_LOG" 'bootstrap-host.sh'
  assert_transport_options
  echo 'PASS: deployment command orchestration'
}

case "$CASE" in
  build) run_build_tests;;
  export) run_export_tests;;
  cloud) run_cloud_tests;;
  deploy) run_deploy_tests;;
  all) run_build_tests; run_export_tests; run_cloud_tests; run_deploy_tests;;
  *) fail "unknown test case: $CASE";;
esac

[ ! -e "$WORKTREE_ROOT/secrets" ] || fail 'test wrote Secrets at the worktree root'
[ -z "$(find "${TMP_PARENT%/}" -maxdepth 1 -type d -name 'happy-agent-task5-local.*' ! -path "$TMP" -print -quit)" ] \
  || fail 'an earlier Task 5 temporary directory remains'
echo 'PASS: local orchestrator safety'
