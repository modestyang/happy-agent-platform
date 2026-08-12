package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import happy.jayden.yang.agentbuilder.core.runtime.AssistantReply;
import happy.jayden.yang.agentbuilder.core.runtime.ResponseBlock;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps AgentScope 2 native event semantics to the framework-neutral reply event protocol. */
final class AgentScopeEventMapper {
  private AgentScopeEventMapper() {}

  static List<AgentScopeAdapter.Signal> map(AgentEvent event) {
    Objects.requireNonNull(event, "event");
    if (event instanceof AgentStartEvent started) {
      return List.of(
          AgentScopeAdapter.Signal.typed(
              RunEvent.Type.REPLY_STARTED,
              new RunEvent.ReplyStarted(started.getReplyId(), started.getName())));
    }
    if (event instanceof AgentEndEvent ended) {
      return List.of(
          AgentScopeAdapter.Signal.typed(
              RunEvent.Type.REPLY_ENDED,
              new RunEvent.ReplyEnded(
                  ended.getReplyId(), AssistantReply.FinishReason.COMPLETED, "")));
    }
    if (event instanceof TextBlockStartEvent started) {
      return blockStarted(
          started.getReplyId(),
          new ResponseBlock.Text(started.getBlockId(), "", ResponseBlock.Fidelity.NATIVE));
    }
    if (event instanceof TextBlockDeltaEvent delta) {
      return blockDelta(delta.getReplyId(), delta.getBlockId(), delta.getDelta());
    }
    if (event instanceof TextBlockEndEvent ended) {
      return blockCompleted(ended.getReplyId(), ended.getBlockId());
    }
    if (event instanceof ThinkingBlockStartEvent started) {
      return blockStarted(
          started.getReplyId(),
          new ResponseBlock.Thinking(started.getBlockId(), "", ResponseBlock.Fidelity.NATIVE));
    }
    if (event instanceof ThinkingBlockDeltaEvent delta) {
      return blockDelta(delta.getReplyId(), delta.getBlockId(), delta.getDelta());
    }
    if (event instanceof ThinkingBlockEndEvent ended) {
      return blockCompleted(ended.getReplyId(), ended.getBlockId());
    }
    if (event instanceof ToolCallStartEvent started) {
      return blockStarted(
          started.getReplyId(),
          new ResponseBlock.ToolCall(
              toolCallBlockId(started.getToolCallId()),
              started.getToolCallId(),
              started.getToolCallName(),
              "",
              ResponseBlock.ToolCallState.PENDING,
              ResponseBlock.Fidelity.NATIVE));
    }
    if (event instanceof ToolCallDeltaEvent delta) {
      return blockDelta(
          delta.getReplyId(), toolCallBlockId(delta.getToolCallId()), delta.getDelta());
    }
    if (event instanceof ToolCallEndEvent ended) {
      return blockCompleted(ended.getReplyId(), toolCallBlockId(ended.getToolCallId()));
    }
    if (event instanceof ToolResultStartEvent started) {
      return blockStarted(
          started.getReplyId(),
          new ResponseBlock.ToolResult(
              toolResultBlockId(started.getToolCallId()),
              started.getToolCallId(),
              started.getToolCallName(),
              "",
              ResponseBlock.ToolResultState.RUNNING,
              ResponseBlock.Fidelity.NATIVE));
    }
    if (event instanceof ToolResultTextDeltaEvent delta) {
      return blockDelta(
          delta.getReplyId(), toolResultBlockId(delta.getToolCallId()), delta.getDelta());
    }
    if (event instanceof ToolResultDataDeltaEvent delta) {
      return blockDelta(
          delta.getReplyId(),
          toolResultBlockId(delta.getToolCallId()),
          String.valueOf(delta.getData()));
    }
    if (event instanceof ToolResultEndEvent ended) {
      return List.of(
          AgentScopeAdapter.Signal.typed(
              RunEvent.Type.BLOCK_COMPLETED,
              new RunEvent.BlockCompleted(
                  ended.getReplyId(), toolResultBlockId(ended.getToolCallId()))));
    }
    if (event instanceof RequireUserConfirmEvent required) {
      var toolCalls =
          required.getToolCalls().stream()
              .map(AgentScopeEventMapper::confirmationToolCall)
              .toList();
      return List.of(
          AgentScopeAdapter.Signal.generic(
              RunEvent.Type.CONFIRMATION_REQUIRED,
              Map.of("replyId", required.getReplyId(), "toolCalls", toolCalls)),
          AgentScopeAdapter.Signal.generic(
              RunEvent.Type.RUN_WAITING_APPROVAL,
              Map.of("replyId", required.getReplyId(), "toolCalls", toolCalls)),
          AgentScopeAdapter.Signal.typed(
              RunEvent.Type.REPLY_SUSPENDED,
              new RunEvent.ReplySuspended(required.getReplyId(), "USER_CONFIRMATION")));
    }
    if (event instanceof UserConfirmResultEvent received) {
      return List.of(
          AgentScopeAdapter.Signal.generic(
              RunEvent.Type.CONFIRMATION_RECEIVED,
              Map.of("replyId", received.getReplyId(), "results", received.getConfirmResults())));
    }
    if (event instanceof ExceedMaxItersEvent exceeded) {
      return List.of(
          AgentScopeAdapter.Signal.typed(
              RunEvent.Type.REPLY_ENDED,
              new RunEvent.ReplyEnded(
                  exceeded.getReplyId(), AssistantReply.FinishReason.EXCEED_MAX_ITERS, "")));
    }
    return List.of();
  }

  private static List<AgentScopeAdapter.Signal> blockStarted(String replyId, ResponseBlock block) {
    return List.of(
        AgentScopeAdapter.Signal.typed(
            RunEvent.Type.BLOCK_STARTED, new RunEvent.BlockStarted(replyId, block)));
  }

  private static List<AgentScopeAdapter.Signal> blockDelta(
      String replyId, String blockId, String delta) {
    return List.of(
        AgentScopeAdapter.Signal.typed(
            RunEvent.Type.BLOCK_DELTA, new RunEvent.BlockDelta(replyId, blockId, delta)));
  }

  private static List<AgentScopeAdapter.Signal> blockCompleted(String replyId, String blockId) {
    return List.of(
        AgentScopeAdapter.Signal.typed(
            RunEvent.Type.BLOCK_COMPLETED, new RunEvent.BlockCompleted(replyId, blockId)));
  }

  private static String toolCallBlockId(String toolCallId) {
    return "tool-call-" + toolCallId;
  }

  private static String toolResultBlockId(String toolCallId) {
    return "tool-result-" + toolCallId;
  }

  private static Map<String, Object> confirmationToolCall(ToolUseBlock call) {
    return Map.of(
        "toolCallId",
        call.getId(),
        "toolName",
        call.getName(),
        "arguments",
        Map.copyOf(new LinkedHashMap<>(call.getInput())));
  }
}
