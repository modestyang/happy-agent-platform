#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
CERTBOT_IMAGE='certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4'
issue() {
  local release webroot staging production challenge response
  release=$(current_release)
  service_healthy "$release" nginx || die "HTTP Nginx must be healthy before certificate issuance"
  webroot=$(validate_descendant "$HAPPY_AGENT_ROOT/data/acme-webroot")
  staging=$(validate_descendant "$HAPPY_AGENT_ROOT/certificates/staging")
  production=$(validate_descendant "$HAPPY_AGENT_ROOT/certificates/production")
  challenge="happy-agent-acme-$RANDOM-$$"
  install -d -m 0755 "$webroot/.well-known/acme-challenge"
  printf '%s' "$challenge" >"$webroot/.well-known/acme-challenge/$challenge"
  response=$(curl --fail --silent --show-error "http://39.101.65.254/.well-known/acme-challenge/$challenge") || die "ACME webroot HTTP proof failed"
  rm -f -- "$webroot/.well-known/acme-challenge/$challenge"
  [ "$response" = "$challenge" ] || die "ACME webroot served unexpected challenge"
  docker run --rm -v "$webroot:/var/www/acme" -v "$staging:/etc/letsencrypt" "$CERTBOT_IMAGE" certonly --webroot -w /var/www/acme --staging --ip-address 39.101.65.254 --preferred-profile shortlived --cert-name happy-agent-ip-staging --email modest_yang@126.com --agree-tos --non-interactive
  docker run --rm -v "$webroot:/var/www/acme" -v "$production:/etc/letsencrypt" "$CERTBOT_IMAGE" certonly --webroot -w /var/www/acme --ip-address 39.101.65.254 --preferred-profile shortlived --cert-name happy-agent-ip --email modest_yang@126.com --agree-tos --non-interactive
  validate_certificate
  systemctl enable --now happy-agent-cert-renew.timer
  log "production IP certificate validated"
}
with_lock issue
