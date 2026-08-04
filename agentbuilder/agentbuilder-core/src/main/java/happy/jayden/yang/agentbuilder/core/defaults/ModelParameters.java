package happy.jayden.yang.agentbuilder.core.defaults;

import java.math.BigDecimal;
import java.util.Objects;

public record ModelParameters(BigDecimal temperature, BigDecimal topP, int maxOutputTokens) {
  public ModelParameters {
    Objects.requireNonNull(temperature, "temperature");
    Objects.requireNonNull(topP, "topP");
    new SparseModelParameters(
        java.util.Optional.of(temperature),
        java.util.Optional.of(topP),
        java.util.Optional.of(maxOutputTokens));
  }
}
