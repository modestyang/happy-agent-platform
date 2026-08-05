package happy.jayden.yang.agentbuilder.service.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.component.AgentDraft;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.HookBinding;
import happy.jayden.yang.agentbuilder.core.component.VersionReference;
import happy.jayden.yang.agentbuilder.core.component.evaluation.EvaluationSuiteVersion;
import happy.jayden.yang.agentbuilder.core.component.memory.MemoryPolicyVersion;
import happy.jayden.yang.agentbuilder.core.component.output.OutputSchemaVersion;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationDefaults;
import happy.jayden.yang.agentbuilder.core.defaults.ComponentDefaults;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import happy.jayden.yang.agentbuilder.core.defaults.PlatformLimits;
import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import happy.jayden.yang.agentbuilder.core.defaults.ValueSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogRegressionTest {
  private static final String CHECKSUM = "a".repeat(64);

  @Test
  void impactQueryMatchesExactComponentVersionsOnly() {
    var first = draft("agent.one", reference("skill.plan", 1));
    var second = draft("agent.two", reference("skill.plan", 2));
    var impact = new ImpactQuery(List.of(first, second));

    assertEquals(
        List.of(first),
        impact.affectedBy(
            new happy.jayden.yang.agentbuilder.core.component.ComponentRef(
                key("skill.plan"), version(1))));
  }

  @Test
  void previewUsesDraftSparseOverridesAndGenuineProvenance() {
    var profile = new DefaultProfileRef(key("defaults.fitness"), version(1), CHECKSUM);
    var application = new ApplicationDefaults("fitness", profile, DefaultValues.empty());
    var draft =
        draft(
            "fitness.coach",
            reference("skill.none", 1),
            Optional.of(reference("defaults.fitness", 1)));
    var preview =
        new ConfigPreviewService()
            .preview(
                draft,
                new PlatformLimits(Duration.ofSeconds(45), 5, 8_000, 2_000, BigDecimal.ONE, 1),
                new ComponentDefaults(
                    Duration.ofSeconds(30),
                    4,
                    4_000,
                    1_000,
                    BigDecimal.ONE,
                    new BigDecimal("0.2"),
                    new BigDecimal("0.9"),
                    500,
                    RetryPolicy.NONE,
                    profile.publishedRef()),
                application);

    assertEquals(draft.runtimeOverrides(), preview.overrides().values());
    assertEquals(
        ValueSource.CODE_DEFAULT, preview.resolvedConfig().sources().modelTemperature().source());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConfigPreviewService()
                .preview(
                    draft(
                        "fitness.coach",
                        reference("skill.none", 1),
                        Optional.of(reference("defaults.other", 1))),
                    new PlatformLimits(Duration.ofSeconds(45), 5, 8_000, 2_000, BigDecimal.ONE, 1),
                    new ComponentDefaults(
                        Duration.ofSeconds(30),
                        4,
                        4_000,
                        1_000,
                        BigDecimal.ONE,
                        new BigDecimal("0.2"),
                        new BigDecimal("0.9"),
                        500,
                        RetryPolicy.NONE,
                        profile.publishedRef()),
                    application));
  }

  @Test
  void previewRejectsDraftFromAnotherApplicationScope() {
    var profile = new DefaultProfileRef(key("defaults.fitness"), version(1), CHECKSUM);
    var application = new ApplicationDefaults("fitness", profile, DefaultValues.empty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConfigPreviewService()
                .preview(
                    draftForScope("other", Optional.empty()),
                    limits(),
                    defaults(profile),
                    application));
  }

  @Test
  void previewPreservesEveryDraftComponentOverrideAndItsAvailableProvenance() {
    var profile = new DefaultProfileRef(key("defaults.fitness"), version(1), CHECKSUM);
    var memoryRef = componentRef("memory.session", 1);
    var outputRef = componentRef("output.answer", 1);
    var evaluationRef = componentRef("evaluation.goal", 1);
    var hookBinding = new HookBinding(key("hook.audit"), version(1), true);
    var memory =
        new MemoryPolicyVersion(
            metadata(memoryRef),
            catalogMetadata(metadata(memoryRef)),
            MemoryPolicyVersion.PolicyType.WINDOW,
            MemoryPolicyVersion.Compression.NONE,
            2_000,
            1,
            0,
            0,
            new MemoryPolicyVersion.MemoryConfigSchema(true, true, 100),
            new MemoryPolicyVersion.MemoryConfig(true, 10, 100));
    var output =
        new OutputSchemaVersion(
            metadata(outputRef),
            catalogMetadata(metadata(outputRef)),
            new OutputSchemaVersion.ClosedObjectSchema(
                List.of(
                    new OutputSchemaVersion.Field(
                        "answer", OutputSchemaVersion.Type.STRING, true, "answer"))),
            List.of(
                new OutputSchemaVersion.Example(
                    "basic", List.of(new OutputSchemaVersion.Value("answer", "ok")))),
            CHECKSUM);
    var evaluation =
        new EvaluationSuiteVersion(
            metadata(evaluationRef),
            catalogMetadata(metadata(evaluationRef)),
            List.of(
                new EvaluationSuiteVersion.Case(
                    "case.valid",
                    "input",
                    List.of(
                        new EvaluationSuiteVersion.Assertion(
                            EvaluationSuiteVersion.AssertionType.CONTAINS, "ok")),
                    1,
                    false)),
            .8,
            EvaluationSuiteVersion.ScoringRule.WEIGHTED_AVERAGE,
            false,
            List.of(),
            CHECKSUM);
    var catalog =
        new CatalogDefinitions(
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(memoryRef, memory),
            Map.of(),
            Map.of(outputRef, output),
            Map.of(evaluationRef, evaluation),
            Map.of());
    var draft =
        new AgentDraft(
            "fitness.coach",
            "fitness",
            1,
            reference("framework.main", 1),
            reference("provider.main", 1),
            reference("model.main", 1),
            reference("prompt.main", 1),
            List.of(),
            List.of(),
            List.of(hookBinding),
            Optional.of(versionReference(memoryRef)),
            Optional.of(versionReference(outputRef)),
            Optional.of(versionReference(evaluationRef)),
            Optional.of(reference("defaults.fitness", 1)),
            DefaultValues.empty(),
            Instant.EPOCH);
    var preview =
        new ConfigPreviewService(
                new happy.jayden.yang.agentbuilder.core.defaults.EffectiveConfigResolver(), catalog)
            .preview(
                draft,
                limits(),
                defaults(profile),
                new ApplicationDefaults("fitness", profile, DefaultValues.empty()));

    assertEquals(
        ValueSource.AGENT_OVERRIDE, preview.resolvedConfig().sources().memoryPolicy().source());
    assertEquals(
        ValueSource.AGENT_OVERRIDE, preview.resolvedConfig().sources().outputSchema().source());
    var evaluationOverride = preview.overrides().evaluationSuiteVersion().orElseThrow();
    assertEquals(
        evaluationRef,
        new ComponentRef(evaluationOverride.componentKey(), evaluationOverride.version()));
    assertEquals(List.of(hookBinding), preview.overrides().hookBindings().orElseThrow());
  }

  @Test
  void profileVersioningPreservesOldProfileAndRejectsStaleExpectation() {
    var profile = new DefaultProfileRef(key("defaults.fitness"), version(1), CHECKSUM);
    var current =
        new ApplicationDefaults("fitness", profile, DefaultValues.empty().withMaxToolCalls(2));
    var service = new DefaultProfileVersioningService();
    var next =
        service.createNext(
            current, version(1), DefaultValues.empty().withMaxToolCalls(3), "b".repeat(64));

    assertEquals(1, current.defaultProfileVersion().version().value());
    assertEquals(2, next.defaultProfileVersion().version().value());
    assertEquals(2, current.values().maxToolCalls().orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.createNext(current, version(2), DefaultValues.empty(), "c".repeat(64)));
  }

  private static AgentDraft draft(String agentKey, VersionReference skill) {
    return draft(agentKey, skill, Optional.empty());
  }

  private static AgentDraft draft(
      String agentKey, VersionReference skill, Optional<VersionReference> defaultProfile) {
    return draftForScope("fitness", defaultProfile, agentKey, skill);
  }

  private static AgentDraft draftForScope(String scope, Optional<VersionReference> defaultProfile) {
    return draftForScope(scope, defaultProfile, "agent.main", reference("skill.none", 1));
  }

  private static AgentDraft draftForScope(
      String scope,
      Optional<VersionReference> defaultProfile,
      String agentKey,
      VersionReference skill) {
    return new AgentDraft(
        agentKey,
        scope,
        1,
        reference("framework.main", 1),
        reference("provider.main", 1),
        reference("model.main", 1),
        reference("prompt.main", 1),
        List.of(),
        List.of(
            new happy.jayden.yang.agentbuilder.core.component.SkillBinding(
                skill.componentKey(), skill.version(), true)),
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        defaultProfile,
        DefaultValues.empty(),
        Instant.EPOCH);
  }

  private static PlatformLimits limits() {
    return new PlatformLimits(Duration.ofSeconds(45), 5, 8_000, 2_000, BigDecimal.ONE, 1);
  }

  private static ComponentDefaults defaults(DefaultProfileRef profile) {
    return new ComponentDefaults(
        Duration.ofSeconds(30),
        4,
        4_000,
        1_000,
        BigDecimal.ONE,
        new BigDecimal("0.2"),
        new BigDecimal("0.9"),
        500,
        RetryPolicy.NONE,
        profile.publishedRef());
  }

  private static VersionReference reference(String key, int version) {
    return new VersionReference(key(key), version(version));
  }

  private static VersionReference versionReference(ComponentRef reference) {
    return new VersionReference(reference.componentKey(), reference.version());
  }

  private static ComponentRef componentRef(String key, int version) {
    return new ComponentRef(new ComponentKey(key), new ComponentVersion(version));
  }

  private static ComponentMetadata metadata(ComponentRef reference) {
    return ComponentMetadata.available(reference.componentKey(), reference.version(), CHECKSUM);
  }

  private static CatalogMetadata catalogMetadata(ComponentMetadata metadata) {
    return new CatalogMetadata(
        metadata.componentKey().value(),
        "description",
        "catalog",
        List.of("test"),
        List.of(componentRef("framework.main", 1)),
        new CatalogMetadata.Source(CatalogMetadata.SourceType.INTERNAL, "test"),
        new CatalogMetadata.Audit("tester", Instant.EPOCH),
        1);
  }

  private static ComponentKey key(String value) {
    return new ComponentKey(value);
  }

  private static ComponentVersion version(int value) {
    return new ComponentVersion(value);
  }
}
