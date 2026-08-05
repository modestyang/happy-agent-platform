package happy.jayden.yang.agentbuilder.service.catalog;

import happy.jayden.yang.agentbuilder.core.defaults.AgentOverrides;
import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig;
import java.util.Objects;

/** Admin-facing view that keeps sparse overrides separate from their resolved provenance. */
public record EffectiveConfigPreview(AgentOverrides overrides, ResolvedAgentConfig resolvedConfig) {
  public EffectiveConfigPreview {
    Objects.requireNonNull(overrides, "overrides");
    Objects.requireNonNull(resolvedConfig, "resolvedConfig");
  }
}
