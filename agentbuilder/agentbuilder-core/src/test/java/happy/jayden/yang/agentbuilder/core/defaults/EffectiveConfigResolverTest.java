package happy.jayden.yang.agentbuilder.core.defaults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.component.AgentComponents;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.EvaluationSuiteRef;
import happy.jayden.yang.agentbuilder.core.component.FrameworkRef;
import happy.jayden.yang.agentbuilder.core.component.MemoryPolicyRef;
import happy.jayden.yang.agentbuilder.core.component.ModelBinding;
import happy.jayden.yang.agentbuilder.core.component.OutputSchemaRef;
import happy.jayden.yang.agentbuilder.core.component.PromptRef;
import happy.jayden.yang.agentbuilder.core.component.ProviderRef;
import happy.jayden.yang.agentbuilder.core.component.PublishedHookBinding;
import happy.jayden.yang.agentbuilder.core.component.PublishedSkillBinding;
import happy.jayden.yang.agentbuilder.core.component.PublishedToolBinding;
import happy.jayden.yang.agentbuilder.core.component.SkillBinding;
import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import happy.jayden.yang.agentbuilder.core.version.AgentVersionSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EffectiveConfigResolverTest {

  private static final String A =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String B =
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  @Test
  void platformTimeoutRejectsSubsecondValuesThatCannotSatisfyRuntimeLimits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlatformLimits(Duration.ofMillis(999), 1, 1, 1, BigDecimal.ZERO, 1));
  }

  @Test
  void sparseOverridesResolveAndPublishedSnapshotDoesNotFollowLaterDefaults() {
    var limits =
        new PlatformLimits(Duration.ofSeconds(45), 8, 8_000, 2_000, new BigDecimal("2.00"), 2);
    var codeDefaults =
        new ComponentDefaults(
            Duration.ofSeconds(30),
            6,
            4_000,
            1_000,
            new BigDecimal("1.00"),
            new BigDecimal("0.2"),
            new BigDecimal("0.9"),
            800,
            RetryPolicy.SAFE_ONCE,
            publishedRef("framework.agentscope", 3, A));
    var applicationDefaults =
        new ApplicationDefaults(
            "fitness",
            publishedRef("defaults.fitness", 7, B),
            DefaultValues.empty().withMaxToolCalls(5));
    var overrides = AgentOverrides.onlyTemperature(new BigDecimal("0.6"));

    var resolved =
        new EffectiveConfigResolver().resolve(limits, codeDefaults, applicationDefaults, overrides);

    assertEquals(Duration.ofSeconds(30), resolved.timeout());
    assertEquals(new BigDecimal("0.6"), resolved.temperature());
    assertEquals(ValueSource.AGENT_OVERRIDE, resolved.sources().modelTemperature().source());
    assertEquals(
        ValueSource.APPLICATION_PROFILE, resolved.sources().runtimeMaxToolCalls().source());

    var snapshot =
        AgentVersionSnapshot.publish(resolved, components(List.of(), List.of(), List.of()));
    applicationDefaults.changeTimeout(Duration.ofSeconds(40));

    assertEquals(Duration.ofSeconds(30), snapshot.resolvedConfig().timeout());
  }

  @Test
  void platformSafetyLimitsCannotBeOverridden() {
    var limits =
        new PlatformLimits(Duration.ofSeconds(20), 3, 2_000, 600, new BigDecimal("0.50"), 1);
    var codeDefaults =
        new ComponentDefaults(
            Duration.ofSeconds(10),
            2,
            1_000,
            500,
            new BigDecimal("0.25"),
            new BigDecimal("0.2"),
            new BigDecimal("0.9"),
            400,
            RetryPolicy.NONE,
            publishedRef("framework.agentscope", 3, A));
    var appDefaults =
        new ApplicationDefaults(
            "fitness", publishedRef("defaults.fitness", 7, B), DefaultValues.empty());
    var overrides =
        new AgentOverrides(
            DefaultValues.empty()
                .withTimeout(Duration.ofSeconds(90))
                .withMaxToolCalls(99)
                .withMaxCostUsd(new BigDecimal("9.99")));

    var resolved =
        new EffectiveConfigResolver().resolve(limits, codeDefaults, appDefaults, overrides);

    assertEquals(Duration.ofSeconds(20), resolved.timeout());
    assertEquals(3, resolved.runtimeLimits().maxToolCalls());
    assertEquals(new BigDecimal("0.50"), resolved.runtimeLimits().maxCostUsd());
    assertEquals(ValueSource.PLATFORM_LIMIT, resolved.sources().runtimeMaxRunSeconds().source());
    assertEquals(ValueSource.PLATFORM_LIMIT, resolved.sources().runtimeMaxToolCalls().source());
    assertEquals(ValueSource.PLATFORM_LIMIT, resolved.sources().runtimeMaxCostUsd().source());
  }

  @Test
  void resetIsAnExplicitCommandAndCanonicalChecksumIgnoresBindingInputOrder() {
    var overrides = AgentOverrides.onlyTemperature(new BigDecimal("0.6"));
    var reset = new ResetAgentOverrides(Set.of(OverridePath.MODEL_TEMPERATURE));
    assertFalse(reset.applyTo(overrides).values().modelParameters().temperature().isPresent());

    var resolved = resolvedFixture();
    var toolA = ToolBinding.published("tool.alpha", 1, true, A);
    var toolB = ToolBinding.published("tool.beta", 2, true, B);
    var skillA = SkillBinding.published("skill.alpha", 1, true, A);
    var skillB = SkillBinding.published("skill.beta", 2, false, B);

    var first =
        AgentVersionSnapshot.publish(
            resolved, components(List.of(toolB, toolA), List.of(skillB, skillA), List.of()));
    var second =
        AgentVersionSnapshot.publish(
            resolved, components(List.of(toolA, toolB), List.of(skillA, skillB), List.of()));

    assertEquals(first.canonicalJson(), second.canonicalJson());
    assertEquals(first.checksum(), second.checksum());
    assertFalse(first.canonicalJson().contains(":null"));
    assertNotEquals(
        first.checksum(),
        AgentVersionSnapshot.publish(
                resolved,
                components(
                    List.of(ToolBinding.published("tool.alpha", 1, false, A), toolB),
                    List.of(skillA, skillB),
                    List.of()))
            .checksum());
  }

  private static ResolvedAgentConfig resolvedFixture() {
    return new EffectiveConfigResolver()
        .resolve(
            new PlatformLimits(Duration.ofSeconds(45), 8, 8_000, 2_000, new BigDecimal("2.00"), 2),
            new ComponentDefaults(
                Duration.ofSeconds(30),
                6,
                4_000,
                1_000,
                new BigDecimal("1.00"),
                new BigDecimal("0.2"),
                new BigDecimal("0.9"),
                800,
                RetryPolicy.SAFE_ONCE,
                publishedRef("framework.agentscope", 3, A)),
            new ApplicationDefaults(
                "fitness", publishedRef("defaults.fitness", 7, B), DefaultValues.empty()),
            AgentOverrides.none());
  }

  private static AgentComponents components(
      List<PublishedToolBinding> tools,
      List<PublishedSkillBinding> skills,
      List<PublishedHookBinding> hooks) {
    return new AgentComponents(
        new FrameworkRef(key("framework.agentscope"), version(3), A),
        new ProviderRef(key("provider.openai"), version(4), B),
        new ModelBinding(key("model.gpt"), version(5), A),
        new PromptRef(key("prompt.coach"), version(6), B),
        tools,
        skills,
        hooks,
        new MemoryPolicyRef(key("memory.window"), version(2), A),
        new OutputSchemaRef(key("schema.answer"), version(2), B),
        new EvaluationSuiteRef(key("evaluation.safety"), version(9), A),
        new DefaultProfileRef(key("defaults.fitness"), version(7), B));
  }

  private static happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef publishedRef(
      String key, int version, String checksum) {
    return new happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef(
        key(key), version(version), checksum);
  }

  private static ComponentKey key(String value) {
    return new ComponentKey(value);
  }

  private static ComponentVersion version(int value) {
    return new ComponentVersion(value);
  }
}
