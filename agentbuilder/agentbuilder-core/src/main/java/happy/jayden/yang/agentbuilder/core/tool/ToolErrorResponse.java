package happy.jayden.yang.agentbuilder.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

public record ToolErrorResponse(boolean ok, String code, String message, boolean retryable) {

  private static final int MAX_MESSAGE_LENGTH = 240;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ToolErrorResponse {
    code = Objects.requireNonNull(code, "code");
    message = Objects.requireNonNull(message, "message");
  }

  public static ToolErrorResponse invalidArgument(Throwable error) {
    Objects.requireNonNull(error, "error");
    return new ToolErrorResponse(false, "INVALID_ARGUMENT", safeMessage(error), true);
  }

  public String json() {
    try {
      return MAPPER.writeValueAsString(this);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Tool error response is not serializable", error);
    }
  }

  private static String safeMessage(Throwable error) {
    var raw = error.getMessage();
    if (raw == null || raw.isBlank()) {
      return "工具参数不符合要求，请修正后重试";
    }
    var normalized = raw.replaceAll("\\p{Cc}+", " ").replaceAll("\\s+", " ").trim();
    if (normalized.isEmpty()) {
      return "工具参数不符合要求，请修正后重试";
    }
    return normalized.length() <= MAX_MESSAGE_LENGTH
        ? normalized
        : normalized.substring(0, MAX_MESSAGE_LENGTH);
  }
}
