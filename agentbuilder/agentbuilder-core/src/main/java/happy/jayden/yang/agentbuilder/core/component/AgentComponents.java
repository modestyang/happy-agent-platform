package happy.jayden.yang.agentbuilder.core.component;

import java.util.List;
import java.util.Objects;

public record AgentComponents(
    FrameworkRef frameworkVersion,
    ProviderRef providerVersion,
    ModelBinding modelBinding,
    PromptRef promptVersion,
    List<PublishedToolBinding> toolBindings,
    List<PublishedSkillBinding> skillBindings,
    List<PublishedHookBinding> hookBindings,
    MemoryPolicyRef memoryPolicyVersion,
    OutputSchemaRef outputSchemaVersion,
    EvaluationSuiteRef evaluationSuiteVersion,
    DefaultProfileRef defaultProfileVersion) {
  public AgentComponents {
    Objects.requireNonNull(frameworkVersion, "frameworkVersion");
    Objects.requireNonNull(providerVersion, "providerVersion");
    Objects.requireNonNull(modelBinding, "modelBinding");
    Objects.requireNonNull(promptVersion, "promptVersion");
    toolBindings = List.copyOf(toolBindings);
    skillBindings = List.copyOf(skillBindings);
    hookBindings = List.copyOf(hookBindings);
    Objects.requireNonNull(memoryPolicyVersion, "memoryPolicyVersion");
    Objects.requireNonNull(outputSchemaVersion, "outputSchemaVersion");
    Objects.requireNonNull(evaluationSuiteVersion, "evaluationSuiteVersion");
    Objects.requireNonNull(defaultProfileVersion, "defaultProfileVersion");
  }
}
