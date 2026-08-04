package happy.jayden.yang.agentbuilder.core.version;

import happy.jayden.yang.agentbuilder.core.component.AgentComponents;
import happy.jayden.yang.agentbuilder.core.defaults.PublishedResolvedConfig;
import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

public final class AgentVersionSnapshot {
  private final ResolvedAgentDefinition resolvedDefinition;
  private final String canonicalJson;
  private final String checksum;

  private AgentVersionSnapshot(
      ResolvedAgentDefinition resolvedDefinition, String canonicalJson, String checksum) {
    this.resolvedDefinition = Objects.requireNonNull(resolvedDefinition, "resolvedDefinition");
    this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
    this.checksum = Objects.requireNonNull(checksum, "checksum");
  }

  public static AgentVersionSnapshot publish(ResolvedAgentDefinition resolvedDefinition) {
    Objects.requireNonNull(resolvedDefinition, "resolvedDefinition");
    var canonical = SnapshotCanonicalizer.canonicalize(resolvedDefinition);
    return new AgentVersionSnapshot(
        resolvedDefinition, canonical, SnapshotChecksum.sha256(canonical));
  }

  public static AgentVersionSnapshot rehydrate(
      ResolvedAgentDefinition resolvedDefinition,
      String storedCanonicalJson,
      String storedChecksum) {
    Objects.requireNonNull(resolvedDefinition, "resolvedDefinition");
    Objects.requireNonNull(storedCanonicalJson, "storedCanonicalJson");
    SnapshotChecksum.requireChecksum(storedChecksum);
    var canonical = SnapshotCanonicalizer.canonicalize(resolvedDefinition);
    var calculated = SnapshotChecksum.sha256(canonical);
    if (!canonical.equals(storedCanonicalJson)
        || !MessageDigest.isEqual(
            calculated.getBytes(StandardCharsets.US_ASCII),
            storedChecksum.getBytes(StandardCharsets.US_ASCII))) {
      throw new IllegalArgumentException(
          "stored snapshot canonical JSON or checksum is inconsistent");
    }
    return new AgentVersionSnapshot(resolvedDefinition, canonical, calculated);
  }

  public ResolvedAgentDefinition resolvedDefinition() {
    return resolvedDefinition;
  }

  public PublishedResolvedConfig resolvedConfig() {
    return resolvedDefinition.resolvedConfig().publishedConfig();
  }

  public AgentComponents components() {
    return resolvedDefinition.components();
  }

  public String canonicalJson() {
    return canonicalJson;
  }

  public String checksum() {
    return checksum;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AgentVersionSnapshot that)) {
      return false;
    }
    return resolvedDefinition.equals(that.resolvedDefinition)
        && canonicalJson.equals(that.canonicalJson)
        && checksum.equals(that.checksum);
  }

  @Override
  public int hashCode() {
    return Objects.hash(resolvedDefinition, canonicalJson, checksum);
  }
}
