package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import java.util.Objects;

public record ResolvedBindingSource(
    ComponentKey componentKey, ComponentVersion version, EffectiveValueSource source) {
  public ResolvedBindingSource {
    Objects.requireNonNull(componentKey, "componentKey");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(source, "source");
  }
}
