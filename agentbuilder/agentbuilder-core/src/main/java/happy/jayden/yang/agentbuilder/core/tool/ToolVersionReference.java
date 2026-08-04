package happy.jayden.yang.agentbuilder.core.tool;

public record ToolVersionReference(String toolKey, int contractVersion) {
  public ToolVersionReference {
    toolKey = requireText(toolKey, "toolKey");
    if (contractVersion < 1) {
      throw new IllegalArgumentException("contractVersion must be at least 1");
    }
  }

  private static String requireText(String value, String field) {
    return ToolText.require(value, 1, 160, field);
  }
}
