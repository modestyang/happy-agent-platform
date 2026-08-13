#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"

readonly CERTBOT_IMAGE='certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4'

issue_core() (
  set -euo pipefail
  local release webroot staging production challenge challenge_path response
  release=$(current_release)
  verify_manifest "$release" .env compose.yml nginx.conf
  service_matches_release "$release" nginx \
    || die 'HTTP Nginx must match the active release and be healthy before certificate issuance'
  webroot=$(validate_descendant "$HAPPY_AGENT_ROOT/data/acme-webroot")
  staging=$(validate_descendant "$HAPPY_AGENT_ROOT/certificates/staging")
  production=$(validate_descendant "$HAPPY_AGENT_ROOT/certificates/production")
  install -d -m 0755 "$webroot/.well-known/acme-challenge"
  install -d -m 0750 "$staging" "$production"
  challenge_path=$(mktemp "$webroot/.well-known/acme-challenge/.happy-agent-acme.XXXXXX")
  challenge_path=$(validate_descendant "$challenge_path")
  challenge=${challenge_path##*/}
  printf '%s' "$challenge" >"$challenge_path"
  chmod 0644 "$challenge_path"
  trap 'rm -f -- "$challenge_path"' EXIT
  response=$(curl --fail --silent --show-error \
    "http://39.101.65.254/.well-known/acme-challenge/$challenge") \
    || die 'ACME webroot HTTP proof failed'
  [ "$response" = "$challenge" ] || die 'ACME webroot served unexpected challenge'

  docker run --rm -v "$webroot:/var/www/acme" -v "$staging:/etc/letsencrypt" \
    "$CERTBOT_IMAGE" certonly --webroot -w /var/www/acme --staging \
    --ip-address 39.101.65.254 --preferred-profile shortlived \
    --cert-name happy-agent-ip-staging --email modest_yang@126.com \
    --agree-tos --non-interactive
  docker run --rm -v "$webroot:/var/www/acme" -v "$production:/etc/letsencrypt" \
    "$CERTBOT_IMAGE" certonly --webroot -w /var/www/acme \
    --ip-address 39.101.65.254 --preferred-profile shortlived \
    --cert-name happy-agent-ip --email modest_yang@126.com \
    --agree-tos --non-interactive
  validate_certificate
  systemctl enable --now happy-agent-cert-renew.timer
  log 'production IP certificate validated'
)

with_lock issue_core
