package happy.jayden.yang.agentbuilder.core.runtime;

import java.util.Objects;

/** A safe, user-visible failure description that never exposes framework exception types. */
public record RunFailure(RunFailureCode code, String message, boolean retryable) {
  public RunFailure {
    Objects.requireNonNull(code, "code");
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
  }
}
