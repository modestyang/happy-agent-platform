package happy.jayden.yang.agentbuilder.core.defaults;

import java.math.BigDecimal;
import java.util.Objects;

public record AgentOverrides(DefaultValues values) {
  public AgentOverrides {
    Objects.requireNonNull(values, "values");
  }

  public static AgentOverrides none() {
    return new AgentOverrides(DefaultValues.empty());
  }

  public static AgentOverrides onlyTemperature(BigDecimal value) {
    return new AgentOverrides(DefaultValues.empty().withTemperature(value));
  }
}
