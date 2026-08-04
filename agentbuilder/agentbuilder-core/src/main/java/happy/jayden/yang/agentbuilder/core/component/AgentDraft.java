package happy.jayden.yang.agentbuilder.core.component;

import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AgentDraft(
    String agentKey,
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
    Objects.requireNonNull(agentKey, "agentKey");
    if (!ComponentValidation.KEY.matcher(agentKey).matches() || agentKey.length() > 120) {
      throw new IllegalArgumentException("agentKey must satisfy the frozen AgentDraft contract");
    }
    if (revision < 1) {
      throw new IllegalArgumentException("revision must be at least 1");
    }
    Objects.requireNonNull(frameworkVersion, "frameworkVersion");
    Objects.requireNonNull(providerVersion, "providerVersion");
    Objects.requireNonNull(modelBinding, "modelBinding");
    Objects.requireNonNull(promptVersion, "promptVersion");
    toolBindings = List.copyOf(toolBindings);
    skillBindings = List.copyOf(skillBindings);
    hookBindings = List.copyOf(hookBindings);
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
