package happy.jayden.yang.agentbuilder.core.defaults;

import java.math.BigDecimal;
import java.util.Objects;

public record RuntimeLimits(
    int maxRunSeconds,
    int maxToolCalls,
    int maxInputTokens,
    int maxOutputTokens,
    BigDecimal maxCostUsd,
    int concurrentRuns) {
  public RuntimeLimits {
    Objects.requireNonNull(maxCostUsd, "maxCostUsd");
    if (maxRunSeconds < 1 || maxRunSeconds > 3600) {
      throw new IllegalArgumentException("maxRunSeconds must be between 1 and 3600");
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
}
