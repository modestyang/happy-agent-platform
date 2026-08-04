package happy.jayden.yang.agentbuilder.core.defaults;

import java.util.Objects;
import java.util.Set;

public record ResetAgentOverrides(Set<OverridePath> resetPaths) {
  public ResetAgentOverrides {
    resetPaths = Set.copyOf(resetPaths);
    if (resetPaths.isEmpty()) {
      throw new IllegalArgumentException("resetPaths cannot be empty");
    }
  }

  public AgentOverrides applyTo(AgentOverrides overrides) {
    Objects.requireNonNull(overrides, "overrides");
    var values = overrides.values();
    for (var path : resetPaths) {
      values = values.without(path);
    }
    return new AgentOverrides(values);
  }
}
