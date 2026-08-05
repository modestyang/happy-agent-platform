package happy.jayden.yang.agentbuilder.core.component.hook;

import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import java.util.Objects;
import java.util.Set;

public record HookDefinition(
    ComponentMetadata metadata,
    String type,
    Set<Phase> phases,
    int order,
    ConfigSchema configSchema,
    int timeoutMs,
    SideEffect sideEffect,
    FailurePolicy failurePolicy,
    boolean mandatory,
    Set<String> applicationScopes,
    Set<ComponentRef> compatibleFrameworks,
    CatalogMetadata catalogMetadata)
    implements happy.jayden.yang.agentbuilder.core.component.CatalogComponent {
  public HookDefinition {
    Objects.requireNonNull(metadata, "metadata");
    text(type, "type");
    phases = Set.copyOf(Objects.requireNonNull(phases, "phases"));
    if (phases.isEmpty() || order < 0 || timeoutMs < 1)
      throw new IllegalArgumentException("invalid hook lifecycle settings");
    Objects.requireNonNull(configSchema, "configSchema");
    Objects.requireNonNull(sideEffect, "sideEffect");
    Objects.requireNonNull(failurePolicy, "failurePolicy");
    applicationScopes = Set.copyOf(Objects.requireNonNull(applicationScopes, "applicationScopes"));
    compatibleFrameworks =
        Set.copyOf(Objects.requireNonNull(compatibleFrameworks, "compatibleFrameworks"));
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
    if (applicationScopes.isEmpty() || compatibleFrameworks.isEmpty())
      throw new IllegalArgumentException("hook compatibility must be explicit");
  }

  public boolean appliesTo(ComponentRef framework, String applicationScope) {
    return compatibleFrameworks.contains(framework)
        && catalogMetadata.supports(framework)
        && (applicationScopes.contains("*") || applicationScopes.contains(applicationScope));
  }

  public Phase phase() {
    return phases.iterator().next();
  }

  public record ConfigSchema(String jsonSchema) {
    public ConfigSchema {
      text(jsonSchema, "jsonSchema");
    }
  }

  public enum Phase {
    PRE_AGENT,
    PRE_MODEL,
    PRE_TOOL,
    POST_TOOL,
    POST_MODEL,
    POST_AGENT
  }

  public enum SideEffect {
    NONE,
    READ,
    WRITE
  }

  public enum FailurePolicy {
    FAIL_CLOSED,
    FAIL_OPEN,
    CONTINUE
  }

  private static void text(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " must not be blank");
  }
}
