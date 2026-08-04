package happy.jayden.yang.agentbuilder.core.tool;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ToolSchema(Map<String, Object> document) {
  public ToolSchema {
    Objects.requireNonNull(document, "document");
    ToolSchemaValidator.validate(document);
    document = immutableMap(document);
  }

  private static Map<String, Object> immutableMap(Map<?, ?> source) {
    var copy = new LinkedHashMap<String, Object>();
    source.forEach(
        (key, value) -> {
          if (!(key instanceof String stringKey) || stringKey.isBlank()) {
            throw new IllegalArgumentException("schema keys must be non-blank strings");
          }
          copy.put(stringKey, immutableValue(value));
        });
    return Collections.unmodifiableMap(copy);
  }

  private static Object immutableValue(Object value) {
    Objects.requireNonNull(value, "schema value");
    if (value instanceof Map<?, ?> map) {
      return immutableMap(map);
    }
    if (value instanceof List<?> list) {
      var copy = new ArrayList<>();
      list.forEach(item -> copy.add(immutableValue(item)));
      return Collections.unmodifiableList(copy);
    }
    if (value instanceof String
        || value instanceof Boolean
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Double
        || value instanceof BigInteger
        || value instanceof BigDecimal) {
      return value;
    }
    throw new IllegalArgumentException(
        "schema contains unsupported value type: " + value.getClass().getName());
  }
}
