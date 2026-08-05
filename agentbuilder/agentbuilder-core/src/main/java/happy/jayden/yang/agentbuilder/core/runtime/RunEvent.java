package happy.jayden.yang.agentbuilder.core.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Ordered event stream emitted during a single framework-neutral agent run. */
public record RunEvent(long sequence, Type type, Instant occurredAt, Map<String, Object> data) {
  public RunEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(occurredAt, "occurredAt");
    data = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
  }

  public enum Type {
    RUN_STARTED,
    MODEL_DELTA,
    TOOL_STARTED,
    TOOL_RESULT,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED
  }
}
