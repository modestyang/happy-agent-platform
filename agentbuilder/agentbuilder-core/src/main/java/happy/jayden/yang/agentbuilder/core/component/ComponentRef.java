package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

/** Identifies one immutable component version for catalog impact queries. */
public record ComponentRef(ComponentKey componentKey, ComponentVersion version) {
  public ComponentRef {
    Objects.requireNonNull(componentKey, "componentKey");
    Objects.requireNonNull(version, "version");
  }

  public static ComponentRef from(VersionReference reference) {
    return new ComponentRef(reference.componentKey(), reference.version());
  }
}
