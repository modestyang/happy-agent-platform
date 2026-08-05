package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class CatalogMigrationStaticTest {
  private static final String V2_SHA256 =
      "1772284e624162504852f90b770090def4dfce00e2abd0f936b80a85f11f2949";

  @Test
  void appliedV2MigrationRemainsByteForByteImmutable() throws Exception {
    var bytes = resource("/db/agent/V2__component_catalogs.sql");
    assertEquals(
        V2_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
  }

  @Test
  void v2UsesOneTypedTablePerAggregateAndNoEavTable() throws Exception {
    var sql = text("/db/agent/V2__component_catalogs.sql");
    for (var table :
        java.util.List.of(
            "skill_catalog",
            "hook_catalog",
            "provider_catalog",
            "model_catalog",
            "memory_policy_catalog",
            "prompt_catalog",
            "output_schema_catalog",
            "evaluation_suite_catalog",
            "framework_adapter_catalog",
            "default_profile_catalog")) assertTrue(sql.contains("create table " + table), table);
    assertFalse(sql.contains("component_type"));
    assertFalse(sql.contains("attribute_name"));
    assertTrue(sql.contains("default_profile_one_active_idx"));
    assertTrue(sql.contains("credential_ciphertext bytea"));
    assertTrue(sql.contains("check (octet_length(credential_iv)=12)"));
    assertTrue(sql.contains("credential_aad bytea not null"));
    assertTrue(sql.contains("prompt_payload jsonb not null"));
    assertTrue(sql.contains("defaults_payload jsonb not null"));
  }

  @Test
  void v3BackfillsPointerThenRemovesLegacyActiveStateAndEnforcesApplicationOwnership()
      throws Exception {
    var sql = text("/db/agent/V3__default_profile_active_pointer.sql");
    assertTrue(sql.contains("create table default_profile_active_pointer"));
    assertTrue(sql.contains("distinct on (application_key)"));
    assertTrue(sql.contains("where active"));
    assertTrue(sql.contains("order by application_key, version desc, profile_key"));
    assertTrue(sql.contains("unique (application_key, profile_key, version)"));
    assertTrue(
        sql.contains(
            "foreign key(application_key, profile_key, version) references default_profile_catalog(application_key, profile_key, version)"));
    assertTrue(sql.contains("drop index default_profile_one_active_idx"));
    assertTrue(sql.contains("defaults_payload = defaults_payload - 'active'"));
    assertTrue(sql.contains("drop column active"));
  }

  private static String text(String path) throws Exception {
    return new String(resource(path), StandardCharsets.UTF_8).toLowerCase().replaceAll("\\s+", " ");
  }

  private static byte[] resource(String path) throws Exception {
    try (var stream = CatalogMigrationStaticTest.class.getResourceAsStream(path)) {
      if (stream == null) throw new IllegalStateException("missing migration: " + path);
      return stream.readAllBytes();
    }
  }
}
