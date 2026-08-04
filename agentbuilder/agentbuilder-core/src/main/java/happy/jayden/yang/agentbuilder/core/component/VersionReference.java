package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record VersionReference(ComponentKey componentKey, ComponentVersion version) {
  public VersionReference {
    Objects.requireNonNull(componentKey, "componentKey");
    Objects.requireNonNull(version, "version");
  }
}
