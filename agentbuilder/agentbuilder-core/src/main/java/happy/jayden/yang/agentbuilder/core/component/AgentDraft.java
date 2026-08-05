package happy.jayden.yang.agentbuilder.core.component;

import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AgentDraft(
    String agentKey,
    String applicationScope,
    long revision,
    VersionReference frameworkVersion,
    VersionReference providerVersion,
    VersionReference modelBinding,
    VersionReference promptVersion,
    List<ToolBinding> toolBindings,
    List<SkillBinding> skillBindings,
    List<HookBinding> hookBindings,
    Optional<VersionReference> memoryPolicyVersion,
    Optional<VersionReference> outputSchemaVersion,
    Optional<VersionReference> evaluationSuiteVersion,
    Optional<VersionReference> defaultProfileVersion,
    DefaultValues runtimeOverrides,
    Instant updatedAt) {
  public AgentDraft {
    TextValidation.requireLength(agentKey, 2, 120, "agentKey");
    TextValidation.requireNonBlankLength(applicationScope, 1, 120, "applicationScope");
    if (revision < 1) {
      throw new IllegalArgumentException("revision must be at least 1");
    }
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
    memoryPolicyVersion = requireOptional(memoryPolicyVersion, "memoryPolicyVersion");
    outputSchemaVersion = requireOptional(outputSchemaVersion, "outputSchemaVersion");
    evaluationSuiteVersion = requireOptional(evaluationSuiteVersion, "evaluationSuiteVersion");
    defaultProfileVersion = requireOptional(defaultProfileVersion, "defaultProfileVersion");
    Objects.requireNonNull(runtimeOverrides, "runtimeOverrides");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  private static <T> Optional<T> requireOptional(Optional<T> value, String field) {
    return Objects.requireNonNull(value, field);
  }
}
