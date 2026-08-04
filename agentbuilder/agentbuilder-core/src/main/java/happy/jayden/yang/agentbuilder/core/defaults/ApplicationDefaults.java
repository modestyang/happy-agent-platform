package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.EvaluationSuiteRef;
import happy.jayden.yang.agentbuilder.core.component.MemoryPolicyRef;
import happy.jayden.yang.agentbuilder.core.component.OutputSchemaRef;
import happy.jayden.yang.agentbuilder.core.component.TextValidation;
import java.util.Objects;
import java.util.Optional;

public final class ApplicationDefaults {
  private final String applicationScope;
  private final DefaultProfileRef defaultProfileVersion;
  private final DefaultValues values;
  private final Optional<MemoryPolicyRef> memoryPolicyVersion;
  private final Optional<OutputSchemaRef> outputSchemaVersion;
  private final Optional<EvaluationSuiteRef> evaluationSuiteVersion;

  public ApplicationDefaults(
      String applicationScope, DefaultProfileRef defaultProfileVersion, DefaultValues values) {
    this(
        applicationScope,
        defaultProfileVersion,
        values,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public ApplicationDefaults(
      String applicationScope,
      DefaultProfileRef defaultProfileVersion,
      DefaultValues values,
      Optional<MemoryPolicyRef> memoryPolicyVersion,
      Optional<OutputSchemaRef> outputSchemaVersion,
      Optional<EvaluationSuiteRef> evaluationSuiteVersion) {
    this.applicationScope = requireScope(applicationScope);
    this.defaultProfileVersion =
        Objects.requireNonNull(defaultProfileVersion, "defaultProfileVersion");
    this.values = Objects.requireNonNull(values, "values");
    this.memoryPolicyVersion = Objects.requireNonNull(memoryPolicyVersion, "memoryPolicyVersion");
    this.outputSchemaVersion = Objects.requireNonNull(outputSchemaVersion, "outputSchemaVersion");
    this.evaluationSuiteVersion =
        Objects.requireNonNull(evaluationSuiteVersion, "evaluationSuiteVersion");
  }

  public String applicationScope() {
    return applicationScope;
  }

  public DefaultProfileRef defaultProfileVersion() {
    return defaultProfileVersion;
  }

  public DefaultValues values() {
    return values;
  }

  public Optional<MemoryPolicyRef> memoryPolicyVersion() {
    return memoryPolicyVersion;
  }

  public Optional<OutputSchemaRef> outputSchemaVersion() {
    return outputSchemaVersion;
  }

  public Optional<EvaluationSuiteRef> evaluationSuiteVersion() {
    return evaluationSuiteVersion;
  }

  private static String requireScope(String value) {
    TextValidation.requireNonBlankLength(value, 1, 120, "applicationScope");
    return value;
  }
}
