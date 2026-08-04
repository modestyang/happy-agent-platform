package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record ProviderRef(ComponentMetadata metadata) implements VersionedComponent {
  public ProviderRef {
    Objects.requireNonNull(metadata, "metadata");
  }

  public ProviderRef(ComponentKey key, ComponentVersion version, String checksum) {
    this(ComponentMetadata.available(key, version, checksum));
  }
}
