package happy.jayden.yang.agentbuilder.core.defaults;

import static happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentDefinitionTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.EvaluationSuiteRef;
import happy.jayden.yang.agentbuilder.core.component.HookBinding;
import happy.jayden.yang.agentbuilder.core.component.MemoryPolicyRef;
import happy.jayden.yang.agentbuilder.core.component.OutputSchemaRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultProfileConsistencyTest {

  private static final String B = "b".repeat(64);

  @Test
  void defaultProfileOverrideMustMatchTheProfileWhoseValuesWereLoaded() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.loaded");
    var loaded =
        new ApplicationDefaults(
            "fitness", baseline.defaultProfileVersion(), completeProfileValues());
    var different = new DefaultProfileRef(key("defaults.different"), version(2), B);
    var overrides =
        new AgentOverrides(
            DefaultValues.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(different),
            Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> resolver().resolveDefinition(limits(), codeDefaults(), loaded, overrides, baseline));
  }

  @Test
  void matchingLoadedProfileSuppliesAllRuntimeModelAndRetryValues() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.loaded");
    var profile = baseline.defaultProfileVersion();
    var loaded = new ApplicationDefaults("fitness", profile, completeProfileValues());
    var overrides =
        new AgentOverrides(
            DefaultValues.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(profile),
            Optional.empty());

    var resolved =
        resolver().resolveDefinition(limits(), codeDefaults(), loaded, overrides, baseline);

    assertEquals(25, resolved.resolvedConfig().runtimeLimits().maxRunSeconds());
    assertEquals(3, resolved.resolvedConfig().runtimeLimits().maxToolCalls());
    assertEquals(3_000, resolved.resolvedConfig().runtimeLimits().maxInputTokens());
    assertEquals(900, resolved.resolvedConfig().runtimeLimits().maxOutputTokens());
    assertEquals(new BigDecimal("0.75"), resolved.resolvedConfig().runtimeLimits().maxCostUsd());
    assertEquals(new BigDecimal("0.55"), resolved.resolvedConfig().modelParameters().temperature());
    assertEquals(new BigDecimal("0.85"), resolved.resolvedConfig().modelParameters().topP());
    assertEquals(700, resolved.resolvedConfig().modelParameters().maxOutputTokens());
    assertEquals(RetryPolicy.SAFE_TWICE, resolved.resolvedConfig().retryPolicy());
    assertEquals(
        profile.publishedRef(),
        resolved.resolvedConfig().sources().modelTemperature().sourceVersion().orElseThrow());
    assertEquals(ValueSource.AGENT_OVERRIDE, resolved.sources().defaultProfileVersion().source());
    assertFalse(resolved.sources().defaultProfileVersion().sourceVersion().isPresent());
  }

  @Test
  void publicResolveComputesCodeApplicationAndAgentComponentSources() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.loaded");
    var profile = baseline.defaultProfileVersion();
    var code = new ApplicationDefaults("fitness", profile, DefaultValues.empty());
    var codeResolved = resolver().resolve(limits(), codeDefaults(), code, AgentOverrides.none());
    assertEquals(ValueSource.CODE_DEFAULT, codeResolved.sources().memoryPolicy().source());
    assertEquals(
        codeDefaults().sourceVersion(),
        codeResolved.sources().memoryPolicy().sourceVersion().orElseThrow());
    assertEquals(ValueSource.CODE_DEFAULT, codeResolved.sources().outputSchema().source());
    assertEquals(ValueSource.APPLICATION_PROFILE, codeResolved.sources().defaultProfile().source());
    assertEquals(
        profile.publishedRef(),
        codeResolved.sources().defaultProfile().sourceVersion().orElseThrow());

    var application =
        new ApplicationDefaults(
            "fitness",
            profile,
            DefaultValues.empty(),
            Optional.of(new MemoryPolicyRef(key("memory.app"), version(2), B)),
            Optional.of(new OutputSchemaRef(key("schema.app"), version(2), B)),
            Optional.of(new EvaluationSuiteRef(key("evaluation.app"), version(2), B)));
    var applicationResolved =
        resolver().resolve(limits(), codeDefaults(), application, AgentOverrides.none());
    assertEquals(
        ValueSource.APPLICATION_PROFILE, applicationResolved.sources().memoryPolicy().source());
    assertEquals(
        profile.publishedRef(),
        applicationResolved.sources().memoryPolicy().sourceVersion().orElseThrow());
    assertEquals(
        ValueSource.APPLICATION_PROFILE, applicationResolved.sources().outputSchema().source());

    var agent =
        new AgentOverrides(
            DefaultValues.empty(),
            Optional.of(new MemoryPolicyRef(key("memory.agent"), version(3), B)),
            Optional.of(new OutputSchemaRef(key("schema.agent"), version(3), B)),
            Optional.empty(),
            Optional.of(profile),
            Optional.empty());
    var agentResolved = resolver().resolve(limits(), codeDefaults(), application, agent);
    assertEquals(ValueSource.AGENT_OVERRIDE, agentResolved.sources().memoryPolicy().source());
    assertFalse(agentResolved.sources().memoryPolicy().sourceVersion().isPresent());
    assertEquals(ValueSource.AGENT_OVERRIDE, agentResolved.sources().outputSchema().source());
    assertFalse(agentResolved.sources().outputSchema().sourceVersion().isPresent());
    assertEquals(ValueSource.AGENT_OVERRIDE, agentResolved.sources().defaultProfile().source());
    assertFalse(agentResolved.sources().defaultProfile().sourceVersion().isPresent());
  }

  @Test
  void componentAndHookSourceVersionsDescribeProviderNotSelectedIdentity() {
    var baseline = components("memory.base", "schema.base", "evaluation.base", "defaults.loaded");
    var profile = baseline.defaultProfileVersion();
    var application =
        new ApplicationDefaults(
            "fitness",
            profile,
            DefaultValues.empty()
                .withOptionalHookDefaults(
                    List.of(new HookBinding(key("hook.audit"), version(1), true, List.of()))),
            Optional.empty(),
            Optional.empty(),
            Optional.of(new EvaluationSuiteRef(key("evaluation.app"), version(2), B)));

    var fromApplication =
        resolver()
            .resolveDefinition(
                limits(), codeDefaults(), application, AgentOverrides.none(), baseline);
    assertEquals(
        profile.publishedRef(),
        fromApplication.sources().evaluationSuiteVersion().sourceVersion().orElseThrow());
    assertEquals(
        profile.publishedRef(),
        fromApplication.sources().hookBindings().get(0).source().sourceVersion().orElseThrow());

    var agentOverrides =
        new AgentOverrides(
            DefaultValues.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(new EvaluationSuiteRef(key("evaluation.agent"), version(3), B)),
            Optional.empty(),
            Optional.of(List.of(new HookBinding(key("hook.audit"), version(1), true, List.of()))));
    var fromAgent =
        resolver()
            .resolveDefinition(limits(), codeDefaults(), application, agentOverrides, baseline);
    assertFalse(fromAgent.sources().evaluationSuiteVersion().sourceVersion().isPresent());
    assertFalse(fromAgent.sources().hookBindings().get(0).source().sourceVersion().isPresent());
    assertFalse(fromAgent.sources().frameworkVersion().sourceVersion().isPresent());
  }

  private static DefaultValues completeProfileValues() {
    return DefaultValues.empty()
        .withMaxRunSeconds(25)
        .withMaxToolCalls(3)
        .withMaxInputTokens(3_000)
        .withMaxOutputTokens(900)
        .withMaxCostUsd(new BigDecimal("0.75"))
        .withTemperature(new BigDecimal("0.55"))
        .withTopP(new BigDecimal("0.85"))
        .withModelMaxOutputTokens(700)
        .withRetryPolicy(RetryPolicy.SAFE_TWICE);
  }
}
