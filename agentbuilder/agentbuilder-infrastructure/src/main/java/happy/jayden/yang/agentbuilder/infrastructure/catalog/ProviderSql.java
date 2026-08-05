package happy.jayden.yang.agentbuilder.infrastructure.catalog;

final class ProviderSql {
  static final String UPDATE_DRAFT =
      "UPDATE provider_catalog SET status=?,revision=?,checksum=?,display_name=?,endpoint=?,public_config=?::jsonb,provider_payload=?::jsonb,credential_ciphertext=?,credential_iv=?,credential_aad=?,credential_key_version=?,tags=?::text[] WHERE component_key=? AND version=? AND revision=? AND status='DRAFT'";

  private ProviderSql() {}
}
