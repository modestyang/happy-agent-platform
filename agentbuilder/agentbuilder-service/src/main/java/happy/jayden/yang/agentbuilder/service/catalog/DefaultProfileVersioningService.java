package happy.jayden.yang.agentbuilder.service.catalog;

import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationDefaults;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import java.util.Objects;

/** Creates a new profile identity; published profile values are never mutated in place. */
public final class DefaultProfileVersioningService {
  public ApplicationDefaults createNext(
      ApplicationDefaults current,
      ComponentVersion expectedCurrentVersion,
      DefaultValues replacement,
      String checksum) {
    Objects.requireNonNull(expectedCurrentVersion, "expectedCurrentVersion");
    if (!current.defaultProfileVersion().version().equals(expectedCurrentVersion))
      throw new IllegalArgumentException("default profile version is stale");
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(replacement, "replacement");
    var old = current.defaultProfileVersion().metadata();
    var next =
        new DefaultProfileRef(
            new ComponentMetadata(
                old.componentKey(),
                new ComponentVersion(old.version().value() + 1),
                old.status(),
                checksum));
    return new ApplicationDefaults(
        current.applicationScope(),
        next,
        replacement,
        current.memoryPolicyVersion(),
        current.outputSchemaVersion(),
        current.evaluationSuiteVersion());
  }
}
