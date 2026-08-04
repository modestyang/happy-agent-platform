package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.tool.PublishedAgentToolReference;
import happy.jayden.yang.agentbuilder.core.tool.ToolBuildManifest;
import happy.jayden.yang.agentbuilder.core.tool.ToolDeploymentImpact;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

public final class ToolDeploymentPreflight {

  public ToolDeploymentImpact assess(
      ToolBuildManifest manifest, Collection<PublishedAgentToolReference> publishedReferences) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(publishedReferences, "publishedReferences");
    if (publishedReferences.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("publishedReferences must not contain null");
    }
    var available = new HashSet<String>();
    manifest
        .availableTools()
        .forEach(
            entry ->
                available.add(
                    tuple(entry.toolKey(), entry.contractVersion(), entry.schemaChecksum())));

    var missing =
        publishedReferences.stream()
            .filter(
                reference ->
                    !available.contains(
                        tuple(
                            reference.toolKey(),
                            reference.contractVersion(),
                            reference.componentChecksum())))
            .toList();
    var agents =
        missing.stream()
            .map(PublishedAgentToolReference::agentVersionIdentity)
            .distinct()
            .sorted()
            .toList();
    var tools =
        missing.stream()
            .map(PublishedAgentToolReference::toolVersionIdentity)
            .distinct()
            .sorted()
            .toList();
    return new ToolDeploymentImpact(missing.isEmpty(), agents, tools);
  }

  public void verify(
      ToolBuildManifest manifest, Collection<PublishedAgentToolReference> publishedReferences) {
    var impact = assess(manifest, publishedReferences);
    if (!impact.deployable()) {
      throw new IllegalStateException(
          "build "
              + manifest.registeredBuild()
              + " is missing published Tool tuples "
              + impact.missingToolVersions()
              + " referenced by Agent versions "
              + impact.affectedAgentVersions());
    }
  }

  private static String tuple(String toolKey, int contractVersion, String checksum) {
    return toolKey + "@" + contractVersion + "#" + checksum;
  }
}
