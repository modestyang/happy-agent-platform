#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); source "$SCRIPT_DIR/common.sh"
CERTBOT_IMAGE='certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4'
renew() {
  local release production log_file
  release=$(current_release)
  production=$(validate_descendant "$HAPPY_AGENT_ROOT/certificates/production")
  require_file "$(certificate_dir)/fullchain.pem"
  require_file "$(certificate_dir)/privkey.pem"
  install -d -m 0750 "$HAPPY_AGENT_ROOT/logs"
  log_file=$(validate_descendant "$HAPPY_AGENT_ROOT/logs/cert-renew.log")
  if ! docker run --rm -v "$production:/etc/letsencrypt" "$CERTBOT_IMAGE" renew --preferred-profile shortlived; then
    if ! openssl x509 -in "$(certificate_dir)/fullchain.pem" -noout -checkend 172800; then
      printf '%s renewal-failed-expiring\n' "$(date -u +%FT%TZ)" >>"$log_file"
      die "renewal failed and certificate expires within 48 hours"
    fi
    printf '%s renewal-failed-existing-valid\n' "$(date -u +%FT%TZ)" >>"$log_file"
    die "certificate renewal failed"
  fi
  validate_certificate
  compose_release "$release" exec -T nginx nginx -t
  compose_release "$release" exec -T nginx nginx -s reload
  printf '%s renewal-validated\n' "$(date -u +%FT%TZ)" >>"$log_file"
}
with_lock renew
