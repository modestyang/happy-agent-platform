package happy.jayden.yang.agentbuilder.core.component;

import java.util.List;
import java.util.Objects;

public record PublishedSkillBinding(
    ComponentKey skillKey,
    ComponentVersion version,
    boolean enabled,
    String componentChecksum,
    List<ConfigEntry> applicationConfig) {
  public PublishedSkillBinding {
    Objects.requireNonNull(skillKey, "skillKey");
    Objects.requireNonNull(version, "version");
    ComponentValidation.requireChecksum(componentChecksum);
    applicationConfig = List.copyOf(applicationConfig);
  }
}
