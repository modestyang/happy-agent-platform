package happy.jayden.yang.agentbuilder.service.catalog;

import happy.jayden.yang.agentbuilder.core.component.AgentDraft;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.EvaluationSuiteRef;
import happy.jayden.yang.agentbuilder.core.component.MemoryPolicyRef;
import happy.jayden.yang.agentbuilder.core.component.OutputSchemaRef;
import happy.jayden.yang.agentbuilder.core.defaults.AgentOverrides;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationDefaults;
import happy.jayden.yang.agentbuilder.core.defaults.ComponentDefaults;
import happy.jayden.yang.agentbuilder.core.defaults.EffectiveConfigResolver;
import happy.jayden.yang.agentbuilder.core.defaults.PlatformLimits;
import java.util.Objects;

public final class ConfigPreviewService {
  private final EffectiveConfigResolver resolver;
  private final CatalogDefinitions catalog;

  public ConfigPreviewService(EffectiveConfigResolver resolver, CatalogDefinitions catalog) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public ConfigPreviewService() {
    this(new EffectiveConfigResolver(), CatalogDefinitions.empty());
  }

  private EffectiveConfigPreview preview(
      PlatformLimits platformLimits,
      ComponentDefaults componentDefaults,
      ApplicationDefaults applicationDefaults,
      AgentOverrides overrides) {
    return new EffectiveConfigPreview(
        overrides,
        resolver.resolve(platformLimits, componentDefaults, applicationDefaults, overrides));
  }

  public EffectiveConfigPreview preview(
      AgentDraft draft,
      PlatformLimits platformLimits,
      ComponentDefaults componentDefaults,
      ApplicationDefaults applicationDefaults) {
    Objects.requireNonNull(draft, "draft");
    if (!draft.applicationScope().equals(applicationDefaults.applicationScope()))
      throw new IllegalArgumentException(
          "draft applicationScope must match loaded application defaults");
    var selectedDefault =
        draft
            .defaultProfileVersion()
            .map(
                value -> {
                  if (!value
                          .componentKey()
                          .equals(applicationDefaults.defaultProfileVersion().componentKey())
                      || !value
                          .version()
                          .equals(applicationDefaults.defaultProfileVersion().version()))
                    throw new IllegalArgumentException(
                        "draft defaultProfileVersion must match loaded application defaults");
                  return applicationDefaults.defaultProfileVersion();
                });
    return preview(
        platformLimits,
        componentDefaults,
        applicationDefaults,
        new AgentOverrides(
            draft.runtimeOverrides(),
            draft.memoryPolicyVersion().map(this::memory),
            draft.outputSchemaVersion().map(this::output),
            draft.evaluationSuiteVersion().map(this::evaluation),
            selectedDefault,
            java.util.Optional.of(draft.hookBindings())));
  }

  private MemoryPolicyRef memory(
      happy.jayden.yang.agentbuilder.core.component.VersionReference reference) {
    var definition = catalog.memoryPolicies().get(ComponentRef.from(reference));
    if (definition == null)
      throw new IllegalArgumentException("memory policy override is unavailable");
    return new MemoryPolicyRef(definition.metadata());
  }

  private OutputSchemaRef output(
      happy.jayden.yang.agentbuilder.core.component.VersionReference reference) {
    var definition = catalog.outputSchemas().get(ComponentRef.from(reference));
    if (definition == null)
      throw new IllegalArgumentException("output schema override is unavailable");
    return new OutputSchemaRef(definition.metadata());
  }

  private EvaluationSuiteRef evaluation(
      happy.jayden.yang.agentbuilder.core.component.VersionReference reference) {
    var definition = catalog.evaluationSuites().get(ComponentRef.from(reference));
    if (definition == null)
      throw new IllegalArgumentException("evaluation suite override is unavailable");
    return new EvaluationSuiteRef(definition.metadata());
  }
}
