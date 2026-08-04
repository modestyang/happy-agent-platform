package happy.jayden.yang.agentbuilder.core.component;

import java.util.List;
import java.util.Objects;

public record HookBinding(
    ComponentKey hookKey, ComponentVersion version, boolean enabled, List<ConfigEntry> config) {
  public HookBinding {
    Objects.requireNonNull(hookKey, "hookKey");
    Objects.requireNonNull(version, "version");
    if (hookKey.value().codePointCount(0, hookKey.value().length()) > 120) {
      throw new IllegalArgumentException("hookKey cannot exceed 120 characters");
    }
    config = ComponentCollections.configEntries(config, "config");
  }

  public HookBinding(ComponentKey hookKey, ComponentVersion version, boolean enabled) {
    this(hookKey, version, enabled, List.of());
  }

  public static PublishedHookBinding published(
      String hookKey, int version, boolean enabled, String componentChecksum) {
    return new PublishedHookBinding(
        new ComponentKey(hookKey),
        new ComponentVersion(version),
        enabled,
        componentChecksum,
        List.of());
  }
}
