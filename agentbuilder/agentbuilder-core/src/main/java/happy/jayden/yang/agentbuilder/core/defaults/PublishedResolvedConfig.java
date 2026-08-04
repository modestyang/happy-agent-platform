package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.TextValidation;
import java.time.Duration;
import java.util.Objects;

public record PublishedResolvedConfig(
    String applicationScope,
    RuntimeLimits runtimeLimits,
    ModelParameters modelParameters,
    RetryPolicy retryPolicy,
    PublishedResolvedConfigSources sources) {
  public PublishedResolvedConfig {
    TextValidation.requireLength(applicationScope, 1, 120, "applicationScope");
    Objects.requireNonNull(runtimeLimits, "runtimeLimits");
    Objects.requireNonNull(modelParameters, "modelParameters");
    Objects.requireNonNull(retryPolicy, "retryPolicy");
    Objects.requireNonNull(sources, "sources");
    if (applicationScope.isBlank() || applicationScope.length() > 120) {
      throw new IllegalArgumentException("applicationScope must contain 1 to 120 characters");
    }
  }

  public Duration timeout() {
    return Duration.ofSeconds(runtimeLimits.maxRunSeconds());
  }

  public java.math.BigDecimal temperature() {
    return modelParameters.temperature();
  }
}
