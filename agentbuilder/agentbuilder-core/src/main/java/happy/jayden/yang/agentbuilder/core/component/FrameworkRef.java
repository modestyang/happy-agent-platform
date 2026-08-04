package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record FrameworkRef(ComponentMetadata metadata) implements VersionedComponent {
  public FrameworkRef {
    Objects.requireNonNull(metadata, "metadata");
  }

  public FrameworkRef(ComponentKey key, ComponentVersion version, String checksum) {
    this(ComponentMetadata.available(key, version, checksum));
  }
}
