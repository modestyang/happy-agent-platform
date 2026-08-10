package happy.jayden.yang.agentbuilder.service.workbench;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resource-specific DTOs used by the admin workbench APIs. */
public final class AdminResourceDtos {
  private AdminResourceDtos() {}

  public record ProviderCreate(String providerKey, String displayName, String endpoint) {
    public ProviderCreate {
      providerKey = text(providerKey, "providerKey");
      displayName = text(displayName, "displayName");
      endpoint = text(endpoint, "endpoint");
    }
  }

  public record ProviderDefinition(
      String providerKey,
      String displayName,
      String endpoint,
      String protocol,
      String status,
      boolean configured,
      String maskedCredential,
      long revision,
      Instant updatedAt) {
    public ProviderDefinition {
      providerKey = text(providerKey, "providerKey");
      displayName = text(displayName, "displayName");
      endpoint = text(endpoint, "endpoint");
      protocol = text(protocol, "protocol");
      status = text(status, "status");
      maskedCredential = Objects.requireNonNull(maskedCredential, "maskedCredential");
      if (revision < 1) throw new IllegalArgumentException("revision");
      Objects.requireNonNull(updatedAt, "updatedAt");
    }
  }

  public record ProviderUpdate(String displayName, String endpoint, String status) {
    public ProviderUpdate {
      displayName = text(displayName, "displayName");
      endpoint = text(endpoint, "endpoint");
      status = validStatus(status);
    }
  }

  public record ModelCreate(
      String modelKey,
      String providerKey,
      String modelId,
      String displayName,
      String description,
      boolean supportsStreaming,
      boolean supportsToolCalling,
      boolean supportsVision) {
    public ModelCreate {
      modelKey = text(modelKey, "modelKey");
      providerKey = text(providerKey, "providerKey");
      modelId = text(modelId, "modelId");
      displayName = text(displayName, "displayName");
      description = Objects.requireNonNull(description, "description").trim();
    }
  }

  public record ModelDefinition(
      String modelKey,
      String providerKey,
      String modelId,
      String displayName,
      String description,
      boolean supportsStreaming,
      boolean supportsToolCalling,
      boolean supportsVision,
      String status,
      long revision,
      Instant updatedAt) {
    public ModelDefinition {
      modelKey = text(modelKey, "modelKey");
      providerKey = text(providerKey, "providerKey");
      modelId = text(modelId, "modelId");
      displayName = text(displayName, "displayName");
      description = Objects.requireNonNull(description, "description").trim();
      status = text(status, "status");
      if (revision < 1) throw new IllegalArgumentException("revision");
      Objects.requireNonNull(updatedAt, "updatedAt");
    }
  }

  public record ModelUpdate(
      String modelId,
      String displayName,
      String description,
      boolean supportsStreaming,
      boolean supportsToolCalling,
      boolean supportsVision,
      String status) {
    public ModelUpdate {
      modelId = text(modelId, "modelId");
      displayName = text(displayName, "displayName");
      description = Objects.requireNonNull(description, "description").trim();
      status = validStatus(status);
    }
  }

  public record PromptDefinition(
      String promptKey,
      String displayName,
      String description,
      String template,
      String status,
      long revision,
      Instant updatedAt) {}

  public record PromptCreate(
      String promptKey, String displayName, String description, String template) {
    public PromptCreate {
      promptKey = text(promptKey, "promptKey");
      displayName = text(displayName, "displayName");
      description = Objects.requireNonNull(description, "description").trim();
      template = text(template, "template");
    }
  }

  public record PromptUpdate(
      String displayName, String description, String template, String status) {}

  public record SkillDefinition(
      String skillKey,
      String displayName,
      String description,
      String whenToUse,
      String whenNotToUse,
      String content,
      List<String> requiredToolKeys,
      boolean runtimeReady,
      String status,
      long revision,
      Instant updatedAt) {}

  public record SkillCreate(
      String skillKey,
      String displayName,
      String description,
      String whenToUse,
      String whenNotToUse,
      String content,
      List<String> requiredToolKeys) {
    public SkillCreate {
      skillKey = text(skillKey, "skillKey");
      displayName = text(displayName, "displayName");
      description = Objects.requireNonNull(description, "description").trim();
      whenToUse = text(whenToUse, "whenToUse");
      whenNotToUse = Objects.requireNonNull(whenNotToUse, "whenNotToUse").trim();
      content = text(content, "content");
      requiredToolKeys = List.copyOf(Objects.requireNonNull(requiredToolKeys, "requiredToolKeys"));
    }
  }

  public record SkillUpdate(
      String displayName,
      String description,
      String whenToUse,
      String whenNotToUse,
      String content,
      List<String> requiredToolKeys,
      String status) {}

  public record HookDefinition(
      String hookKey,
      String displayName,
      String description,
      String phase,
      boolean mandatory,
      boolean runtimeReady,
      String status,
      long revision,
      Instant updatedAt) {}

  public record HookUpdate(
      String displayName, String description, String phase, boolean mandatory, String status) {}

  public record FrameworkDefinition(
      String frameworkKey,
      String displayName,
      String description,
      Map<String, Object> capabilities,
      String status,
      long revision,
      Instant updatedAt) {}

  public record MemoryDefinition(
      String memoryKey,
      String displayName,
      String description,
      int retentionHours,
      int maxTokens,
      String status,
      long revision,
      Instant updatedAt) {}

  public record ToolDefinition(
      String toolKey,
      int contractVersion,
      String runtimeName,
      String displayName,
      String description,
      String whenToUse,
      String whenNotToUse,
      String sideEffect,
      String riskLevel,
      List<String> requiredScopes,
      Map<String, Object> inputSchema,
      Map<String, Object> outputSchema) {}

  private static String text(String value, String name) {
    var normalized = Objects.requireNonNull(value, name).trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(name);
    return normalized;
  }

  private static String validStatus(String value) {
    var normalized = text(value, "status");
    if (!("ACTIVE".equals(normalized) || "DISABLED".equals(normalized))) {
      throw new IllegalArgumentException("status 只能是 ACTIVE 或 DISABLED");
    }
    return normalized;
  }
}
