package happy.jayden.yang.agentbuilder.service.catalog;

import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.evaluation.EvaluationSuiteVersion;
import happy.jayden.yang.agentbuilder.core.component.framework.FrameworkAdapterDefinition;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.component.memory.MemoryPolicyVersion;
import happy.jayden.yang.agentbuilder.core.component.model.ModelDefinition;
import happy.jayden.yang.agentbuilder.core.component.output.OutputSchemaVersion;
import happy.jayden.yang.agentbuilder.core.component.prompt.PromptVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderVersion;
import happy.jayden.yang.agentbuilder.core.component.skill.SkillDefinition;
import java.util.Map;
import java.util.Objects;

/** Immutable catalog view used to validate an agent draft before publishing it. */
public record CatalogDefinitions(
    Map<ComponentRef, SkillDefinition> skills,
    Map<ComponentRef, HookDefinition> hooks,
    Map<ComponentRef, ProviderVersion> providers,
    Map<ComponentRef, ModelDefinition> models,
    Map<ComponentRef, MemoryPolicyVersion> memoryPolicies,
    Map<ComponentRef, PromptVersion> prompts,
    Map<ComponentRef, OutputSchemaVersion> outputSchemas,
    Map<ComponentRef, EvaluationSuiteVersion> evaluationSuites,
    Map<ComponentRef, FrameworkAdapterDefinition> frameworks) {
  public CatalogDefinitions {
    skills = Map.copyOf(Objects.requireNonNull(skills, "skills"));
    hooks = Map.copyOf(Objects.requireNonNull(hooks, "hooks"));
    providers = Map.copyOf(Objects.requireNonNull(providers, "providers"));
    models = Map.copyOf(Objects.requireNonNull(models, "models"));
    memoryPolicies = Map.copyOf(Objects.requireNonNull(memoryPolicies, "memoryPolicies"));
    prompts = Map.copyOf(Objects.requireNonNull(prompts, "prompts"));
    outputSchemas = Map.copyOf(Objects.requireNonNull(outputSchemas, "outputSchemas"));
    evaluationSuites = Map.copyOf(Objects.requireNonNull(evaluationSuites, "evaluationSuites"));
    frameworks = Map.copyOf(Objects.requireNonNull(frameworks, "frameworks"));
    validate(skills);
    validate(hooks);
    validate(providers);
    validate(models);
    validate(memoryPolicies);
    validate(prompts);
    validate(outputSchemas);
    validate(evaluationSuites);
    validate(frameworks);
  }

  private static <T extends happy.jayden.yang.agentbuilder.core.component.CatalogComponent>
      void validate(Map<ComponentRef, T> definitions) {
    definitions.forEach(
        (key, value) -> {
          if (!key.componentKey().equals(value.metadata().componentKey())
              || !key.version().equals(value.metadata().version()))
            throw new IllegalArgumentException("catalog map key must match definition metadata");
        });
  }

  public static CatalogDefinitions empty() {
    return new CatalogDefinitions(
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }
}
