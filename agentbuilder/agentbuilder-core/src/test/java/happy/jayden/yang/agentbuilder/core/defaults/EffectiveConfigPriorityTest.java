package happy.jayden.yang.agentbuilder.core.defaults;

import static org.junit.jupiter.api.Assertions.assertEquals;

import happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EffectiveConfigPriorityTest {

  private static final String SHA = "a".repeat(64);

  @ParameterizedTest
  @EnumSource(CappedLeaf.class)
  void everyLimitedLeafResolvesCodeThenApplicationThenAgentBeforePlatformCeiling(CappedLeaf leaf) {
    var application =
        new ApplicationDefaults(
            "fitness", ref("defaults.fitness"), set(DefaultValues.empty(), leaf, false));
    var resolver = new EffectiveConfigResolver();

    var fromApplication =
        resolver.resolve(limits(), codeDefaults(), application, AgentOverrides.none());
    assertEquals(ValueSource.APPLICATION_PROFILE, source(fromApplication, leaf).source());
    assertEquals(0, actual(fromApplication, leaf).compareTo(applicationValue(leaf)));

    var fromAgent =
        resolver.resolve(
            limits(),
            codeDefaults(),
            application,
            new AgentOverrides(set(DefaultValues.empty(), leaf, true)));
    assertEquals(ValueSource.AGENT_OVERRIDE, source(fromAgent, leaf).source());
    assertEquals(0, actual(fromAgent, leaf).compareTo(agentValue(leaf)));

    var ceiling =
        resolver.resolve(
            limits(),
            codeDefaults(),
            application,
            new AgentOverrides(setAboveCeiling(DefaultValues.empty(), leaf)));
    assertEquals(ValueSource.PLATFORM_LIMIT, source(ceiling, leaf).source());
    assertEquals(0, actual(ceiling, leaf).compareTo(platformValue(leaf)));
  }

  @ParameterizedTest
  @EnumSource(ModelLeaf.class)
  void everyNonLimitedModelLeafResolvesApplicationThenAgent(ModelLeaf leaf) {
    var application =
        new ApplicationDefaults(
            "fitness", ref("defaults.fitness"), set(DefaultValues.empty(), leaf, false));
    var resolver = new EffectiveConfigResolver();
    var fromApplication =
        resolver.resolve(limits(), codeDefaults(), application, AgentOverrides.none());
    assertEquals(ValueSource.APPLICATION_PROFILE, source(fromApplication, leaf).source());

    var fromAgent =
        resolver.resolve(
            limits(),
            codeDefaults(),
            application,
            new AgentOverrides(set(DefaultValues.empty(), leaf, true)));
    assertEquals(ValueSource.AGENT_OVERRIDE, source(fromAgent, leaf).source());
    assertEquals(expectedAgent(leaf), actual(fromAgent, leaf));
    assertEquals(ValueSource.PLATFORM_LIMIT, fromAgent.sources().runtimeConcurrentRuns().source());
  }

  private static DefaultValues set(DefaultValues values, CappedLeaf leaf, boolean agent) {
    return switch (leaf) {
      case MAX_RUN_SECONDS -> values.withMaxRunSeconds(agent ? 40 : 35);
      case MAX_TOOL_CALLS -> values.withMaxToolCalls(agent ? 7 : 5);
      case MAX_INPUT_TOKENS -> values.withMaxInputTokens(agent ? 7_000 : 6_000);
      case MAX_OUTPUT_TOKENS -> values.withMaxOutputTokens(agent ? 1_900 : 1_500);
      case MAX_COST_USD -> values.withMaxCostUsd(new BigDecimal(agent ? "1.50" : "1.25"));
      case MODEL_MAX_OUTPUT_TOKENS -> values.withModelMaxOutputTokens(agent ? 1_900 : 1_500);
    };
  }

  private static DefaultValues setAboveCeiling(DefaultValues values, CappedLeaf leaf) {
    return switch (leaf) {
      case MAX_RUN_SECONDS -> values.withMaxRunSeconds(60);
      case MAX_TOOL_CALLS -> values.withMaxToolCalls(9);
      case MAX_INPUT_TOKENS -> values.withMaxInputTokens(9_000);
      case MAX_OUTPUT_TOKENS -> values.withMaxOutputTokens(3_000);
      case MAX_COST_USD -> values.withMaxCostUsd(new BigDecimal("3.00"));
      case MODEL_MAX_OUTPUT_TOKENS -> values.withModelMaxOutputTokens(3_000);
    };
  }

  private static DefaultValues set(DefaultValues values, ModelLeaf leaf, boolean agent) {
    return switch (leaf) {
      case TEMPERATURE -> values.withTemperature(new BigDecimal(agent ? "0.7" : "0.5"));
      case TOP_P -> values.withTopP(new BigDecimal(agent ? "0.8" : "0.7"));
      case RETRY_POLICY ->
          values.withRetryPolicy(agent ? RetryPolicy.SAFE_TWICE : RetryPolicy.SAFE_ONCE);
    };
  }

  private static EffectiveValueSource source(ResolvedAgentConfig value, CappedLeaf leaf) {
    return switch (leaf) {
      case MAX_RUN_SECONDS -> value.sources().runtimeMaxRunSeconds();
      case MAX_TOOL_CALLS -> value.sources().runtimeMaxToolCalls();
      case MAX_INPUT_TOKENS -> value.sources().runtimeMaxInputTokens();
      case MAX_OUTPUT_TOKENS -> value.sources().runtimeMaxOutputTokens();
      case MAX_COST_USD -> value.sources().runtimeMaxCostUsd();
      case MODEL_MAX_OUTPUT_TOKENS -> value.sources().modelMaxOutputTokens();
    };
  }

  private static BigDecimal actual(ResolvedAgentConfig value, CappedLeaf leaf) {
    return switch (leaf) {
      case MAX_RUN_SECONDS -> BigDecimal.valueOf(value.runtimeLimits().maxRunSeconds());
      case MAX_TOOL_CALLS -> BigDecimal.valueOf(value.runtimeLimits().maxToolCalls());
      case MAX_INPUT_TOKENS -> BigDecimal.valueOf(value.runtimeLimits().maxInputTokens());
      case MAX_OUTPUT_TOKENS -> BigDecimal.valueOf(value.runtimeLimits().maxOutputTokens());
      case MAX_COST_USD -> value.runtimeLimits().maxCostUsd();
      case MODEL_MAX_OUTPUT_TOKENS -> BigDecimal.valueOf(value.modelParameters().maxOutputTokens());
    };
  }

  private static BigDecimal applicationValue(CappedLeaf leaf) {
    return switch (leaf) {
      case MAX_RUN_SECONDS -> new BigDecimal("35");
      case MAX_TOOL_CALLS -> new BigDecimal("5");
      case MAX_INPUT_TOKENS -> new BigDecimal("6000");
      case MAX_OUTPUT_TOKENS, MODEL_MAX_OUTPUT_TOKENS -> new BigDecimal("1500");
      case MAX_COST_USD -> new BigDecimal("1.25");
    };
  }

  private static BigDecimal agentValue(CappedLeaf leaf) {
    return switch (leaf) {
      case MAX_RUN_SECONDS -> new BigDecimal("40");
      case MAX_TOOL_CALLS -> new BigDecimal("7");
      case MAX_INPUT_TOKENS -> new BigDecimal("7000");
      case MAX_OUTPUT_TOKENS, MODEL_MAX_OUTPUT_TOKENS -> new BigDecimal("1900");
      case MAX_COST_USD -> new BigDecimal("1.50");
    };
  }

  private static BigDecimal platformValue(CappedLeaf leaf) {
    return switch (leaf) {
      case MAX_RUN_SECONDS -> new BigDecimal("45");
      case MAX_TOOL_CALLS -> new BigDecimal("8");
      case MAX_INPUT_TOKENS -> new BigDecimal("8000");
      case MAX_OUTPUT_TOKENS, MODEL_MAX_OUTPUT_TOKENS -> new BigDecimal("2000");
      case MAX_COST_USD -> new BigDecimal("2.00");
    };
  }

  private static EffectiveValueSource source(ResolvedAgentConfig value, ModelLeaf leaf) {
    return switch (leaf) {
      case TEMPERATURE -> value.sources().modelTemperature();
      case TOP_P -> value.sources().modelTopP();
      case RETRY_POLICY -> value.sources().retryPolicy();
    };
  }

  private static Object actual(ResolvedAgentConfig value, ModelLeaf leaf) {
    return switch (leaf) {
      case TEMPERATURE -> value.modelParameters().temperature();
      case TOP_P -> value.modelParameters().topP();
      case RETRY_POLICY -> value.retryPolicy();
    };
  }

  private static Object expectedAgent(ModelLeaf leaf) {
    return switch (leaf) {
      case TEMPERATURE -> new BigDecimal("0.7");
      case TOP_P -> new BigDecimal("0.8");
      case RETRY_POLICY -> RetryPolicy.SAFE_TWICE;
    };
  }

  private static PlatformLimits limits() {
    return new PlatformLimits(Duration.ofSeconds(45), 8, 8_000, 2_000, new BigDecimal("2.00"), 2);
  }

  private static ComponentDefaults codeDefaults() {
    return new ComponentDefaults(
        Duration.ofSeconds(30),
        4,
        4_000,
        1_000,
        new BigDecimal("1.00"),
        new BigDecimal("0.2"),
        new BigDecimal("0.9"),
        800,
        RetryPolicy.NONE,
        ref("framework.agentscope"));
  }

  private static PublishedComponentRef ref(String key) {
    return new PublishedComponentRef(
        new happy.jayden.yang.agentbuilder.core.component.ComponentKey(key),
        new happy.jayden.yang.agentbuilder.core.component.ComponentVersion(1),
        SHA);
  }

  enum CappedLeaf {
    MAX_RUN_SECONDS,
    MAX_TOOL_CALLS,
    MAX_INPUT_TOKENS,
    MAX_OUTPUT_TOKENS,
    MAX_COST_USD,
    MODEL_MAX_OUTPUT_TOKENS
  }

  enum ModelLeaf {
    TEMPERATURE,
    TOP_P,
    RETRY_POLICY
  }
}
