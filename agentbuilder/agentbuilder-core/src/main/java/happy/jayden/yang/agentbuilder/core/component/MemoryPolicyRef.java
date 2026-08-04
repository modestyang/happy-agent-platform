package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;

public record MemoryPolicyRef(ComponentMetadata metadata) implements VersionedComponent {
  public MemoryPolicyRef {
    Objects.requireNonNull(metadata, "metadata");
  }

  public MemoryPolicyRef(ComponentKey key, ComponentVersion version, String checksum) {
    this(ComponentMetadata.available(key, version, checksum));
  }
}
