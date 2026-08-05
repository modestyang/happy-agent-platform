package happy.jayden.yang.agentbuilder.core.component.memory;

import happy.jayden.yang.agentbuilder.core.component.CatalogComponent;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import java.util.Objects;

public record MemoryPolicyVersion(
    ComponentMetadata metadata,
    CatalogMetadata catalogMetadata,
    PolicyType policyType,
    Compression compression,
    int maxTokens,
    int retentionDays,
    int compressionThresholdTokens,
    int compressionWindowMessages,
    MemoryConfigSchema configSchema,
    MemoryConfig defaults)
    implements CatalogComponent {
  public MemoryPolicyVersion {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
    Objects.requireNonNull(policyType, "policyType");
    Objects.requireNonNull(compression, "compression");
    Objects.requireNonNull(configSchema, "configSchema");
    Objects.requireNonNull(defaults, "defaults");
    if (maxTokens < 1 || retentionDays < 0)
      throw new IllegalArgumentException("invalid memory limits");
    if (compression == Compression.NONE
        && (compressionThresholdTokens != 0 || compressionWindowMessages != 0))
      throw new IllegalArgumentException("uncompressed memory cannot have compression settings");
    if (compression != Compression.NONE
        && (compressionThresholdTokens < 1 || compressionWindowMessages < 1))
      throw new IllegalArgumentException("compression requires positive threshold and window");
    if (compressionThresholdTokens > maxTokens)
      throw new IllegalArgumentException("compression threshold cannot exceed maxTokens");
    if (defaults.retrievalEnabled() && !configSchema.allowRetrieval())
      throw new IllegalArgumentException("memory defaults enable retrieval forbidden by schema");
    if (defaults.maxEntries() > configSchema.maxEntries())
      throw new IllegalArgumentException("memory defaults exceed schema maxEntries");
    if (defaults.retrievalLimit() > defaults.maxEntries())
      throw new IllegalArgumentException("retrievalLimit cannot exceed maxEntries");
  }

  public record MemoryConfig(boolean retrievalEnabled, int retrievalLimit, int maxEntries) {
    public MemoryConfig {
      if (retrievalLimit < 0 || maxEntries < 1)
        throw new IllegalArgumentException("invalid memory config");
    }
  }

  public record MemoryConfigSchema(
      boolean allowRetrieval, boolean allowPersistence, int maxEntries) {
    public MemoryConfigSchema {
      if (maxEntries < 1) throw new IllegalArgumentException("schema maxEntries must be positive");
    }
  }

  public enum PolicyType {
    WINDOW,
    SUMMARY,
    VECTOR
  }

  public enum Compression {
    NONE,
    SUMMARY,
    TOKEN_BUDGET
  }
}
