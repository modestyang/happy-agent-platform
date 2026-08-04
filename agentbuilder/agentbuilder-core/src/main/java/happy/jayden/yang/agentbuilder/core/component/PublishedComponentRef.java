package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record PublishedComponentRef(
    ComponentKey componentKey, ComponentVersion version, String componentChecksum) {
  public PublishedComponentRef {
    Objects.requireNonNull(componentKey, "componentKey");
    Objects.requireNonNull(version, "version");
    ComponentValidation.requireChecksum(componentChecksum);
  }
}
