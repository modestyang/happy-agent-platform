package happy.jayden.yang.agentbuilder.core.runtime;

import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig;
import happy.jayden.yang.agentbuilder.core.tool.ResolvedTool;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fully resolved, framework-neutral inputs for one agent run. */
public record RunRequest(
    String runId,
    String userId,
    String input,
    ResolvedAgentConfig resolvedConfig,
    ModelEndpoint model,
    List<ResolvedTool> tools,
    List<Skill> skills,
    List<Hook> hooks,
    Memory memory,
    ToolExecutionContext toolExecutionContext) {
  public RunRequest {
    runId = text(runId, "runId");
    userId = text(userId, "userId");
    input = text(input, "input");
    Objects.requireNonNull(resolvedConfig, "resolvedConfig");
    Objects.requireNonNull(model, "model");
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    hooks = orderedHooks(hooks);
    Objects.requireNonNull(memory, "memory");
    Objects.requireNonNull(toolExecutionContext, "toolExecutionContext");
    if (!runId.equals(toolExecutionContext.runId())
        || !userId.equals(toolExecutionContext.userId())) {
      throw new IllegalArgumentException("trusted tool context must match the run identity");
    }
  }

  public record ModelEndpoint(URI baseUri, String modelName, ModelCredential credential) {
    public ModelEndpoint {
      Objects.requireNonNull(baseUri, "baseUri");
      modelName = text(modelName, "modelName");
      Objects.requireNonNull(credential, "credential");
    }
  }

  /** Opaque credential carrier that only exposes a short-lived, zeroed character copy. */
  public static final class ModelCredential {
    private final char[] value;

    public ModelCredential(char[] value) {
      Objects.requireNonNull(value, "value");
      if (value.length == 0) {
        throw new IllegalArgumentException("credential must not be empty");
      }
      this.value = value.clone();
    }

    public <T> T use(SecretFunction<T> function) {
      Objects.requireNonNull(function, "function");
      var scopedCopy = value.clone();
      try {
        return function.apply(scopedCopy);
      } finally {
        Arrays.fill(scopedCopy, '\0');
      }
    }

    /** Safe Jackson-visible representation. */
    public String getRedacted() {
      return "[REDACTED]";
    }

    @Override
    public String toString() {
      return "[REDACTED]";
    }
  }

  @FunctionalInterface
  public interface SecretFunction<T> {
    T apply(char[] secret);
  }

  public record Skill(
      String key,
      String description,
      String markdown,
      Map<String, String> resources,
      Set<String> alwaysIncludedResources,
      Set<String> onDemandResources) {
    public Skill {
      key = text(key, "key");
      description = text(description, "description");
      markdown = text(markdown, "markdown");
      resources = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(resources, "resources")));
      alwaysIncludedResources =
          Set.copyOf(Objects.requireNonNull(alwaysIncludedResources, "alwaysIncludedResources"));
      onDemandResources =
          Set.copyOf(Objects.requireNonNull(onDemandResources, "onDemandResources"));
      if (!resources.keySet().containsAll(alwaysIncludedResources)
          || !resources.keySet().containsAll(onDemandResources)) {
        throw new IllegalArgumentException("skill disclosure resources must be declared");
      }
    }
  }

  public record Hook(
      String key,
      HookDefinition.Phase phase,
      int order,
      boolean mandatory,
      HookDefinition.FailurePolicy failurePolicy,
      HookAction action) {
    public Hook {
      key = text(key, "key");
      Objects.requireNonNull(phase, "phase");
      if (order < 0) {
        throw new IllegalArgumentException("order must not be negative");
      }
      Objects.requireNonNull(failurePolicy, "failurePolicy");
      if (mandatory && failurePolicy != HookDefinition.FailurePolicy.FAIL_CLOSED) {
        throw new IllegalArgumentException("mandatory hooks must fail closed");
      }
      Objects.requireNonNull(action, "action");
    }
  }

  @FunctionalInterface
  public interface HookAction {
    void execute(HookContext context) throws Exception;
  }

  public record HookContext(String runId, String userId, String input, HookDefinition.Phase phase) {
    public HookContext {
      runId = text(runId, "runId");
      userId = text(userId, "userId");
      input = text(input, "input");
      Objects.requireNonNull(phase, "phase");
    }
  }

  public record Memory(List<String> entries, int maxTokens) {
    public Memory {
      entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
      if (maxTokens < 1) {
        throw new IllegalArgumentException("maxTokens must be positive");
      }
    }
  }

  private static List<Hook> orderedHooks(List<Hook> value) {
    var result = new ArrayList<>(Objects.requireNonNull(value, "hooks"));
    result.sort(
        Comparator.comparing(Hook::phase).thenComparingInt(Hook::order).thenComparing(Hook::key));
    return List.copyOf(result);
  }

  private static String text(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
