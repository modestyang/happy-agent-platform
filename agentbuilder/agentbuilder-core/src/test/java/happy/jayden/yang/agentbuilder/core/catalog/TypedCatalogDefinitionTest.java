package happy.jayden.yang.agentbuilder.core.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.evaluation.EvaluationSuiteVersion;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.component.memory.MemoryPolicyVersion;
import happy.jayden.yang.agentbuilder.core.component.model.ModelDefinition;
import happy.jayden.yang.agentbuilder.core.component.output.OutputSchemaVersion;
import happy.jayden.yang.agentbuilder.core.component.prompt.PromptVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderPublicConfig;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderVersion;
import happy.jayden.yang.agentbuilder.core.component.skill.SkillDefinition;
import happy.jayden.yang.agentbuilder.core.defaults.SparseModelParameters;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypedCatalogDefinitionTest {
  private static final String CHECKSUM = "a".repeat(64);
  private static final ComponentRef FRAMEWORK = ref("framework.main");

  @Test
  void allEightDefinitionsRequireExplicitImmutableMetadataAndTypedContracts() {
    var metadata = metadata("skill.support");
    assertDoesNotThrow(
        () ->
            new SkillDefinition(
                metadata,
                "# Support",
                List.of(new SkillDefinition.Resource("guide.md", "text/markdown", CHECKSUM)),
                CHECKSUM,
                SkillDefinition.ProgressiveDisclosure.none(),
                List.of(ref("tool.search")),
                catalog(metadata)));
    assertDoesNotThrow(
        () ->
            new ProviderVersion(
                metadata("provider.main"),
                "Provider",
                "Description",
                List.of("tag"),
                new ProviderVersion.CredentialReference("secret.key", 1),
                "https://example.test",
                ProviderPublicConfig.empty(),
                catalog(metadata("provider.main"))));
    assertDoesNotThrow(
        () ->
            new ModelDefinition(
                metadata("model.main"),
                "model",
                ref("provider.main"),
                List.of(ModelDefinition.Modality.TEXT),
                100,
                10,
                new ModelDefinition.Capabilities(true, true, true, false, false),
                SparseModelParameters.empty(),
                catalog(metadata("model.main"))));
    assertDoesNotThrow(
        () ->
            new HookDefinition(
                metadata("hook.audit"),
                "audit",
                Set.of(HookDefinition.Phase.PRE_TOOL),
                1,
                new HookDefinition.ConfigSchema("{}"),
                100,
                HookDefinition.SideEffect.READ,
                HookDefinition.FailurePolicy.FAIL_CLOSED,
                true,
                Set.of("fitness"),
                Set.of(FRAMEWORK),
                catalog(metadata("hook.audit"))));
    assertDoesNotThrow(
        () ->
            new MemoryPolicyVersion(
                metadata("memory.window"),
                catalog(metadata("memory.window")),
                MemoryPolicyVersion.PolicyType.WINDOW,
                MemoryPolicyVersion.Compression.NONE,
                100,
                1,
                0,
                0,
                new MemoryPolicyVersion.MemoryConfigSchema(true, true, 10),
                new MemoryPolicyVersion.MemoryConfig(true, 1, 10)));
    assertDoesNotThrow(
        () ->
            new PromptVersion(
                metadata("prompt.coach"),
                catalog(metadata("prompt.coach")),
                PromptVersion.TemplateFormat.MUSTACHE,
                "Hello {{name}}",
                List.of(new PromptVersion.Variable("name", PromptVersion.Type.STRING, true)),
                CHECKSUM));
    assertDoesNotThrow(
        () ->
            new OutputSchemaVersion(
                metadata("output.answer"),
                catalog(metadata("output.answer")),
                new OutputSchemaVersion.ClosedObjectSchema(
                    List.of(
                        new OutputSchemaVersion.Field(
                            "answer", OutputSchemaVersion.Type.STRING, true, "answer"))),
                List.of(
                    new OutputSchemaVersion.Example(
                        "basic", List.of(new OutputSchemaVersion.Value("answer", "yes")))),
                CHECKSUM));
    assertDoesNotThrow(
        () ->
            new EvaluationSuiteVersion(
                metadata("evaluation.safety"),
                catalog(metadata("evaluation.safety")),
                List.of(
                    new EvaluationSuiteVersion.Case(
                        "case.one",
                        "hello",
                        List.of(
                            new EvaluationSuiteVersion.Assertion(
                                EvaluationSuiteVersion.AssertionType.CONTAINS, "hello")),
                        1,
                        true)),
                .8,
                EvaluationSuiteVersion.ScoringRule.SAFETY_GATE_THEN_WEIGHTED,
                true,
                List.of(new EvaluationSuiteVersion.Criterion("safe", "safe")),
                CHECKSUM));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillDefinition.Resource("noextension", "text/plain", CHECKSUM));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillDefinition.Resource("guide.md", "application/json", CHECKSUM));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillDefinition.Resource("run.py", "text/plain", CHECKSUM));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EvaluationSuiteVersion.Case(
                "case.bad",
                "in",
                List.of(
                    new EvaluationSuiteVersion.Assertion(
                        EvaluationSuiteVersion.AssertionType.EXACT, "x")),
                101,
                false));
  }

  @Test
  void memoryCompressionSettingsAndEvaluationSafetyRuleMustBeCoherent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MemoryPolicyVersion(
                metadata("memory.invalid"),
                catalog(metadata("memory.invalid")),
                MemoryPolicyVersion.PolicyType.WINDOW,
                MemoryPolicyVersion.Compression.SUMMARY,
                100,
                1,
                0,
                0,
                new MemoryPolicyVersion.MemoryConfigSchema(true, true, 10),
                new MemoryPolicyVersion.MemoryConfig(true, 1, 10)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EvaluationSuiteVersion(
                metadata("evaluation.invalid"),
                catalog(metadata("evaluation.invalid")),
                List.of(
                    new EvaluationSuiteVersion.Case(
                        "case.one",
                        "hello",
                        List.of(
                            new EvaluationSuiteVersion.Assertion(
                                EvaluationSuiteVersion.AssertionType.EXACT, "hello")),
                        1,
                        false)),
                .8,
                EvaluationSuiteVersion.ScoringRule.SAFETY_GATE_THEN_WEIGHTED,
                false,
                List.of(),
                CHECKSUM));
  }

  @Test
  void memoryDefaultsMustFitDeclaredSchemaAndTokenBudget() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            memory(
                MemoryPolicyVersion.Compression.SUMMARY,
                101,
                new MemoryPolicyVersion.MemoryConfigSchema(true, true, 10),
                new MemoryPolicyVersion.MemoryConfig(true, 1, 10)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            memory(
                MemoryPolicyVersion.Compression.NONE,
                0,
                new MemoryPolicyVersion.MemoryConfigSchema(false, true, 10),
                new MemoryPolicyVersion.MemoryConfig(true, 1, 10)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            memory(
                MemoryPolicyVersion.Compression.NONE,
                0,
                new MemoryPolicyVersion.MemoryConfigSchema(true, true, 5),
                new MemoryPolicyVersion.MemoryConfig(true, 1, 10)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            memory(
                MemoryPolicyVersion.Compression.NONE,
                0,
                new MemoryPolicyVersion.MemoryConfigSchema(true, true, 10),
                new MemoryPolicyVersion.MemoryConfig(true, 11, 10)));
  }

  @Test
  void providerMaskedResponseJacksonSerializationOmitsCredentialReference() throws Exception {
    var response =
        new ProviderVersion(
                metadata("provider.secret"),
                "Provider",
                "Description",
                List.of(),
                new ProviderVersion.CredentialReference("known-secret-reference", 7),
                "https://example.test",
                ProviderPublicConfig.empty(),
                catalog(metadata("provider.secret")))
            .maskedResponse();
    var json = new ObjectMapper().writeValueAsString(response);
    assertFalse(json.contains("known-secret-reference"));
    assertFalse(json.contains("credentialReference"));
    assertFalse(json.contains("\"version\":7"));
  }

  private static ComponentMetadata metadata(String key) {
    return ComponentMetadata.available(new ComponentKey(key), new ComponentVersion(1), CHECKSUM);
  }

  private static MemoryPolicyVersion memory(
      MemoryPolicyVersion.Compression compression,
      int threshold,
      MemoryPolicyVersion.MemoryConfigSchema schema,
      MemoryPolicyVersion.MemoryConfig defaults) {
    var metadata = metadata("memory.checked");
    return new MemoryPolicyVersion(
        metadata,
        catalog(metadata),
        MemoryPolicyVersion.PolicyType.WINDOW,
        compression,
        100,
        1,
        threshold,
        compression == MemoryPolicyVersion.Compression.NONE ? 0 : 10,
        schema,
        defaults);
  }

  private static ComponentRef ref(String key) {
    return new ComponentRef(new ComponentKey(key), new ComponentVersion(1));
  }

  private static CatalogMetadata catalog(ComponentMetadata metadata) {
    return new CatalogMetadata(
        metadata.componentKey().value(),
        "description",
        "catalog",
        List.of("tag"),
        List.of(FRAMEWORK),
        new CatalogMetadata.Source(CatalogMetadata.SourceType.INTERNAL, "test"),
        new CatalogMetadata.Audit("tester", Instant.EPOCH),
        1);
  }
}
