package happy.jayden.yang.agentbuilder.core.tool;

import java.util.List;
import java.util.Objects;

public record ToolDeploymentImpact(
    boolean deployable, List<String> affectedAgentVersions, List<String> missingToolVersions) {
  public ToolDeploymentImpact {
    affectedAgentVersions =
        List.copyOf(Objects.requireNonNull(affectedAgentVersions, "affectedAgentVersions"));
    missingToolVersions =
        List.copyOf(Objects.requireNonNull(missingToolVersions, "missingToolVersions"));
    if (deployable && (!affectedAgentVersions.isEmpty() || !missingToolVersions.isEmpty())) {
      throw new IllegalArgumentException("deployable impact cannot contain missing references");
    }
  }
}
