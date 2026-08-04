package happy.jayden.yang.agentbuilder.core.tool;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record ToolExecutionContext(
    String userId, String runId, Set<String> grantedScopes, String operationId) {
  public ToolExecutionContext {
    userId = requireText(userId, "userId");
    runId = requireText(runId, "runId");
    grantedScopes =
        Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(grantedScopes, "grantedScopes")));
    operationId = requireText(operationId, "operationId");
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
