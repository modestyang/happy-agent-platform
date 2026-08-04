package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record ComponentDefaults(
    Duration timeout,
    int maxToolCalls,
    int maxInputTokens,
    int maxOutputTokens,
    BigDecimal maxCostUsd,
    BigDecimal temperature,
    BigDecimal topP,
    int modelMaxOutputTokens,
    RetryPolicy retryPolicy,
    PublishedComponentRef sourceVersion) {
  public ComponentDefaults {
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(maxCostUsd, "maxCostUsd");
    Objects.requireNonNull(temperature, "temperature");
    Objects.requireNonNull(topP, "topP");
    Objects.requireNonNull(retryPolicy, "retryPolicy");
    Objects.requireNonNull(sourceVersion, "sourceVersion");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    DefaultValues.empty()
        .withMaxToolCalls(maxToolCalls)
        .withMaxInputTokens(maxInputTokens)
        .withMaxOutputTokens(maxOutputTokens)
        .withMaxCostUsd(maxCostUsd)
        .withTemperature(temperature)
        .withTopP(topP)
        .withModelMaxOutputTokens(modelMaxOutputTokens);
  }
}
