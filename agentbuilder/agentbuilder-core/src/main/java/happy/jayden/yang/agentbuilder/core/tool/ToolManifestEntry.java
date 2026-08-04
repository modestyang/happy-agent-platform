package happy.jayden.yang.agentbuilder.core.tool;

public record ToolManifestEntry(String toolKey, int contractVersion, String schemaChecksum) {
  public ToolManifestEntry {
    toolKey = requireText(toolKey, "toolKey");
    if (contractVersion < 1) {
      throw new IllegalArgumentException("contractVersion must be at least 1");
    }
    schemaChecksum = requireText(schemaChecksum, "schemaChecksum");
    if (!schemaChecksum.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("schemaChecksum must be a lowercase SHA-256 value");
    }
  }

  public String versionIdentity() {
    return toolKey + "@" + contractVersion;
  }

  private static String requireText(String value, String field) {
    return ToolText.require(value, 1, 160, field);
  }
}
