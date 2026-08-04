package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.EvaluationSuiteRef;
import happy.jayden.yang.agentbuilder.core.component.HookBinding;
import happy.jayden.yang.agentbuilder.core.component.MemoryPolicyRef;
import happy.jayden.yang.agentbuilder.core.component.OutputSchemaRef;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AgentOverrides(
    DefaultValues values,
    Optional<MemoryPolicyRef> memoryPolicyVersion,
    Optional<OutputSchemaRef> outputSchemaVersion,
    Optional<EvaluationSuiteRef> evaluationSuiteVersion,
    Optional<DefaultProfileRef> defaultProfileVersion,
    Optional<List<HookBinding>> hookBindings) {
  public AgentOverrides {
    Objects.requireNonNull(values, "values");
    memoryPolicyVersion = Objects.requireNonNull(memoryPolicyVersion, "memoryPolicyVersion");
    outputSchemaVersion = Objects.requireNonNull(outputSchemaVersion, "outputSchemaVersion");
    evaluationSuiteVersion =
        Objects.requireNonNull(evaluationSuiteVersion, "evaluationSuiteVersion");
    defaultProfileVersion = Objects.requireNonNull(defaultProfileVersion, "defaultProfileVersion");
    hookBindings = Objects.requireNonNull(hookBindings, "hookBindings").map(AgentOverrides::hooks);
  }

  public AgentOverrides(DefaultValues values) {
    this(
        values,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public static AgentOverrides none() {
    return new AgentOverrides(DefaultValues.empty());
  }

  public static AgentOverrides onlyTemperature(BigDecimal value) {
    return new AgentOverrides(DefaultValues.empty().withTemperature(value));
  }

  private static List<HookBinding> hooks(List<HookBinding> values) {
    var copy = List.copyOf(values);
    if (copy.size() > 100) {
      throw new IllegalArgumentException("hookBindings cannot contain more than 100 items");
    }
    var identities = new HashSet<String>();
    for (var hook : copy) {
      var identity = hook.hookKey().value() + "\u0000" + hook.version().value();
      if (!identities.add(identity)) {
        throw new IllegalArgumentException("hookBindings contains duplicate identity");
      }
    }
    return copy;
  }
}
