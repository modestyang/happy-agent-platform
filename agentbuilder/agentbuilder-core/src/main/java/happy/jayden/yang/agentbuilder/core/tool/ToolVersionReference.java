package happy.jayden.yang.agentbuilder.core.tool;

import java.util.Objects;

public record ToolVersionReference(String toolKey, int contractVersion) {
  public ToolVersionReference {
    toolKey = requireText(toolKey, "toolKey");
    if (contractVersion < 1) {
      throw new IllegalArgumentException("contractVersion must be at least 1");
    }
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
