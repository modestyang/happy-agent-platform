package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import java.util.Objects;

/** Independently revisioned pointer to one immutable default-profile version. */
public record ActiveDefaultProfile(
    ApplicationKey applicationKey,
    ComponentKey profileKey,
    ComponentVersion version,
    long revision) {
  public ActiveDefaultProfile {
    Objects.requireNonNull(applicationKey, "applicationKey");
    Objects.requireNonNull(profileKey, "profileKey");
    Objects.requireNonNull(version, "version");
    if (revision < 1) throw new IllegalArgumentException("revision must be positive");
  }
}
