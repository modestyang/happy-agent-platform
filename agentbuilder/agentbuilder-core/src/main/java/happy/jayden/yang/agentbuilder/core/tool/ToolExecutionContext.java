package happy.jayden.yang.agentbuilder.core.tool;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record ToolExecutionContext(
    String userId, String runId, Set<String> grantedScopes, String operationId) {
  public ToolExecutionContext {
    userId = requireText(userId, "userId");
    runId = requireText(runId, "runId");
    var scopes = new LinkedHashSet<String>();
    for (var scope : Objects.requireNonNull(grantedScopes, "grantedScopes")) {
      scopes.add(ToolText.require(scope, 1, 120, "grantedScopes item"));
    }
    grantedScopes = Set.copyOf(scopes);
    operationId = requireText(operationId, "operationId");
  }

  private static String requireText(String value, String field) {
    return ToolText.require(value, 1, 160, field);
  }
}
