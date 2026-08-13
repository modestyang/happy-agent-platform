#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"

readonly CERTBOT_RUNTIME_IMAGE='certbot/certbot:v5.7.0'

renew_core() {
  local release production webroot log_file
  release=$(current_release)
  verify_manifest "$release" .env compose.yml nginx.conf
  service_matches_release "$release" nginx || die 'active Nginx identity or health is invalid'
  production=$(validate_descendant "$HAPPY_AGENT_ROOT/certificates/production")
  webroot=$(validate_descendant "$HAPPY_AGENT_ROOT/data/acme-webroot")
  require_file "$(certificate_dir)/cert.pem"
  require_file "$(certificate_dir)/fullchain.pem"
  require_file "$(certificate_dir)/chain.pem"
  require_file "$(certificate_dir)/privkey.pem"
  install -d -m 0750 "$HAPPY_AGENT_ROOT/logs"
  log_file=$(validate_descendant "$HAPPY_AGENT_ROOT/logs/cert-renew.log")
  touch "$log_file"
  chmod 0640 "$log_file"

  if ! docker run --pull never --rm -v "$webroot:/var/www/acme" -v "$production:/etc/letsencrypt" "$CERTBOT_RUNTIME_IMAGE" \
      renew --preferred-profile shortlived; then
    if ! openssl x509 -in "$(certificate_dir)/cert.pem" -noout -checkend 172800; then
      printf '%s renewal-failed-expiring\n' "$(date -u +%FT%TZ)" >>"$log_file"
      die 'renewal failed and certificate expires within 48 hours'
    fi
    printf '%s renewal-failed-existing-valid\n' "$(date -u +%FT%TZ)" >>"$log_file"
    die 'certificate renewal failed'
  fi
  validate_certificate
  compose_release "$release" exec -T nginx nginx -t
  compose_release "$release" exec -T nginx nginx -s reload
  printf '%s renewal-validated\n' "$(date -u +%FT%TZ)" >>"$log_file"
}

with_lock renew_core
