package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record OutputSchemaRef(ComponentMetadata metadata) implements VersionedComponent {
  public OutputSchemaRef {
    Objects.requireNonNull(metadata, "metadata");
  }

  public OutputSchemaRef(ComponentKey key, ComponentVersion version, String checksum) {
    this(ComponentMetadata.available(key, version, checksum));
  }
}
