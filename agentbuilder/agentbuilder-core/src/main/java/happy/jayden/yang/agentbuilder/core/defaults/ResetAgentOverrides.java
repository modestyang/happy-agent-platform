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
    var memory = overrides.memoryPolicyVersion();
    var output = overrides.outputSchemaVersion();
    var evaluation = overrides.evaluationSuiteVersion();
    var defaults = overrides.defaultProfileVersion();
    for (var path : resetPaths) {
      switch (path) {
        case MEMORY_POLICY_VERSION -> memory = java.util.Optional.empty();
        case OUTPUT_SCHEMA_VERSION -> output = java.util.Optional.empty();
        case EVALUATION_SUITE_VERSION -> evaluation = java.util.Optional.empty();
        case DEFAULT_PROFILE_VERSION -> defaults = java.util.Optional.empty();
        default -> values = values.without(path);
      }
    }
    return new AgentOverrides(
        values, memory, output, evaluation, defaults, overrides.hookBindings());
  }
}
