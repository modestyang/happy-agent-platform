package happy.jayden.yang.agentbuilder.core.component;

import java.util.List;

public record StringListValue(List<String> value) implements ConfigValue {
  public StringListValue {
    value = List.copyOf(value);
    if (value.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("string list values cannot contain null");
    }
  }
}
