package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import happy.jayden.yang.agentbuilder.core.component.BooleanValue;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.ConfigEntry;
import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.HookBinding;
import happy.jayden.yang.agentbuilder.core.component.NumberValue;
import happy.jayden.yang.agentbuilder.core.component.StringListValue;
import happy.jayden.yang.agentbuilder.core.component.StringValue;
import happy.jayden.yang.agentbuilder.core.component.evaluation.EvaluationSuiteVersion;
import happy.jayden.yang.agentbuilder.core.component.framework.FrameworkAdapterDefinition;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.component.memory.MemoryPolicyVersion;
import happy.jayden.yang.agentbuilder.core.component.model.ModelDefinition;
import happy.jayden.yang.agentbuilder.core.component.output.OutputSchemaVersion;
import happy.jayden.yang.agentbuilder.core.component.prompt.PromptVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderPublicConfig;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderVersion;
import happy.jayden.yang.agentbuilder.core.component.skill.SkillDefinition;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationKey;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultProfileVersion;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import happy.jayden.yang.agentbuilder.core.defaults.PlatformLimits;
import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import happy.jayden.yang.agentbuilder.core.defaults.SparseModelParameters;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogJsonCodecTest {
  private static final String CHECKSUM = "a".repeat(64);
  private static final ComponentRef FRAMEWORK = ref("framework.main");
  private final CatalogJsonCodec codec = CatalogJsonCodec.standard();

  @Test
  void everyAggregateFamilyRoundTripsPopulatedValidatedState() {
    var skillMetadata = metadata("skill.support");
    roundTrip(
        new SkillDefinition(
            skillMetadata,
            "# Support",
            List.of(new SkillDefinition.Resource("guide.md", "text/markdown", CHECKSUM)),
            CHECKSUM,
            new SkillDefinition.ProgressiveDisclosure(List.of("guide.md"), List.of("extra.md")),
            List.of(ref("tool.search")),
            catalog(skillMetadata)),
        SkillDefinition.class);

    var hookMetadata = metadata("hook.audit");
    roundTrip(
        new HookDefinition(
            hookMetadata,
            "audit",
            Set.of(HookDefinition.Phase.PRE_TOOL, HookDefinition.Phase.POST_TOOL),
            1,
            new HookDefinition.ConfigSchema("{\"type\":\"object\"}"),
            100,
            HookDefinition.SideEffect.READ,
            HookDefinition.FailurePolicy.FAIL_CLOSED,
            true,
            Set.of("fitness"),
            Set.of(FRAMEWORK),
            catalog(hookMetadata)),
        HookDefinition.class);

    var frameworkMetadata = metadata("framework.main");
    roundTrip(
        new FrameworkAdapterDefinition(
            frameworkMetadata,
            "Framework",
            "Description",
            List.of("fitness"),
            true,
            true,
            true,
            catalog(frameworkMetadata)),
        FrameworkAdapterDefinition.class);

    var providerMetadata = metadata("provider.main");
    roundTrip(
        new ProviderVersion(
            providerMetadata,
            "Provider",
            "Description",
            List.of("fitness"),
            new ProviderVersion.CredentialReference("provider.main", 2),
            "https://example.test",
            new ProviderPublicConfig("cn", "v1", "org", "project"),
            catalog(providerMetadata)),
        ProviderVersion.class);

    var modelMetadata = metadata("model.main");
    roundTrip(
        new ModelDefinition(
            modelMetadata,
            "qwen-max",
            ref("provider.main"),
            List.of(ModelDefinition.Modality.TEXT, ModelDefinition.Modality.IMAGE),
            32_000,
            4_000,
            new ModelDefinition.Capabilities(true, true, true, true, false),
            SparseModelParameters.empty()
                .withTemperature(new BigDecimal("0.7"))
                .withTopP(new BigDecimal("0.9"))
                .withMaxOutputTokens(1000),
            catalog(modelMetadata)),
        ModelDefinition.class);

    var memoryMetadata = metadata("memory.summary");
    roundTrip(
        new MemoryPolicyVersion(
            memoryMetadata,
            catalog(memoryMetadata),
            MemoryPolicyVersion.PolicyType.SUMMARY,
            MemoryPolicyVersion.Compression.SUMMARY,
            4_000,
            30,
            3_000,
            20,
            new MemoryPolicyVersion.MemoryConfigSchema(true, true, 100),
            new MemoryPolicyVersion.MemoryConfig(true, 5, 50)),
        MemoryPolicyVersion.class);

    var promptMetadata = metadata("prompt.coach");
    roundTrip(
        new PromptVersion(
            promptMetadata,
            catalog(promptMetadata),
            PromptVersion.TemplateFormat.MUSTACHE,
            "Hello {{name}}",
            List.of(new PromptVersion.Variable("name", PromptVersion.Type.STRING, true)),
            CHECKSUM),
        PromptVersion.class);

    var outputMetadata = metadata("output.answer");
    roundTrip(
        new OutputSchemaVersion(
            outputMetadata,
            catalog(outputMetadata),
            new OutputSchemaVersion.ClosedObjectSchema(
                List.of(
                    new OutputSchemaVersion.Field(
                        "answer", OutputSchemaVersion.Type.STRING, true, "answer"))),
            List.of(
                new OutputSchemaVersion.Example(
                    "basic", List.of(new OutputSchemaVersion.Value("answer", "yes")))),
            CHECKSUM),
        OutputSchemaVersion.class);

    var evaluationMetadata = metadata("evaluation.safety");
    roundTrip(
        new EvaluationSuiteVersion(
            evaluationMetadata,
            catalog(evaluationMetadata),
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
            CHECKSUM),
        EvaluationSuiteVersion.class);

    roundTrip(defaultProfile(), DefaultProfileVersion.class);
    roundTrip(
        new PlatformLimits(Duration.ofSeconds(45), 3, 4_000, 1_000, new BigDecimal("0.25"), 1),
        PlatformLimits.class);
  }

  @Test
  void configValuesUseOnlyTheClosedKindDiscriminator() {
    var json = codec.write(defaultProfile());
    assertTrue(json.contains("\"kind\":\"string\""));
    assertTrue(json.contains("\"kind\":\"number\""));
    assertTrue(json.contains("\"kind\":\"boolean\""));
    assertTrue(json.contains("\"kind\":\"stringList\""));
    assertFalse(json.contains("@class"));
    assertFalse(json.contains("happy.jayden"));
  }

  @Test
  void callerDefaultTypingIsExplicitlyDisabled() {
    var unsafeMapper =
        new ObjectMapper()
            .activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
    var hardenedCodec = new CatalogJsonCodec(unsafeMapper);
    var profile = defaultProfile();
    var json = hardenedCodec.write(profile);
    assertFalse(json.contains("@class"));
    assertFalse(json.contains("happy.jayden"));
    assertEquals(profile, hardenedCodec.read(json, DefaultProfileVersion.class));
  }

  @Test
  void configValueDeserializerRejectsUnknownKindsAndClassMetadata() {
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.read(
                "{\"path\":\"x\",\"value\":{\"kind\":\"runtimeClass\",\"value\":\"x\"}}",
                ConfigEntry.class));
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.read(
                "{\"path\":\"x\",\"value\":{\"kind\":\"string\",\"value\":\"x\",\"@class\":\"java.lang.Runtime\"}}",
                ConfigEntry.class));
  }

  private DefaultProfileVersion defaultProfile() {
    var values =
        DefaultValues.empty()
            .withTimeout(Duration.ofSeconds(30))
            .withMaxToolCalls(3)
            .withMaxInputTokens(4_000)
            .withMaxOutputTokens(1_000)
            .withMaxCostUsd(new BigDecimal("0.25"))
            .withTemperature(new BigDecimal("0.5"))
            .withTopP(new BigDecimal("0.8"))
            .withModelMaxOutputTokens(900)
            .withRetryPolicy(RetryPolicy.SAFE_ONCE)
            .withOptionalHookDefaults(
                List.of(
                    new HookBinding(
                        new ComponentKey("hook.audit"),
                        new ComponentVersion(1),
                        true,
                        List.of(
                            new ConfigEntry("a.string", new StringValue("value")),
                            new ConfigEntry("b.number", new NumberValue(new BigDecimal("1.5"))),
                            new ConfigEntry("c.boolean", new BooleanValue(true)),
                            new ConfigEntry(
                                "d.list", new StringListValue(List.of("one", "two")))))));
    return new DefaultProfileVersion(
        new ApplicationKey("fitness"),
        new DefaultProfileRef(metadata("defaults.fitness")),
        values,
        1,
        List.of("fitness"));
  }

  private <T> void roundTrip(T value, Class<T> type) {
    assertEquals(value, codec.read(codec.write(value), type));
  }

  private static ComponentMetadata metadata(String key) {
    return ComponentMetadata.available(new ComponentKey(key), new ComponentVersion(1), CHECKSUM);
  }

  private static ComponentRef ref(String key) {
    return new ComponentRef(new ComponentKey(key), new ComponentVersion(1));
  }

  private static CatalogMetadata catalog(ComponentMetadata metadata) {
    return new CatalogMetadata(
        metadata.componentKey().value(),
        "description",
        "catalog",
        List.of("fitness"),
        List.of(FRAMEWORK),
        new CatalogMetadata.Source(CatalogMetadata.SourceType.INTERNAL, "test"),
        new CatalogMetadata.Audit("tester", Instant.parse("2026-08-05T00:00:00Z")),
        1);
  }
}
