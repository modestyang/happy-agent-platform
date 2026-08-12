package happy.jayden.yang.agentbuilder.core.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Ordered event stream emitted during a single framework-neutral agent run. */
public record RunEvent(long sequence, Type type, Instant occurredAt, Payload payload) {
  public RunEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
  }

  /** Compatibility constructor for existing adapters while they migrate to typed payloads. */
  public RunEvent(long sequence, Type type, Instant occurredAt, Map<String, Object> data) {
    this(sequence, type, occurredAt, new GenericPayload(data));
  }

  public Map<String, Object> data() {
    return payload.data();
  }

  public static RunEvent replyStarted(
      long sequence, Instant occurredAt, String replyId, String agentName) {
    return new RunEvent(
        sequence, Type.REPLY_STARTED, occurredAt, new ReplyStarted(replyId, agentName));
  }

  public static RunEvent replyEnded(
      long sequence,
      Instant occurredAt,
      String replyId,
      AssistantReply.FinishReason finishReason,
      String errorMessage) {
    return new RunEvent(
        sequence,
        Type.REPLY_ENDED,
        occurredAt,
        new ReplyEnded(replyId, finishReason, errorMessage));
  }

  public static RunEvent replySuspended(
      long sequence, Instant occurredAt, String replyId, String reason) {
    return new RunEvent(
        sequence, Type.REPLY_SUSPENDED, occurredAt, new ReplySuspended(replyId, reason));
  }

  public static RunEvent blockStarted(
      long sequence, Instant occurredAt, String replyId, ResponseBlock block) {
    return new RunEvent(sequence, Type.BLOCK_STARTED, occurredAt, new BlockStarted(replyId, block));
  }

  public static RunEvent blockDelta(
      long sequence, Instant occurredAt, String replyId, String blockId, String delta) {
    return new RunEvent(
        sequence, Type.BLOCK_DELTA, occurredAt, new BlockDelta(replyId, blockId, delta));
  }

  public static RunEvent blockCompleted(
      long sequence, Instant occurredAt, String replyId, String blockId) {
    return new RunEvent(
        sequence, Type.BLOCK_COMPLETED, occurredAt, new BlockCompleted(replyId, blockId));
  }

  public enum Type {
    RUN_STARTED,
    RUN_WAITING_APPROVAL,
    MODEL_DELTA,
    TOOL_STARTED,
    TOOL_RESULT,
    TOOL_COMPLETED,
    TOOL_FAILED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED,
    REPLY_STARTED,
    REPLY_SUSPENDED,
    REPLY_ENDED,
    MODEL_CALL_STARTED,
    MODEL_CALL_COMPLETED,
    BLOCK_STARTED,
    BLOCK_DELTA,
    BLOCK_COMPLETED,
    CONTEXT_ASSEMBLED,
    MEMORY_LOADED,
    MEMORY_SAVED,
    SKILL_DISCOVERED,
    SKILL_LOADED,
    SKILL_FAILED,
    HOOK_STARTED,
    HOOK_COMPLETED,
    HOOK_FAILED,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_RECEIVED,
    CAPABILITY_DEGRADED
  }

  public sealed interface Payload
      permits GenericPayload,
          ReplyStarted,
          ReplySuspended,
          ReplyEnded,
          BlockStarted,
          BlockDelta,
          BlockCompleted {
    Map<String, Object> data();
  }

  public record GenericPayload(Map<String, Object> data) implements Payload {
    public GenericPayload {
      data = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
    }
  }

  public record ReplyStarted(String replyId, String agentName) implements Payload {
    public ReplyStarted {
      replyId = required(replyId, "replyId");
      agentName = required(agentName, "agentName");
    }

    @Override
    public Map<String, Object> data() {
      return Map.of("replyId", replyId, "agentName", agentName);
    }
  }

  public record ReplyEnded(
      String replyId, AssistantReply.FinishReason finishReason, String errorMessage)
      implements Payload {
    public ReplyEnded {
      replyId = required(replyId, "replyId");
      Objects.requireNonNull(finishReason, "finishReason");
      errorMessage = errorMessage == null ? "" : errorMessage;
    }

    @Override
    public Map<String, Object> data() {
      return Map.of(
          "replyId", replyId, "finishReason", finishReason.name(), "errorMessage", errorMessage);
    }
  }

  public record ReplySuspended(String replyId, String reason) implements Payload {
    public ReplySuspended {
      replyId = required(replyId, "replyId");
      reason = required(reason, "reason");
    }

    @Override
    public Map<String, Object> data() {
      return Map.of("replyId", replyId, "reason", reason);
    }
  }

  public record BlockStarted(String replyId, ResponseBlock block) implements Payload {
    public BlockStarted {
      replyId = required(replyId, "replyId");
      Objects.requireNonNull(block, "block");
    }

    @Override
    public Map<String, Object> data() {
      var result = new LinkedHashMap<>(ResponseBlock.traceValue(block));
      result.put("replyId", replyId);
      return Map.copyOf(result);
    }
  }

  public record BlockDelta(String replyId, String blockId, String delta) implements Payload {
    public BlockDelta {
      replyId = required(replyId, "replyId");
      blockId = required(blockId, "blockId");
      delta = delta == null ? "" : delta;
    }

    @Override
    public Map<String, Object> data() {
      return Map.of("replyId", replyId, "blockId", blockId, "delta", delta);
    }
  }

  public record BlockCompleted(String replyId, String blockId) implements Payload {
    public BlockCompleted {
      replyId = required(replyId, "replyId");
      blockId = required(blockId, "blockId");
    }

    @Override
    public Map<String, Object> data() {
      return Map.of("replyId", replyId, "blockId", blockId);
    }
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
