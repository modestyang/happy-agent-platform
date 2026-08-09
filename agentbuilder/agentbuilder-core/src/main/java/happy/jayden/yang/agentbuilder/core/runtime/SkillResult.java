package happy.jayden.yang.agentbuilder.core.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Structured, serializable output supplied by an executable skill. */
public record SkillResult(String key, Map<String, Object> value) {
  public SkillResult {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
    value = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(value, "value")));
  }
}
