package happy.jayden.yang.agentbuilder.core.runtime;

import java.util.Objects;

/** Result of a pre-run safety or policy hook. */
public record HookDecision(Action action, String message) {
  public HookDecision {
    Objects.requireNonNull(action, "action");
    message = message == null ? "" : message.trim();
    if (action == Action.BLOCK && message.isBlank()) {
      throw new IllegalArgumentException("blocked hook decision requires a safe user message");
    }
    if (action == Action.ALLOW && !message.isBlank()) {
      throw new IllegalArgumentException("allowed hook decision must not carry a user message");
    }
  }

  public static HookDecision allow() {
    return new HookDecision(Action.ALLOW, "");
  }

  public static HookDecision block(String message) {
    return new HookDecision(Action.BLOCK, message);
  }

  public enum Action {
    ALLOW,
    BLOCK
  }
}
