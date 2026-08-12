package happy.jayden.yang.agentbuilder.core.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable complete assistant message reconstructed from one reply's ordered event stream. */
public record AssistantReply(
    String replyId,
    String agentName,
    Status status,
    List<ResponseBlock> blocks,
    Instant createdAt,
    Instant finishedAt,
    FinishReason finishReason,
    String errorMessage) {

  public AssistantReply {
    replyId = required(replyId, "replyId");
    agentName = required(agentName, "agentName");
    Objects.requireNonNull(status, "status");
    blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    Objects.requireNonNull(createdAt, "createdAt");
    if (status == Status.RUNNING || status == Status.SUSPENDED) {
      if (finishedAt != null || finishReason != null) {
        throw new IllegalArgumentException("unfinished reply must not have terminal metadata");
      }
    } else if (finishedAt == null || finishReason == null) {
      throw new IllegalArgumentException("finished reply requires terminal metadata");
    }
    errorMessage = errorMessage == null ? "" : errorMessage;
  }

  public static AssistantReply rebuild(List<RunEvent> events) {
    Objects.requireNonNull(events, "events");
    AssistantReply reply = null;
    long previousSequence = 0;
    for (var event : events) {
      if (event.sequence() <= previousSequence) {
        throw new IllegalArgumentException("reply events must be strictly ordered");
      }
      previousSequence = event.sequence();
      reply = apply(reply, event);
    }
    if (reply == null) {
      throw new IllegalArgumentException("reply event stream must start a reply");
    }
    return reply;
  }

  private static AssistantReply apply(AssistantReply reply, RunEvent event) {
    if (event.payload() instanceof RunEvent.ReplyStarted started) {
      if (reply != null) {
        throw new IllegalArgumentException("reply stream can only start once");
      }
      return new AssistantReply(
          started.replyId(),
          started.agentName(),
          Status.RUNNING,
          List.of(),
          event.occurredAt(),
          null,
          null,
          "");
    }
    if (reply == null) {
      throw new IllegalArgumentException("reply event stream must start with ReplyStarted");
    }
    if (event.payload() instanceof RunEvent.BlockStarted started) {
      requireReply(reply, started.replyId());
      var blocks = new ArrayList<>(reply.blocks());
      if (blocks.stream().anyMatch(block -> block.blockId().equals(started.block().blockId()))) {
        throw new IllegalArgumentException("blockId already exists in reply");
      }
      blocks.add(started.block());
      return withBlocks(reply, blocks);
    }
    if (event.payload() instanceof RunEvent.BlockDelta delta) {
      requireReply(reply, delta.replyId());
      var blocks = new ArrayList<>(reply.blocks());
      int index = blockIndex(blocks, delta.blockId());
      blocks.set(index, append(blocks.get(index), delta.delta()));
      return withBlocks(reply, blocks);
    }
    if (event.payload() instanceof RunEvent.BlockCompleted completed) {
      requireReply(reply, completed.replyId());
      blockIndex(reply.blocks(), completed.blockId());
      return reply;
    }
    if (event.payload() instanceof RunEvent.ReplySuspended suspended) {
      requireReply(reply, suspended.replyId());
      return new AssistantReply(
          reply.replyId(),
          reply.agentName(),
          Status.SUSPENDED,
          reply.blocks(),
          reply.createdAt(),
          null,
          null,
          "");
    }
    if (event.payload() instanceof RunEvent.ReplyEnded ended) {
      requireReply(reply, ended.replyId());
      return new AssistantReply(
          reply.replyId(),
          reply.agentName(),
          status(ended.finishReason()),
          reply.blocks(),
          reply.createdAt(),
          event.occurredAt(),
          ended.finishReason(),
          ended.errorMessage());
    }
    return reply;
  }

  private static AssistantReply withBlocks(AssistantReply reply, List<ResponseBlock> blocks) {
    return new AssistantReply(
        reply.replyId(),
        reply.agentName(),
        reply.status(),
        blocks,
        reply.createdAt(),
        reply.finishedAt(),
        reply.finishReason(),
        reply.errorMessage());
  }

  private static ResponseBlock append(ResponseBlock block, String delta) {
    String value = delta == null ? "" : delta;
    if (block instanceof ResponseBlock.Text text) {
      return new ResponseBlock.Text(text.blockId(), text.text() + value, text.fidelity());
    }
    if (block instanceof ResponseBlock.Thinking thinking) {
      return new ResponseBlock.Thinking(
          thinking.blockId(), thinking.thinking() + value, thinking.fidelity());
    }
    if (block instanceof ResponseBlock.ToolCall call) {
      return new ResponseBlock.ToolCall(
          call.blockId(),
          call.toolCallId(),
          call.toolName(),
          call.input() + value,
          call.state(),
          call.fidelity());
    }
    if (block instanceof ResponseBlock.ToolResult result) {
      return new ResponseBlock.ToolResult(
          result.blockId(),
          result.toolCallId(),
          result.toolName(),
          result.output() + value,
          result.state(),
          result.fidelity());
    }
    throw new IllegalArgumentException("block type does not support text delta");
  }

  private static int blockIndex(List<ResponseBlock> blocks, String blockId) {
    for (int index = 0; index < blocks.size(); index++) {
      if (blocks.get(index).blockId().equals(blockId)) {
        return index;
      }
    }
    throw new IllegalArgumentException("blockId is not part of the reply");
  }

  private static void requireReply(AssistantReply reply, String replyId) {
    if (!reply.replyId().equals(replyId)) {
      throw new IllegalArgumentException("event replyId does not match active reply");
    }
  }

  private static Status status(FinishReason reason) {
    return switch (reason) {
      case COMPLETED -> Status.COMPLETED;
      case INTERRUPTED -> Status.INTERRUPTED;
      case EXCEED_MAX_ITERS -> Status.EXCEEDED_MAX_ITERATIONS;
      case ERROR -> Status.FAILED;
    };
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public enum Status {
    RUNNING,
    SUSPENDED,
    COMPLETED,
    INTERRUPTED,
    EXCEEDED_MAX_ITERATIONS,
    FAILED
  }

  public enum FinishReason {
    COMPLETED,
    INTERRUPTED,
    EXCEED_MAX_ITERS,
    ERROR
  }
}
