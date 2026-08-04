package happy.jayden.yang.agentbuilder.core.tool;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ToolBuildManifest(String registeredBuild, List<ToolManifestEntry> availableTools) {
  public ToolBuildManifest {
    Objects.requireNonNull(registeredBuild, "registeredBuild");
    if (registeredBuild.isBlank()) {
      throw new IllegalArgumentException("registeredBuild must not be blank");
    }
    availableTools = List.copyOf(Objects.requireNonNull(availableTools, "availableTools"));
    var identities = new HashSet<String>();
    for (var tool : availableTools) {
      Objects.requireNonNull(tool, "availableTools item");
      if (!identities.add(tool.versionIdentity())) {
        throw new IllegalArgumentException(
            "build manifest contains duplicate tool version " + tool.versionIdentity());
      }
    }
  }
}
