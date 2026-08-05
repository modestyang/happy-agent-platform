package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.catalog.DefaultProfileRepository;
import happy.jayden.yang.agentbuilder.core.catalog.EvaluationSuiteRepository;
import happy.jayden.yang.agentbuilder.core.catalog.FrameworkRepository;
import happy.jayden.yang.agentbuilder.core.catalog.HookRepository;
import happy.jayden.yang.agentbuilder.core.catalog.MemoryPolicyRepository;
import happy.jayden.yang.agentbuilder.core.catalog.ModelRepository;
import happy.jayden.yang.agentbuilder.core.catalog.OutputSchemaRepository;
import happy.jayden.yang.agentbuilder.core.catalog.PromptRepository;
import happy.jayden.yang.agentbuilder.core.catalog.ProviderRepository;
import happy.jayden.yang.agentbuilder.core.catalog.SkillRepository;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentStatus;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.evaluation.EvaluationSuiteVersion;
import happy.jayden.yang.agentbuilder.core.component.framework.FrameworkAdapterDefinition;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.component.memory.MemoryPolicyVersion;
import happy.jayden.yang.agentbuilder.core.component.model.ModelDefinition;
import happy.jayden.yang.agentbuilder.core.component.output.OutputSchemaVersion;
import happy.jayden.yang.agentbuilder.core.component.prompt.PromptVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderVersion;
import happy.jayden.yang.agentbuilder.core.component.skill.SkillDefinition;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogRepositoryContractTest {
  private static final String CHECKSUM = "a".repeat(64);

  @Test
  void everyAggregateFamilyHasATypedPortAndConcreteJdbcAdapter() throws Exception {
    assertBinding(SkillRepository.class, JdbcSkillRepository.class, SkillDefinition.class);
    assertBinding(HookRepository.class, JdbcHookRepository.class, HookDefinition.class);
    assertBinding(
        FrameworkRepository.class, JdbcFrameworkRepository.class, FrameworkAdapterDefinition.class);
    assertBinding(ProviderRepository.class, JdbcProviderRepository.class, ProviderVersion.class);
    assertBinding(ModelRepository.class, JdbcModelRepository.class, ModelDefinition.class);
    assertBinding(
        MemoryPolicyRepository.class, JdbcMemoryPolicyRepository.class, MemoryPolicyVersion.class);
    assertBinding(PromptRepository.class, JdbcPromptRepository.class, PromptVersion.class);
    assertBinding(
        OutputSchemaRepository.class, JdbcOutputSchemaRepository.class, OutputSchemaVersion.class);
    assertBinding(
        EvaluationSuiteRepository.class,
        JdbcEvaluationSuiteRepository.class,
        EvaluationSuiteVersion.class);
    assertFalse(Modifier.isAbstract(JdbcDefaultProfileRepository.class.getModifiers()));
    assertEquals(
        DefaultProfileRepository.class, JdbcDefaultProfileRepository.class.getInterfaces()[0]);
  }

  @Test
  void typedPayloadCodecRoundTripsDomainRecordsAndNeverSerializesProviderCiphertext()
      throws Exception {
    var prompt = prompt(1, 1, "Hello {{name}}");
    var codec = CatalogJsonCodec.standard();
    assertEquals(prompt, codec.read(codec.write(prompt), PromptVersion.class));

    var providerJson = codec.write(provider());
    assertFalse(providerJson.contains("plain-secret"));
    assertFalse(providerJson.contains("ciphertext"));
    assertFalse(providerJson.contains("credential_iv"));
  }

  @Test
  void optimisticWriteGuardRejectsZeroAndMultipleRows() {
    assertThrows(OptimisticCatalogLockException.class, () -> CatalogWriteGuard.updated(0));
    assertThrows(IllegalStateException.class, () -> CatalogWriteGuard.updated(2));
    assertEquals(1, CatalogWriteGuard.updated(1));
    for (var immutableStatus :
        List.of(
            ComponentStatus.AVAILABLE,
            ComponentStatus.DEPRECATED,
            ComponentStatus.DISABLED,
            ComponentStatus.RETIRED))
      assertThrows(
          ImmutableCatalogVersionException.class,
          () -> CatalogWriteGuard.updatedDraft(0, () -> Optional.of(immutableStatus)));
    assertThrows(
        OptimisticCatalogLockException.class,
        () -> CatalogWriteGuard.updatedDraft(0, () -> Optional.of(ComponentStatus.DRAFT)));
  }

  @Test
  void defaultActivationSqlUsesASeparateRevisionCheckedPointer() {
    assertTrue(DefaultProfileSql.FIND_ACTIVE.contains("JOIN default_profile_catalog"));
    assertTrue(
        DefaultProfileSql.INSERT_POINTER.contains("ON CONFLICT (application_key) DO NOTHING"));
    assertTrue(DefaultProfileSql.UPDATE_POINTER.endsWith("application_key=? AND revision=?"));
    assertTrue(DefaultProfileSql.UPDATE_DRAFT.endsWith("revision=? AND status='DRAFT'"));
    assertTrue(ProviderSql.UPDATE_DRAFT.endsWith("revision=? AND status='DRAFT'"));
    assertFalse(DefaultProfileSql.UPDATE_POINTER.contains("default_profile_catalog SET active"));
  }

  @Test
  void sharedSqlUsesExactIdentityTypedPayloadAndAllSupportedFilters() {
    var columns =
        List.of(
            CatalogColumn.raw("template", PromptVersion::template),
            CatalogColumn.json("variable_schema", PromptVersion::variables));
    assertEquals(
        "INSERT INTO prompt_catalog(component_key,version,application_scope,status,revision,checksum,prompt_payload,tags,template,variable_schema) VALUES (?,?,?,?,?,?,?::jsonb,?::text[],?,?::jsonb)",
        CatalogSql.insert("prompt_catalog", "prompt_payload", columns));
    assertEquals(
        "SELECT prompt_payload::text FROM prompt_catalog WHERE component_key=? AND version=?",
        CatalogSql.exact("prompt_catalog", "prompt_payload"));
    var statement =
        CatalogSql.list(
            "prompt_catalog",
            "prompt_payload",
            new happy.jayden.yang.agentbuilder.core.catalog.CatalogFilter(
                "fitness", Optional.of(ComponentStatus.AVAILABLE), Optional.of("fitness")));
    assertEquals(
        "SELECT prompt_payload::text FROM prompt_catalog WHERE application_scope=? AND status=? AND ?=ANY(tags) ORDER BY component_key,version",
        statement.sql());
    assertEquals(List.of("fitness", "AVAILABLE", "fitness"), statement.arguments());
    assertEquals(
        "UPDATE prompt_catalog SET status=?,revision=?,checksum=?,prompt_payload=?::jsonb,tags=?::text[],template=?,variable_schema=?::jsonb WHERE component_key=? AND version=? AND revision=? AND status='DRAFT'",
        CatalogSql.update("prompt_catalog", "prompt_payload", columns));
  }

  private static void assertBinding(Class<?> port, Class<?> adapter, Class<?> aggregate)
      throws Exception {
    assertFalse(Modifier.isAbstract(adapter.getModifiers()));
    assertEquals(port, adapter.getInterfaces()[0]);
    var field = adapter.getDeclaredField("AGGREGATE_TYPE");
    field.setAccessible(true);
    assertEquals(aggregate, field.get(null));
  }

  private static PromptVersion prompt(int version, long revision, String template) {
    var metadata = metadata("prompt.coach", version);
    return new PromptVersion(
        metadata,
        catalog(metadata, revision),
        PromptVersion.TemplateFormat.MUSTACHE,
        template,
        List.of(new PromptVersion.Variable("name", PromptVersion.Type.STRING, true)),
        CHECKSUM);
  }

  private static ProviderVersion provider() {
    var metadata = metadata("provider.main", 1);
    return new ProviderVersion(
        metadata,
        "Provider",
        "Description",
        List.of("fitness"),
        new ProviderVersion.CredentialReference("provider.main", 1),
        "https://example.test",
        happy.jayden.yang.agentbuilder.core.component.provider.ProviderPublicConfig.empty(),
        catalog(metadata, 1));
  }

  private static ComponentMetadata metadata(String key, int version) {
    return ComponentMetadata.available(
        new ComponentKey(key), new ComponentVersion(version), CHECKSUM);
  }

  private static CatalogMetadata catalog(ComponentMetadata metadata, long revision) {
    return new CatalogMetadata(
        metadata.componentKey().value(),
        "description",
        "catalog",
        List.of("fitness"),
        List.of(new ComponentRef(new ComponentKey("framework.main"), new ComponentVersion(1))),
        new CatalogMetadata.Source(CatalogMetadata.SourceType.INTERNAL, "test"),
        new CatalogMetadata.Audit("tester", Instant.EPOCH),
        revision);
  }
}
