package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CatalogMigrationStaticTest {
  @Test
  void baselineContainsIndependentRuntimeResourcesAndNoProjectionTable() throws Exception {
    var sql = text("/db/agent/V1__agent_baseline.sql");
    for (var table :
        java.util.List.of(
            "agent_providers",
            "agent_models",
            "agent_prompts",
            "agent_skills",
            "agent_hooks",
            "agent_frameworks",
            "agent_memories")) assertTrue(sql.contains("create table " + table), table);
    assertFalse(sql.contains("agent_component_projection"));
  }

  @Test
  void baselineStillContainsTheTypedCatalogContractsUsedByTheCore() throws Exception {
    var sql = text("/db/agent/V1__agent_baseline.sql");
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
    assertTrue(sql.contains("credential_ciphertext bytea"));
    assertTrue(sql.contains("check (octet_length(credential_iv)=12)"));
    assertTrue(sql.contains("credential_aad bytea not null"));
    assertTrue(sql.contains("prompt_payload jsonb not null"));
    assertTrue(sql.contains("defaults_payload jsonb not null"));
    assertTrue(sql.contains("create table default_profile_active_pointer"));
    assertTrue(sql.contains("unique(application_key, profile_key, version)"));
    assertTrue(
        sql.contains(
            "foreign key(application_key, profile_key, version) references default_profile_catalog(application_key, profile_key, version)"));
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
