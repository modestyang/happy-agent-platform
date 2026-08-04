package happy.jayden.yang.agentbuilder.core.component;

import java.util.List;

public record StringListValue(List<String> value) implements ConfigValue {
  public StringListValue {
    value = List.copyOf(value);
    if (value.size() > 256) {
      throw new IllegalArgumentException("string list cannot contain more than 256 items");
    }
    for (var item : value) {
      TextValidation.requireLength(item, 0, 2_000, "string list item");
    }
  }
}
