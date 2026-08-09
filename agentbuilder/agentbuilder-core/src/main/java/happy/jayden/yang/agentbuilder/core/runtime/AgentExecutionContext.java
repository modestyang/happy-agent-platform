package happy.jayden.yang.agentbuilder.core.runtime;

import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Framework-neutral identity, message and authorized tool surface for one execution. */
public record AgentExecutionContext(
    String agentKey,
    String runId,
    String userId,
    String message,
    Set<String> authorizedToolKeys,
    ToolExecutionContext toolExecutionContext,
    AgentToolPort toolPort) {
  public AgentExecutionContext {
    agentKey = text(agentKey, "agentKey");
    runId = text(runId, "runId");
    userId = text(userId, "userId");
    message = text(message, "message");
    authorizedToolKeys =
        Set.copyOf(Objects.requireNonNull(authorizedToolKeys, "authorizedToolKeys"));
    Objects.requireNonNull(toolExecutionContext, "toolExecutionContext");
    Objects.requireNonNull(toolPort, "toolPort");
    if (!runId.equals(toolExecutionContext.runId())
        || !userId.equals(toolExecutionContext.userId())) {
      throw new IllegalArgumentException(
          "toolExecutionContext must match the agent execution identity");
    }
  }

  public Object invokeTool(String toolKey, Map<String, Object> input) throws Exception {
    String normalized = text(toolKey, "toolKey");
    if (!authorizedToolKeys.contains(normalized)) {
      throw new SecurityException("skill attempted to invoke an unbound Tool: " + normalized);
    }
    return toolPort.invoke(
        normalized, Map.copyOf(new LinkedHashMap<>(input)), toolExecutionContext);
  }

  private static String text(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }
}
