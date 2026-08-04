package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.HookBinding;
import happy.jayden.yang.agentbuilder.core.component.VersionReference;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DefaultValues(
    Optional<Integer> maxRunSeconds,
    Optional<Integer> maxToolCalls,
    Optional<Integer> maxInputTokens,
    Optional<Integer> maxOutputTokens,
    Optional<BigDecimal> maxCostUsd,
    SparseModelParameters modelParameters,
    Optional<RetryPolicy> retryPolicy,
    Optional<VersionReference> memoryPolicy,
    List<HookBinding> optionalHookDefaults) {
  public DefaultValues {
    maxRunSeconds = requireOptional(maxRunSeconds, "maxRunSeconds");
    maxToolCalls = requireOptional(maxToolCalls, "maxToolCalls");
    maxInputTokens = requireOptional(maxInputTokens, "maxInputTokens");
    maxOutputTokens = requireOptional(maxOutputTokens, "maxOutputTokens");
    maxCostUsd = requireOptional(maxCostUsd, "maxCostUsd");
    modelParameters = Objects.requireNonNull(modelParameters, "modelParameters");
    retryPolicy = requireOptional(retryPolicy, "retryPolicy");
    memoryPolicy = requireOptional(memoryPolicy, "memoryPolicy");
    optionalHookDefaults = List.copyOf(optionalHookDefaults);
    maxRunSeconds.ifPresent(value -> requireRange(value, 1, 3600, "maxRunSeconds"));
    maxToolCalls.ifPresent(value -> requireRange(value, 0, 1000, "maxToolCalls"));
    maxInputTokens.ifPresent(value -> requireAtLeast(value, 1, "maxInputTokens"));
    maxOutputTokens.ifPresent(value -> requireAtLeast(value, 1, "maxOutputTokens"));
    maxCostUsd.ifPresent(
        value -> {
          if (value.signum() < 0) {
            throw new IllegalArgumentException("maxCostUsd cannot be negative");
          }
        });
  }

  public static DefaultValues empty() {
    return new DefaultValues(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        SparseModelParameters.empty(),
        Optional.empty(),
        Optional.empty(),
        List.of());
  }

  public DefaultValues withTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    return withMaxRunSeconds(Math.toIntExact(timeout.toSeconds()));
  }

  public DefaultValues withMaxRunSeconds(int value) {
    return copy(
        Optional.of(value),
        maxToolCalls,
        maxInputTokens,
        maxOutputTokens,
        maxCostUsd,
        modelParameters,
        retryPolicy);
  }

  public DefaultValues withMaxToolCalls(int value) {
    return copy(
        maxRunSeconds,
        Optional.of(value),
        maxInputTokens,
        maxOutputTokens,
        maxCostUsd,
        modelParameters,
        retryPolicy);
  }

  public DefaultValues withMaxInputTokens(int value) {
    return copy(
        maxRunSeconds,
        maxToolCalls,
        Optional.of(value),
        maxOutputTokens,
        maxCostUsd,
        modelParameters,
        retryPolicy);
  }

  public DefaultValues withMaxOutputTokens(int value) {
    return copy(
        maxRunSeconds,
        maxToolCalls,
        maxInputTokens,
        Optional.of(value),
        maxCostUsd,
        modelParameters,
        retryPolicy);
  }

  public DefaultValues withMaxCostUsd(BigDecimal value) {
    return copy(
        maxRunSeconds,
        maxToolCalls,
        maxInputTokens,
        maxOutputTokens,
        Optional.of(value),
        modelParameters,
        retryPolicy);
  }

  public DefaultValues withTemperature(BigDecimal value) {
    return copy(
        maxRunSeconds,
        maxToolCalls,
        maxInputTokens,
        maxOutputTokens,
        maxCostUsd,
        modelParameters.withTemperature(value),
        retryPolicy);
  }

  public DefaultValues withTopP(BigDecimal value) {
    return copy(
        maxRunSeconds,
        maxToolCalls,
        maxInputTokens,
        maxOutputTokens,
        maxCostUsd,
        modelParameters.withTopP(value),
        retryPolicy);
  }

  public DefaultValues withModelMaxOutputTokens(int value) {
    return copy(
        maxRunSeconds,
        maxToolCalls,
        maxInputTokens,
        maxOutputTokens,
        maxCostUsd,
        modelParameters.withMaxOutputTokens(value),
        retryPolicy);
  }

  public DefaultValues withRetryPolicy(RetryPolicy value) {
    return copy(
        maxRunSeconds,
        maxToolCalls,
        maxInputTokens,
        maxOutputTokens,
        maxCostUsd,
        modelParameters,
        Optional.of(value));
  }

  DefaultValues without(OverridePath path) {
    return switch (path) {
      case RUNTIME_MAX_RUN_SECONDS ->
          copy(
              Optional.empty(),
              maxToolCalls,
              maxInputTokens,
              maxOutputTokens,
              maxCostUsd,
              modelParameters,
              retryPolicy);
      case RUNTIME_MAX_TOOL_CALLS ->
          copy(
              maxRunSeconds,
              Optional.empty(),
              maxInputTokens,
              maxOutputTokens,
              maxCostUsd,
              modelParameters,
              retryPolicy);
      case RUNTIME_MAX_INPUT_TOKENS ->
          copy(
              maxRunSeconds,
              maxToolCalls,
              Optional.empty(),
              maxOutputTokens,
              maxCostUsd,
              modelParameters,
              retryPolicy);
      case RUNTIME_MAX_OUTPUT_TOKENS ->
          copy(
              maxRunSeconds,
              maxToolCalls,
              maxInputTokens,
              Optional.empty(),
              maxCostUsd,
              modelParameters,
              retryPolicy);
      case RUNTIME_MAX_COST_USD ->
          copy(
              maxRunSeconds,
              maxToolCalls,
              maxInputTokens,
              maxOutputTokens,
              Optional.empty(),
              modelParameters,
              retryPolicy);
      case MODEL_TEMPERATURE ->
          copy(
              maxRunSeconds,
              maxToolCalls,
              maxInputTokens,
              maxOutputTokens,
              maxCostUsd,
              modelParameters.withoutTemperature(),
              retryPolicy);
      case MODEL_TOP_P ->
          copy(
              maxRunSeconds,
              maxToolCalls,
              maxInputTokens,
              maxOutputTokens,
              maxCostUsd,
              modelParameters.withoutTopP(),
              retryPolicy);
      case MODEL_MAX_OUTPUT_TOKENS ->
          copy(
              maxRunSeconds,
              maxToolCalls,
              maxInputTokens,
              maxOutputTokens,
              maxCostUsd,
              modelParameters.withoutMaxOutputTokens(),
              retryPolicy);
      case RETRY_POLICY ->
          copy(
              maxRunSeconds,
              maxToolCalls,
              maxInputTokens,
              maxOutputTokens,
              maxCostUsd,
              modelParameters,
              Optional.empty());
    };
  }

  private DefaultValues copy(
      Optional<Integer> maxRunSeconds,
      Optional<Integer> maxToolCalls,
      Optional<Integer> maxInputTokens,
      Optional<Integer> maxOutputTokens,
      Optional<BigDecimal> maxCostUsd,
      SparseModelParameters modelParameters,
      Optional<RetryPolicy> retryPolicy) {
    return new DefaultValues(
        maxRunSeconds,
        maxToolCalls,
        maxInputTokens,
        maxOutputTokens,
        maxCostUsd,
        modelParameters,
        retryPolicy,
        memoryPolicy,
        optionalHookDefaults);
  }

  private static <T> Optional<T> requireOptional(Optional<T> value, String field) {
    return Objects.requireNonNull(value, field);
  }

  private static void requireRange(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }
  }

  private static void requireAtLeast(int value, int minimum, String field) {
    if (value < minimum) {
      throw new IllegalArgumentException(field + " must be at least " + minimum);
    }
  }
}
