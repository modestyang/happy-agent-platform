package happy.jayden.yang.agentbuilder.core.component;

import java.util.List;
import java.util.Objects;

public record SkillBinding(
    ComponentKey skillKey,
    ComponentVersion version,
    boolean enabled,
    List<ConfigEntry> applicationConfig) {
  public SkillBinding {
    Objects.requireNonNull(skillKey, "skillKey");
    Objects.requireNonNull(version, "version");
    applicationConfig = List.copyOf(applicationConfig);
  }

  public SkillBinding(ComponentKey skillKey, ComponentVersion version, boolean enabled) {
    this(skillKey, version, enabled, List.of());
  }

  public static PublishedSkillBinding published(
      String skillKey, int version, boolean enabled, String componentChecksum) {
    return new PublishedSkillBinding(
        new ComponentKey(skillKey),
        new ComponentVersion(version),
        enabled,
        componentChecksum,
        List.of());
  }
}
