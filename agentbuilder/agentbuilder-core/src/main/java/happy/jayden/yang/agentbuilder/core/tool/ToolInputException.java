package happy.jayden.yang.agentbuilder.core.tool;

/** Marks a model-correctable Tool input or domain validation failure. */
public final class ToolInputException extends RuntimeException {

  public ToolInputException(String message) {
    super(message);
  }

  public ToolInputException(String message, Throwable cause) {
    super(message, cause);
  }
}
