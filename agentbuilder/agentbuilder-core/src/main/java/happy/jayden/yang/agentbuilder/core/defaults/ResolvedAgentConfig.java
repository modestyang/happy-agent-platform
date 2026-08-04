package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.TextValidation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record ResolvedAgentConfig(
    String applicationScope,
    RuntimeLimits runtimeLimits,
    ModelParameters modelParameters,
    RetryPolicy retryPolicy,
    PublishedResolvedConfigSources sources) {
  public ResolvedAgentConfig {
    TextValidation.requireNonBlankLength(applicationScope, 1, 120, "applicationScope");
    Objects.requireNonNull(runtimeLimits, "runtimeLimits");
    Objects.requireNonNull(modelParameters, "modelParameters");
    Objects.requireNonNull(retryPolicy, "retryPolicy");
    Objects.requireNonNull(sources, "sources");
  }

  public Duration timeout() {
    return Duration.ofSeconds(runtimeLimits.maxRunSeconds());
  }

  public BigDecimal temperature() {
    return modelParameters.temperature();
  }

  public PublishedResolvedConfig publishedConfig() {
    return new PublishedResolvedConfig(
        applicationScope, runtimeLimits, modelParameters, retryPolicy, sources);
  }
}
