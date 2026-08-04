package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.AgentComponents;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record ResolvedAgentDefinition(
    ResolvedAgentConfig resolvedConfig,
    AgentComponents components,
    ResolvedComponentSources sources) {
  public ResolvedAgentDefinition {
    Objects.requireNonNull(resolvedConfig, "resolvedConfig");
    Objects.requireNonNull(components, "components");
    Objects.requireNonNull(sources, "sources");
    requireBindingIdentities(
        components.toolBindings(),
        sources.toolBindings(),
        item -> identity(item.toolKey().value(), item.contractVersion().value()),
        "toolBindings");
    requireBindingIdentities(
        components.skillBindings(),
        sources.skillBindings(),
        item -> identity(item.skillKey().value(), item.version().value()),
        "skillBindings");
    requireBindingIdentities(
        components.hookBindings(),
        sources.hookBindings(),
        item -> identity(item.hookKey().value(), item.version().value()),
        "hookBindings");
    if (!sources.memoryPolicyVersion().equals(resolvedConfig.sources().memoryPolicy())
        || !sources.outputSchemaVersion().equals(resolvedConfig.sources().outputSchema())
        || !sources.defaultProfileVersion().equals(resolvedConfig.sources().defaultProfile())) {
      throw new IllegalArgumentException(
          "resolved component provenance must match PublishedResolvedConfigSources");
    }
  }

  private static <T> void requireBindingIdentities(
      List<T> bindings,
      List<ResolvedBindingSource> bindingSources,
      Function<T, String> identity,
      String field) {
    var expected = new HashSet<String>();
    for (var binding : bindings) {
      expected.add(identity.apply(binding));
    }
    var actual = new HashSet<String>();
    for (var source : bindingSources) {
      actual.add(identity(source.componentKey().value(), source.version().value()));
    }
    if (expected.size() != bindings.size()
        || actual.size() != bindingSources.size()
        || !expected.equals(actual)) {
      throw new IllegalArgumentException(field + " must match resolved binding provenance");
    }
  }

  private static String identity(String key, int version) {
    return key + "\u0000" + version;
  }
}
