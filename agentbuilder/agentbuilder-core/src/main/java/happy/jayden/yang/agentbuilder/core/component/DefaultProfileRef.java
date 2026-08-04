package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record DefaultProfileRef(ComponentMetadata metadata) implements VersionedComponent {
  public DefaultProfileRef {
    Objects.requireNonNull(metadata, "metadata");
  }

  public DefaultProfileRef(ComponentKey key, ComponentVersion version, String checksum) {
    this(ComponentMetadata.available(key, version, checksum));
  }
}
