package happy.jayden.yang.agentbuilder.core.runtime;

import java.util.Objects;
import java.util.Optional;

/** Terminal state emitted by an adapter without leaking its native result object. */
public record RunResult(Status status, String text, Optional<RunFailure> failure) {
  public RunResult {
    Objects.requireNonNull(status, "status");
    text = text == null ? "" : text;
    failure = Objects.requireNonNull(failure, "failure");
    if (status == Status.FAILED && failure.isEmpty()) {
      throw new IllegalArgumentException("failed result requires a failure");
    }
    if (status != Status.FAILED && failure.isPresent()) {
      throw new IllegalArgumentException("only failed result may contain a failure");
    }
  }

  public static RunResult completed(String text) {
    return new RunResult(Status.COMPLETED, text, Optional.empty());
  }

  public static RunResult cancelled(String text) {
    return new RunResult(Status.CANCELLED, text, Optional.empty());
  }

  public static RunResult failed(RunFailure failure) {
    return new RunResult(Status.FAILED, "", Optional.of(failure));
  }

  public enum Status {
    COMPLETED,
    FAILED,
    CANCELLED
  }
}
