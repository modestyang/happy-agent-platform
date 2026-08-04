package happy.jayden.yang.agentbuilder.core.defaults;

import java.util.List;
import java.util.Objects;

public record ResolvedComponentSources(
    EffectiveValueSource frameworkVersion,
    EffectiveValueSource providerVersion,
    EffectiveValueSource modelBinding,
    EffectiveValueSource promptVersion,
    EffectiveValueSource memoryPolicyVersion,
    EffectiveValueSource outputSchemaVersion,
    EffectiveValueSource evaluationSuiteVersion,
    EffectiveValueSource defaultProfileVersion,
    List<ResolvedBindingSource> toolBindings,
    List<ResolvedBindingSource> skillBindings,
    List<ResolvedBindingSource> hookBindings) {
  public ResolvedComponentSources {
    Objects.requireNonNull(frameworkVersion, "frameworkVersion");
    Objects.requireNonNull(providerVersion, "providerVersion");
    Objects.requireNonNull(modelBinding, "modelBinding");
    Objects.requireNonNull(promptVersion, "promptVersion");
    Objects.requireNonNull(memoryPolicyVersion, "memoryPolicyVersion");
    Objects.requireNonNull(outputSchemaVersion, "outputSchemaVersion");
    Objects.requireNonNull(evaluationSuiteVersion, "evaluationSuiteVersion");
    Objects.requireNonNull(defaultProfileVersion, "defaultProfileVersion");
    toolBindings = List.copyOf(toolBindings);
    skillBindings = List.copyOf(skillBindings);
    hookBindings = List.copyOf(hookBindings);
  }
}
