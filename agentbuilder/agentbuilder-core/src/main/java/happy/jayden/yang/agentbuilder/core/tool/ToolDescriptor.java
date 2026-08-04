package happy.jayden.yang.agentbuilder.core.tool;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record ToolDescriptor(
    String toolKey,
    int contractVersion,
    String runtimeName,
    String displayName,
    String description,
    String whenToUse,
    String whenNotToUse,
    String applicationKey,
    String group,
    List<String> tags,
    ToolSchema inputSchema,
    ToolSchema outputSchema,
    boolean strictInput,
    ToolSideEffect sideEffect,
    boolean idempotent,
    ToolRiskLevel riskLevel,
    List<String> requiredScopes,
    int defaultTimeoutMs,
    int maxTimeoutMs,
    int defaultMaxCallsPerRun,
    boolean supportsStreaming,
    boolean returnDirect,
    ToolSourceType sourceType,
    String schemaChecksum,
    ToolLifecycleStatus status,
    Optional<ToolVersionReference> replacementTool,
    String registeredBuild) {

  private static final Pattern RUNTIME_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
  private static final Pattern CHECKSUM = Pattern.compile("^[a-f0-9]{64}$");

  public ToolDescriptor {
    toolKey = requireText(toolKey, 2, 160, "toolKey");
    if (contractVersion < 1) {
      throw new IllegalArgumentException("contractVersion must be at least 1");
    }
    runtimeName = requireText(runtimeName, 2, 64, "runtimeName");
    if (!RUNTIME_NAME.matcher(runtimeName).matches()) {
      throw new IllegalArgumentException("runtimeName must be model compatible");
    }
    displayName = requireText(displayName, 1, 160, "displayName");
    description = requireText(description, 1, 2_000, "description");
    whenToUse = requireText(whenToUse, 1, 2_000, "whenToUse");
    whenNotToUse = requireText(whenNotToUse, 1, 2_000, "whenNotToUse");
    applicationKey = requireText(applicationKey, 1, 120, "applicationKey");
    group = requireText(group, 1, 120, "group");
    tags = immutableDistinctText(tags, 64, 64, "tags");
    Objects.requireNonNull(inputSchema, "inputSchema");
    Objects.requireNonNull(outputSchema, "outputSchema");
    if (!strictInput) {
      throw new IllegalArgumentException("strictInput must be true");
    }
    Objects.requireNonNull(sideEffect, "sideEffect");
    Objects.requireNonNull(riskLevel, "riskLevel");
    requiredScopes =
        immutableDistinctText(requiredScopes, Integer.MAX_VALUE, 120, "requiredScopes");
    if (defaultTimeoutMs < 100) {
      throw new IllegalArgumentException("defaultTimeoutMs must be at least 100");
    }
    if (maxTimeoutMs < defaultTimeoutMs) {
      throw new IllegalArgumentException("maxTimeoutMs must be at least defaultTimeoutMs");
    }
    if (defaultMaxCallsPerRun < 1) {
      throw new IllegalArgumentException("defaultMaxCallsPerRun must be at least 1");
    }
    Objects.requireNonNull(sourceType, "sourceType");
    schemaChecksum = requireText(schemaChecksum, 64, 64, "schemaChecksum");
    if (!CHECKSUM.matcher(schemaChecksum).matches()) {
      throw new IllegalArgumentException("schemaChecksum must be a lowercase SHA-256 value");
    }
    Objects.requireNonNull(status, "status");
    replacementTool = Objects.requireNonNull(replacementTool, "replacementTool");
    registeredBuild = requireText(registeredBuild, 1, 160, "registeredBuild");
  }

  private static List<String> immutableDistinctText(
      List<String> values, int maximumItems, int maximumLength, String field) {
    Objects.requireNonNull(values, field);
    if (values.size() > maximumItems) {
      throw new IllegalArgumentException(
          field + " must contain at most " + maximumItems + " items");
    }
    var copy = new LinkedHashSet<String>();
    for (var value : values) {
      copy.add(requireText(value, 1, maximumLength, field));
    }
    if (copy.size() != values.size()) {
      throw new IllegalArgumentException(field + " must contain unique values");
    }
    return List.copyOf(copy);
  }

  private static String requireText(String value, int minimum, int maximum, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank() || value.length() < minimum || value.length() > maximum) {
      throw new IllegalArgumentException(
          field + " length must be between " + minimum + " and " + maximum);
    }
    return value;
  }
}
