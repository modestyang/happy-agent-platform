package happy.jayden.yang.agentbuilder.core.tool;

import java.util.Objects;

public record PublishedAgentToolReference(
    String agentKey,
    int agentVersion,
    String toolKey,
    int contractVersion,
    String componentChecksum) {
  public PublishedAgentToolReference {
    agentKey = requireText(agentKey, "agentKey");
    if (agentVersion < 1) {
      throw new IllegalArgumentException("agentVersion must be at least 1");
    }
    toolKey = requireText(toolKey, "toolKey");
    if (contractVersion < 1) {
      throw new IllegalArgumentException("contractVersion must be at least 1");
    }
    componentChecksum = requireText(componentChecksum, "componentChecksum");
    if (!componentChecksum.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("componentChecksum must be a lowercase SHA-256 value");
    }
  }

  public String agentVersionIdentity() {
    return agentKey + "@" + agentVersion;
  }

  public String toolVersionIdentity() {
    return toolKey + "@" + contractVersion;
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
