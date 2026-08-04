package happy.jayden.yang.agentbuilder.core.defaults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.component.AgentComponents;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.EvaluationSuiteRef;
import happy.jayden.yang.agentbuilder.core.component.FrameworkRef;
import happy.jayden.yang.agentbuilder.core.component.HookBinding;
import happy.jayden.yang.agentbuilder.core.component.MemoryPolicyRef;
import happy.jayden.yang.agentbuilder.core.component.ModelBinding;
import happy.jayden.yang.agentbuilder.core.component.OutputSchemaRef;
import happy.jayden.yang.agentbuilder.core.component.PromptRef;
import happy.jayden.yang.agentbuilder.core.component.ProviderRef;
import happy.jayden.yang.agentbuilder.core.component.PublishedHookBinding;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class ResolvedAgentDefinitionTest {

  private static final String A = "a".repeat(64);
  private static final String B = "b".repeat(64);

  @Test
  void resolverClosesComponentOverridesOptionalHooksAndTheirProvenanceTogether() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.base");
    var applicationHook = new HookBinding(key("hook.audit"), version(1), true, List.of());
    var application =
        new ApplicationDefaults(
            "fitness",
            baseline.defaultProfileVersion().publishedRef(),
            DefaultValues.empty().withOptionalHookDefaults(List.of(applicationHook)),
            Optional.of(new MemoryPolicyRef(key("memory.app"), version(2), B)),
            Optional.of(new OutputSchemaRef(key("schema.app"), version(2), B)),
            Optional.of(new EvaluationSuiteRef(key("evaluation.app"), version(2), B)));
    var overrides =
        new AgentOverrides(
            DefaultValues.empty().withTemperature(new BigDecimal("0.6")),
            Optional.of(new MemoryPolicyRef(key("memory.agent"), version(3), A)),
            Optional.of(new OutputSchemaRef(key("schema.agent"), version(3), A)),
            Optional.of(new EvaluationSuiteRef(key("evaluation.agent"), version(3), A)),
            Optional.of(new DefaultProfileRef(key("defaults.agent"), version(3), A)),
            Optional.empty());

    var definition =
        resolver().resolveDefinition(limits(), codeDefaults(), application, overrides, baseline);

    assertEquals(
        "memory.agent", definition.components().memoryPolicyVersion().componentKey().value());
    assertEquals(
        "schema.agent", definition.components().outputSchemaVersion().componentKey().value());
    assertEquals(
        "evaluation.agent",
        definition.components().evaluationSuiteVersion().componentKey().value());
    assertEquals(
        "defaults.agent", definition.components().defaultProfileVersion().componentKey().value());
    assertEquals(ValueSource.AGENT_OVERRIDE, definition.sources().memoryPolicyVersion().source());
    assertEquals(ValueSource.AGENT_OVERRIDE, definition.sources().outputSchemaVersion().source());
    assertEquals(
        ValueSource.AGENT_OVERRIDE, definition.sources().evaluationSuiteVersion().source());
    assertEquals(ValueSource.AGENT_OVERRIDE, definition.sources().defaultProfileVersion().source());
    assertEquals(
        ValueSource.AGENT_OVERRIDE, definition.resolvedConfig().sources().memoryPolicy().source());
    assertEquals(
        ValueSource.APPLICATION_PROFILE,
        definition.sources().hookBindings().get(0).source().source());
    assertEquals(true, definition.components().hookBindings().get(0).enabled());
  }

  @Test
  void resetCommandCoversEveryFrozenComponentVersionPath() {
    var overrides =
        new AgentOverrides(
            DefaultValues.empty()
                .withMaxRunSeconds(10)
                .withMaxToolCalls(2)
                .withMaxInputTokens(100)
                .withMaxOutputTokens(100)
                .withMaxCostUsd(new BigDecimal("1"))
                .withTemperature(new BigDecimal("0.5"))
                .withTopP(new BigDecimal("0.8"))
                .withModelMaxOutputTokens(100)
                .withRetryPolicy(RetryPolicy.SAFE_ONCE),
            Optional.of(new MemoryPolicyRef(key("memory.agent"), version(3), A)),
            Optional.of(new OutputSchemaRef(key("schema.agent"), version(3), A)),
            Optional.of(new EvaluationSuiteRef(key("evaluation.agent"), version(3), A)),
            Optional.of(new DefaultProfileRef(key("defaults.agent"), version(3), A)),
            Optional.empty());

    var reset = new ResetAgentOverrides(EnumSet.allOf(OverridePath.class)).applyTo(overrides);

    assertFalse(reset.values().maxRunSeconds().isPresent());
    assertFalse(reset.values().maxToolCalls().isPresent());
    assertFalse(reset.values().maxInputTokens().isPresent());
    assertFalse(reset.values().maxOutputTokens().isPresent());
    assertFalse(reset.values().maxCostUsd().isPresent());
    assertFalse(reset.values().modelParameters().temperature().isPresent());
    assertFalse(reset.values().modelParameters().topP().isPresent());
    assertFalse(reset.values().modelParameters().maxOutputTokens().isPresent());
    assertFalse(reset.values().retryPolicy().isPresent());
    assertFalse(reset.memoryPolicyVersion().isPresent());
    assertFalse(reset.outputSchemaVersion().isPresent());
    assertFalse(reset.evaluationSuiteVersion().isPresent());
    assertFalse(reset.defaultProfileVersion().isPresent());
  }

  @Test
  void applicationComponentDefaultsWinOverCodeWithComponentProvenance() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.base");
    var application =
        new ApplicationDefaults(
            "fitness",
            baseline.defaultProfileVersion().publishedRef(),
            DefaultValues.empty(),
            Optional.of(new MemoryPolicyRef(key("memory.app"), version(2), B)),
            Optional.of(new OutputSchemaRef(key("schema.app"), version(2), B)),
            Optional.of(new EvaluationSuiteRef(key("evaluation.app"), version(2), B)));

    var definition =
        resolver()
            .resolveDefinition(
                limits(), codeDefaults(), application, AgentOverrides.none(), baseline);

    assertEquals(
        ValueSource.APPLICATION_PROFILE, definition.sources().memoryPolicyVersion().source());
    assertEquals(
        ValueSource.APPLICATION_PROFILE, definition.sources().outputSchemaVersion().source());
    assertEquals(
        ValueSource.APPLICATION_PROFILE, definition.sources().evaluationSuiteVersion().source());
    assertEquals(
        ValueSource.APPLICATION_PROFILE, definition.sources().defaultProfileVersion().source());
  }

  @Test
  void optionalHookDefaultsRejectDuplicateIdentity() {
    var hook = new HookBinding(key("hook.audit"), version(1), true, List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> DefaultValues.empty().withOptionalHookDefaults(List.of(hook, hook)));
  }

  @Test
  void resolvedAggregateRejectsComponentAndProvenanceDrift() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.base");
    var application =
        new ApplicationDefaults(
            "fitness", baseline.defaultProfileVersion().publishedRef(), DefaultValues.empty());
    var resolved =
        resolver()
            .resolveDefinition(
                limits(), codeDefaults(), application, AgentOverrides.none(), baseline);
    var mismatched =
        new AgentComponents(
            baseline.frameworkVersion(),
            new ProviderRef(key("provider.other"), version(1), B),
            baseline.modelBinding(),
            baseline.promptVersion(),
            baseline.toolBindings(),
            baseline.skillBindings(),
            baseline.hookBindings(),
            baseline.memoryPolicyVersion(),
            baseline.outputSchemaVersion(),
            baseline.evaluationSuiteVersion(),
            baseline.defaultProfileVersion());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResolvedAgentDefinition(resolved.resolvedConfig(), mismatched, resolved.sources()));
  }

  @Test
  void resolvedApplicationScopeRejectsInvalidUnicodeAtConstructionBoundary() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.base");
    var application =
        new ApplicationDefaults(
            "fitness", baseline.defaultProfileVersion().publishedRef(), DefaultValues.empty());
    var resolved =
        resolver()
            .resolveDefinition(
                limits(), codeDefaults(), application, AgentOverrides.none(), baseline);
    var config = resolved.resolvedConfig();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResolvedAgentConfig(
                "bad\ud800scope",
                config.runtimeLimits(),
                config.modelParameters(),
                config.retryPolicy(),
                config.sources()));
  }

  public static EffectiveConfigResolver resolver() {
    return new EffectiveConfigResolver();
  }

  public static PlatformLimits limits() {
    return new PlatformLimits(Duration.ofSeconds(45), 8, 8_000, 2_000, new BigDecimal("2.00"), 2);
  }

  public static ComponentDefaults codeDefaults() {
    return new ComponentDefaults(
        Duration.ofSeconds(30),
        6,
        4_000,
        1_000,
        new BigDecimal("1.00"),
        new BigDecimal("0.2"),
        new BigDecimal("0.9"),
        800,
        RetryPolicy.SAFE_ONCE,
        baselineRef("framework.agentscope", 1, A));
  }

  public static AgentComponents components(
      String memory, String output, String evaluation, String defaults) {
    return new AgentComponents(
        new FrameworkRef(key("framework.agentscope"), version(1), A),
        new ProviderRef(key("provider.openai"), version(1), A),
        new ModelBinding(key("model.gpt"), version(1), A),
        new PromptRef(key("prompt.coach"), version(1), A),
        List.of(),
        List.of(),
        List.of(new PublishedHookBinding(key("hook.audit"), version(1), false, A, List.of())),
        new MemoryPolicyRef(key(memory), version(1), A),
        new OutputSchemaRef(key(output), version(1), A),
        new EvaluationSuiteRef(key(evaluation), version(1), A),
        new DefaultProfileRef(key(defaults), version(1), A));
  }

  public static happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef baselineRef(
      String key, int version, String checksum) {
    return new happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef(
        key(key), version(version), checksum);
  }

  public static ComponentKey key(String value) {
    return new ComponentKey(value);
  }

  public static ComponentVersion version(int value) {
    return new ComponentVersion(value);
  }
}
