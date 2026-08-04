package happy.jayden.yang.agentbuilder.core.component;

import java.math.BigDecimal;
import java.util.Objects;

public record NumberValue(BigDecimal value) implements ConfigValue {
  public NumberValue {
    Objects.requireNonNull(value, "value");
  }
}
