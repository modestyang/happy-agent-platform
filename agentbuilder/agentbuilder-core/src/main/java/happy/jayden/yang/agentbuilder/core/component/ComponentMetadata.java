package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record ComponentMetadata(
    ComponentKey componentKey,
    ComponentVersion version,
    ComponentStatus status,
    String componentChecksum) {
  public ComponentMetadata {
    Objects.requireNonNull(componentKey, "componentKey");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(status, "status");
    ComponentValidation.requireChecksum(componentChecksum);
  }

  public static ComponentMetadata available(
      ComponentKey componentKey, ComponentVersion version, String componentChecksum) {
    return new ComponentMetadata(
        componentKey, version, ComponentStatus.AVAILABLE, componentChecksum);
  }
}
