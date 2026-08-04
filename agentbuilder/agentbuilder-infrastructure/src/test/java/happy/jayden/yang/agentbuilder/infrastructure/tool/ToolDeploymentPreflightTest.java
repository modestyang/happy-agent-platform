package happy.jayden.yang.agentbuilder.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.tool.PublishedAgentToolReference;
import happy.jayden.yang.agentbuilder.core.tool.ToolBuildManifest;
import happy.jayden.yang.agentbuilder.core.tool.ToolManifestEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolDeploymentPreflightTest {

  private static final String CHECKSUM = "a".repeat(64);

  @Test
  void blocksDeploymentAndReportsImpactWhenPublishedAgentTupleIsAbsent() {
    var manifest =
        new ToolBuildManifest(
            "build-2", List.of(new ToolManifestEntry("fitness.present", 1, CHECKSUM)));
    var required =
        List.of(new PublishedAgentToolReference("coach", 7, "fitness.removed", 3, "b".repeat(64)));

    var impact = new ToolDeploymentPreflight().assess(manifest, required);

    assertFalse(impact.deployable());
    assertEquals(List.of("coach@7"), impact.affectedAgentVersions());
    assertEquals(List.of("fitness.removed@3"), impact.missingToolVersions());
    assertThrows(
        IllegalStateException.class,
        () -> new ToolDeploymentPreflight().verify(manifest, required));
  }

  @Test
  void checksumMismatchDoesNotSatisfyPublishedAgentTuple() {
    var manifest =
        new ToolBuildManifest(
            "build-2", List.of(new ToolManifestEntry("fitness.history", 1, CHECKSUM)));
    var required =
        List.of(new PublishedAgentToolReference("coach", 7, "fitness.history", 1, "b".repeat(64)));

    var impact = new ToolDeploymentPreflight().assess(manifest, required);

    assertFalse(impact.deployable());
    assertEquals(List.of("fitness.history@1"), impact.missingToolVersions());
  }

  @Test
  void acceptsDeploymentWhenEveryPublishedAgentTupleIsAvailable() {
    var manifest =
        new ToolBuildManifest(
            "build-2", List.of(new ToolManifestEntry("fitness.history", 1, CHECKSUM)));
    var required =
        List.of(new PublishedAgentToolReference("coach", 7, "fitness.history", 1, CHECKSUM));

    assertDoesNotThrow(() -> new ToolDeploymentPreflight().verify(manifest, required));
  }
}
