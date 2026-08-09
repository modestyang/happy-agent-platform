package happy.jayden.yang.agentbuilder.core.runtime;

import java.util.Objects;

/** Safe terminal result visible to post-run hooks, without model/provider internals. */
public record AgentRunResult(Status status, String output) {
  public AgentRunResult {
    Objects.requireNonNull(status, "status");
    output = output == null ? "" : output;
  }

  public enum Status {
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED
  }
}
