package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record ModelBinding(ComponentMetadata metadata) implements VersionedComponent {
  public ModelBinding {
    Objects.requireNonNull(metadata, "metadata");
  }

  public ModelBinding(ComponentKey key, ComponentVersion version, String checksum) {
    this(ComponentMetadata.available(key, version, checksum));
  }
}
