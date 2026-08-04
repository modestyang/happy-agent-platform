package happy.jayden.yang.agentbuilder.core.component;

import java.util.List;
import java.util.Objects;

public record PublishedHookBinding(
    ComponentKey hookKey,
    ComponentVersion version,
    boolean enabled,
    String componentChecksum,
    List<ConfigEntry> config) {
  public PublishedHookBinding {
    Objects.requireNonNull(hookKey, "hookKey");
    Objects.requireNonNull(version, "version");
    ComponentValidation.requireChecksum(componentChecksum);
    config = List.copyOf(config);
  }
}
