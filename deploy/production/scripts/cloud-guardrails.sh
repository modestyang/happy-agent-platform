#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

readonly REGION=cn-wulanchabu
readonly INSTANCE_ID=i-0jlfb8o4hqpjekoudg4x
readonly SECURITY_GROUP_ID=sg-0jlb5v2njkb2jbzrvurr
readonly PUBLIC_IP=39.101.65.254
readonly ALIYUN_PROFILE=ecs-audit

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
command -v aliyun >/dev/null 2>&1 || die 'required command unavailable: aliyun'
command -v node >/dev/null 2>&1 || die 'required command unavailable: node'

describe_instance() {
  aliyun ecs DescribeInstances --profile "$ALIYUN_PROFILE" --RegionId "$REGION" \
    --InstanceIds "[\"$INSTANCE_ID\"]"
}

describe_security_group() {
  aliyun ecs DescribeSecurityGroupAttribute --profile "$ALIYUN_PROFILE" --RegionId "$REGION" \
    --SecurityGroupId "$SECURITY_GROUP_ID" --Direction ingress
}

parse_instance() {
  node -e '
const fs = require("fs");
const expectedId = process.argv[1];
const expectedRegion = process.argv[2];
const expectedIp = process.argv[3];
const expectedGroup = process.argv[4];
let input;
try { input = JSON.parse(fs.readFileSync(0, "utf8")); } catch { process.exit(2); }
const instances = input?.Instances?.Instance;
if (!Array.isArray(instances) || instances.length !== 1) process.exit(3);
const instance = instances[0];
const ips = instance?.PublicIpAddress?.IpAddress;
const groups = instance?.SecurityGroupIds?.SecurityGroupId;
if (instance.InstanceId !== expectedId || instance.RegionId !== expectedRegion ||
    !Array.isArray(ips) || ips.length !== 1 || ips[0] !== expectedIp ||
    !Array.isArray(groups) || groups.length !== 1 || groups[0] !== expectedGroup ||
    typeof instance.DeletionProtection !== "boolean") process.exit(4);
process.stdout.write(instance.DeletionProtection ? "true\n" : "false\n");
' "$INSTANCE_ID" "$REGION" "$PUBLIC_IP" "$SECURITY_GROUP_ID"
}

permission_records() {
  node -e '
const fs = require("fs");
const expectedGroup = process.argv[1];
let input;
try { input = JSON.parse(fs.readFileSync(0, "utf8")); } catch { process.exit(2); }
if (input.SecurityGroupId !== expectedGroup) process.exit(3);
const permissions = input?.Permissions?.Permission;
if (!Array.isArray(permissions)) process.exit(4);
for (const permission of permissions) {
  const protocol = String(permission.IpProtocol ?? "").toLowerCase();
  const range = String(permission.PortRange ?? "");
  const source = String(permission.SourceCidrIp ?? "");
  const policy = String(permission.Policy ?? "");
  if ([protocol, range, source, policy].some(value => value.includes("\t") || value.includes("\n"))) process.exit(5);
  process.stdout.write(`${protocol}\t${range}\t${source}\t${policy}\n`);
}
' "$SECURITY_GROUP_ID"
}

has_exact_public_rule() {
  local records=$1 port=$2
  printf '%s\n' "$records" \
    | awk -F '\t' -v range="$port/$port" \
        '$1 == "tcp" && $2 == range && $3 == "0.0.0.0/0" && $4 == "Accept" {found=1} END {exit !found}'
}

public_tcp_port_allowed() {
  local records=$1 port=$2
  printf '%s\n' "$records" \
    | awk -F '\t' -v port="$port" '
        $1 == "tcp" && $3 == "0.0.0.0/0" && $4 == "Accept" {
          split($2, range, "/")
          if (range[1] ~ /^[0-9]+$/ && range[2] ~ /^[0-9]+$/ \
              && range[1] <= port && port <= range[2]) found=1
        }
        END {exit !found}'
}

tcp_port_allowed() {
  local records=$1 port=$2
  printf '%s\n' "$records" \
    | awk -F '\t' -v port="$port" '
        $1 == "tcp" && $4 == "Accept" {
          split($2, range, "/")
          if (range[1] ~ /^[0-9]+$/ && range[2] ~ /^[0-9]+$/ \
              && range[1] <= port && port <= range[2]) found=1
        }
        END {exit !found}'
}

instance_json=$(describe_instance)
security_group_json=$(describe_security_group)
deletion_protection=$(printf '%s\n' "$instance_json" | parse_instance) \
  || die 'instance target, region, public IP, or security group drift detected'
permissions=$(printf '%s\n' "$security_group_json" | permission_records) \
  || die 'security-group target or response shape drift detected'

for port in 5432 8080; do
  ! public_tcp_port_allowed "$permissions" "$port" \
    || die "unsafe public TCP port already allowed: $port"
done

for port in 80 443; do
  if ! has_exact_public_rule "$permissions" "$port"; then
    aliyun ecs AuthorizeSecurityGroup --profile "$ALIYUN_PROFILE" --RegionId "$REGION" \
      --SecurityGroupId "$SECURITY_GROUP_ID" --IpProtocol tcp --PortRange "$port/$port" \
      --SourceCidrIp 0.0.0.0/0 --Policy Accept >/dev/null
  fi
done
if has_exact_public_rule "$permissions" 3389; then
  aliyun ecs RevokeSecurityGroup --profile "$ALIYUN_PROFILE" --RegionId "$REGION" \
    --SecurityGroupId "$SECURITY_GROUP_ID" --IpProtocol tcp --PortRange 3389/3389 \
    --SourceCidrIp 0.0.0.0/0 --Policy Accept >/dev/null
fi
if [ "$deletion_protection" = false ]; then
  aliyun ecs ModifyInstanceAttribute --profile "$ALIYUN_PROFILE" --RegionId "$REGION" \
    --InstanceId "$INSTANCE_ID" --DeletionProtection true >/dev/null
fi

instance_json=$(describe_instance)
security_group_json=$(describe_security_group)
deletion_protection=$(printf '%s\n' "$instance_json" | parse_instance) \
  || die 'instance target drift detected after guardrail changes'
permissions=$(printf '%s\n' "$security_group_json" | permission_records) \
  || die 'security-group drift detected after guardrail changes'

[ "$deletion_protection" = true ] || die 'instance deletion protection is not enabled'
tcp_port_allowed "$permissions" 22 || die 'required TCP port is not allowed: 22'
for port in 80 443; do
  public_tcp_port_allowed "$permissions" "$port" \
    || die "required public TCP port is not allowed: $port"
done
for port in 3389 5432 8080; do
  ! public_tcp_port_allowed "$permissions" "$port" \
    || die "forbidden public TCP port is allowed: $port"
done
log 'cloud guardrails verified for the fixed ECS target'
