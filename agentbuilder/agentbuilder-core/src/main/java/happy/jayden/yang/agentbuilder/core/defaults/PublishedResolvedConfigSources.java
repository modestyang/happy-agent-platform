package happy.jayden.yang.agentbuilder.core.defaults;

import java.util.Objects;

public record PublishedResolvedConfigSources(
    EffectiveValueSource runtimeMaxRunSeconds,
    EffectiveValueSource runtimeMaxToolCalls,
    EffectiveValueSource runtimeMaxInputTokens,
    EffectiveValueSource runtimeMaxOutputTokens,
    EffectiveValueSource runtimeMaxCostUsd,
    EffectiveValueSource runtimeConcurrentRuns,
    EffectiveValueSource modelTemperature,
    EffectiveValueSource modelTopP,
    EffectiveValueSource modelMaxOutputTokens,
    EffectiveValueSource retryPolicy,
    EffectiveValueSource memoryPolicy,
    EffectiveValueSource outputSchema,
    EffectiveValueSource defaultProfile) {
  public PublishedResolvedConfigSources {
    Objects.requireNonNull(runtimeMaxRunSeconds, "runtimeMaxRunSeconds");
    Objects.requireNonNull(runtimeMaxToolCalls, "runtimeMaxToolCalls");
    Objects.requireNonNull(runtimeMaxInputTokens, "runtimeMaxInputTokens");
    Objects.requireNonNull(runtimeMaxOutputTokens, "runtimeMaxOutputTokens");
    Objects.requireNonNull(runtimeMaxCostUsd, "runtimeMaxCostUsd");
    Objects.requireNonNull(runtimeConcurrentRuns, "runtimeConcurrentRuns");
    Objects.requireNonNull(modelTemperature, "modelTemperature");
    Objects.requireNonNull(modelTopP, "modelTopP");
    Objects.requireNonNull(modelMaxOutputTokens, "modelMaxOutputTokens");
    Objects.requireNonNull(retryPolicy, "retryPolicy");
    Objects.requireNonNull(memoryPolicy, "memoryPolicy");
    Objects.requireNonNull(outputSchema, "outputSchema");
    Objects.requireNonNull(defaultProfile, "defaultProfile");
  }
}
