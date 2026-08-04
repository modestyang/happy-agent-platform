package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record EvaluationSuiteRef(ComponentMetadata metadata) implements VersionedComponent {
  public EvaluationSuiteRef {
    Objects.requireNonNull(metadata, "metadata");
  }

  public EvaluationSuiteRef(ComponentKey key, ComponentVersion version, String checksum) {
    this(ComponentMetadata.available(key, version, checksum));
  }
}
