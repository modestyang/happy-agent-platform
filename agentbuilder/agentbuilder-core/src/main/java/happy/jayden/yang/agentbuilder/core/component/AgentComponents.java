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
    toolBindings =
        ComponentCollections.bindings(
            toolBindings,
            100,
            item -> ComponentCollections.identity(item.toolKey(), item.contractVersion()),
            "toolBindings");
    skillBindings =
        ComponentCollections.bindings(
            skillBindings,
            100,
            item -> ComponentCollections.identity(item.skillKey(), item.version()),
            "skillBindings");
    hookBindings =
        ComponentCollections.bindings(
            hookBindings,
            100,
            item -> ComponentCollections.identity(item.hookKey(), item.version()),
            "hookBindings");
    Objects.requireNonNull(memoryPolicyVersion, "memoryPolicyVersion");
    Objects.requireNonNull(outputSchemaVersion, "outputSchemaVersion");
    Objects.requireNonNull(evaluationSuiteVersion, "evaluationSuiteVersion");
    Objects.requireNonNull(defaultProfileVersion, "defaultProfileVersion");
  }
}
