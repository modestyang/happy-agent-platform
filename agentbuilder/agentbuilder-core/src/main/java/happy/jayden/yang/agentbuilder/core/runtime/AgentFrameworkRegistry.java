package happy.jayden.yang.agentbuilder.core.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves the one runtime adapter that owns execution for a published framework key. */
public final class AgentFrameworkRegistry {
  private final Map<String, AgentFrameworkAdapter> adapters;

  public AgentFrameworkRegistry(List<AgentFrameworkAdapter> adapters) {
    Objects.requireNonNull(adapters, "adapters");
    var values = new LinkedHashMap<String, AgentFrameworkAdapter>();
    for (var adapter : adapters) {
      Objects.requireNonNull(adapter, "adapters item");
      if (values.putIfAbsent(adapter.key(), adapter) != null) {
        throw new IllegalArgumentException("duplicate framework adapter key: " + adapter.key());
      }
    }
    this.adapters = Map.copyOf(values);
  }

  public AgentFrameworkAdapter required(String frameworkKey) {
    if (frameworkKey == null || frameworkKey.isBlank()) {
      throw new IllegalArgumentException("frameworkKey must not be blank");
    }
    var adapter = adapters.get(frameworkKey.trim());
    if (adapter == null) {
      throw new IllegalArgumentException(
          "no runtime adapter is registered for framework: " + frameworkKey);
    }
    return adapter;
  }
}
