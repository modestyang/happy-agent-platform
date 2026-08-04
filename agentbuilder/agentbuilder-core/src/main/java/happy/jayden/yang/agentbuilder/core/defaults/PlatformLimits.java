package happy.jayden.yang.agentbuilder.core.defaults;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record PlatformLimits(
    Duration timeout,
    int maxToolCalls,
    int maxInputTokens,
    int maxOutputTokens,
    BigDecimal maxCostUsd,
    int concurrentRuns) {
  public PlatformLimits {
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(maxCostUsd, "maxCostUsd");
    if (timeout.compareTo(Duration.ofSeconds(1)) < 0
        || timeout.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("timeout must be between one second and one hour");
    }
    if (maxToolCalls < 0 || maxToolCalls > 1000) {
      throw new IllegalArgumentException("maxToolCalls must be between 0 and 1000");
    }
    if (maxInputTokens < 1 || maxOutputTokens < 1) {
      throw new IllegalArgumentException("token limits must be positive");
    }
    if (maxCostUsd.signum() < 0) {
      throw new IllegalArgumentException("maxCostUsd cannot be negative");
    }
    if (concurrentRuns < 1 || concurrentRuns > 2) {
      throw new IllegalArgumentException("concurrentRuns must be 1 or 2");
    }
  }

  public int maxRunSeconds() {
    return Math.toIntExact(timeout.toSeconds());
  }
}
