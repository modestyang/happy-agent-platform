package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record StringValue(String value) implements ConfigValue {
  public StringValue {
    Objects.requireNonNull(value, "value");
  }
}
