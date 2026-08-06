package happy.jayden.yang.agentbuilder.service.workbench;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AdminWorkbenchDtos {
  private AdminWorkbenchDtos() {}

  public record WorkbenchSnapshot(
      OverviewView overview,
      List<AgentDraftView> agents,
      List<ComponentView> components,
      List<ProviderView> providers,
      List<RunView> runs) {
    public WorkbenchSnapshot {
      Objects.requireNonNull(overview, "overview");
      agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
      components = List.copyOf(Objects.requireNonNull(components, "components"));
      providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
      runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
    }
  }

  public record OverviewView(
      int agentCount,
      String platformStatus,
      int availableComponents,
      int configuredProviders,
      int runCount) {
    public OverviewView {
      text(platformStatus, "platformStatus");
      if (agentCount < 0 || availableComponents < 0 || configuredProviders < 0 || runCount < 0)
        throw new IllegalArgumentException("overview counts cannot be negative");
    }
  }

  public record AgentDraftView(
      String agentKey,
      String name,
      String description,
      String status,
      String frameworkKey,
      String providerKey,
      String modelKey,
      String promptKey,
      List<String> toolKeys,
      List<String> skillKeys,
      List<String> hookKeys,
      String memoryKey,
      double temperature,
      int maxToolCalls,
      int publishedVersion,
      long revision,
      Instant updatedAt) {
    public AgentDraftView {
      text(agentKey, "agentKey");
      text(name, "name");
      text(description, "description");
      text(status, "status");
      toolKeys = strings(toolKeys, "toolKeys");
      skillKeys = strings(skillKeys, "skillKeys");
      hookKeys = strings(hookKeys, "hookKeys");
      if (temperature < 0 || temperature > 2) throw new IllegalArgumentException("temperature");
      if (maxToolCalls < 1 || maxToolCalls > 50) throw new IllegalArgumentException("maxToolCalls");
      if (publishedVersion < 0 || revision < 1)
        throw new IllegalArgumentException("invalid draft version");
      Objects.requireNonNull(updatedAt, "updatedAt");
    }
  }

  public record ComponentView(
      String type,
      String componentKey,
      String displayName,
      String description,
      int version,
      String status,
      List<String> tags,
      Map<String, Object> config) {
    public ComponentView {
      text(type, "type");
      text(componentKey, "componentKey");
      text(displayName, "displayName");
      text(description, "description");
      text(status, "status");
      if (version < 1) throw new IllegalArgumentException("version");
      tags = strings(tags, "tags");
      config = Map.copyOf(Objects.requireNonNull(config, "config"));
    }
  }

  public record ProviderView(
      String providerKey,
      String displayName,
      String endpoint,
      boolean configured,
      String maskedCredential,
      String status) {
    public ProviderView {
      text(providerKey, "providerKey");
      text(displayName, "displayName");
      text(endpoint, "endpoint");
      Objects.requireNonNull(maskedCredential, "maskedCredential");
      text(status, "status");
      if (configured && !"••••••••".equals(maskedCredential))
        throw new IllegalArgumentException("configured credentials must remain masked");
      if (!configured && !maskedCredential.isEmpty())
        throw new IllegalArgumentException("unconfigured credentials must be empty");
    }
  }

  public record RunView(
      UUID runId,
      String agentKey,
      int agentVersion,
      String status,
      Instant startedAt,
      Instant completedAt,
      long durationMs,
      int toolCalls,
      List<RunEventView> events) {
    public RunView {
      Objects.requireNonNull(runId, "runId");
      text(agentKey, "agentKey");
      text(status, "status");
      Objects.requireNonNull(startedAt, "startedAt");
      if (agentVersion < 1 || durationMs < 0 || toolCalls < 0)
        throw new IllegalArgumentException("invalid run metrics");
      events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
  }

  public record RunEventView(
      long sequence, String type, String title, String detail, Instant occurredAt) {
    public RunEventView {
      if (sequence < 1) throw new IllegalArgumentException("sequence");
      text(type, "type");
      text(title, "title");
      Objects.requireNonNull(detail, "detail");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  public record ValidationView(boolean valid, List<String> errors, List<String> warnings) {
    public ValidationView {
      errors = stringsAllowEmpty(errors, "errors");
      warnings = stringsAllowEmpty(warnings, "warnings");
      if (valid != errors.isEmpty())
        throw new IllegalArgumentException("valid must reflect the error list");
    }
  }

  public record DraftUpdate(
      String name,
      String description,
      String frameworkKey,
      String providerKey,
      String modelKey,
      String promptKey,
      List<String> toolKeys,
      List<String> skillKeys,
      List<String> hookKeys,
      String memoryKey,
      double temperature,
      int maxToolCalls) {
    public DraftUpdate {
      text(name, "name");
      text(description, "description");
      text(frameworkKey, "frameworkKey");
      text(providerKey, "providerKey");
      text(modelKey, "modelKey");
      text(promptKey, "promptKey");
      text(memoryKey, "memoryKey");
      toolKeys = strings(toolKeys, "toolKeys");
      skillKeys = strings(skillKeys, "skillKeys");
      hookKeys = strings(hookKeys, "hookKeys");
      if (temperature < 0 || temperature > 2) throw new IllegalArgumentException("temperature");
      if (maxToolCalls < 1 || maxToolCalls > 50) throw new IllegalArgumentException("maxToolCalls");
    }
  }

  public record PublicationView(String agentKey, int publishedVersion, Instant publishedAt) {
    public PublicationView {
      text(agentKey, "agentKey");
      if (publishedVersion < 1) throw new IllegalArgumentException("publishedVersion");
      Objects.requireNonNull(publishedAt, "publishedAt");
    }
  }

  private static void text(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " must not be blank");
  }

  private static List<String> strings(List<String> values, String field) {
    var copy = stringsAllowEmpty(values, field);
    copy.forEach(value -> text(value, field));
    return copy;
  }

  private static List<String> stringsAllowEmpty(List<String> values, String field) {
    Objects.requireNonNull(values, field);
    return List.copyOf(values);
  }
}
