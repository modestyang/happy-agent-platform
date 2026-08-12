package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import happy.jayden.yang.agentbuilder.core.runtime.ResponseBlock;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AgentScopeEventMapperTest {

  @Test
  void exposesHarnessConfirmationAsAReplySuspension() {
    var signals =
        AgentScopeEventMapper.map(
            new RequireUserConfirmEvent(
                "reply-1",
                List.of(
                    ToolUseBlock.builder()
                        .id("call-1")
                        .name("save_plan")
                        .input(java.util.Map.of("scope", "WEEK"))
                        .build())));

    assertEquals(
        List.of(
            RunEvent.Type.CONFIRMATION_REQUIRED,
            RunEvent.Type.RUN_WAITING_APPROVAL,
            RunEvent.Type.REPLY_SUSPENDED),
        signals.stream().map(signal -> signal.toEvent(new AtomicLong()).type()).toList());
    var confirmation = signals.get(0).toEvent(new AtomicLong());
    assertEquals(
        List.of(
            java.util.Map.of(
                "toolCallId",
                "call-1",
                "toolName",
                "save_plan",
                "arguments",
                java.util.Map.of("scope", "WEEK"))),
        confirmation.data().get("toolCalls"));
  }

  @Test
  void preservesNativeBlocksAndReplyLifecycleFromHarnessEvents() {
    var sequence = new AtomicLong();
    var events =
        List.of(
            event(sequence, new AgentStartEvent("session-1", "reply-1", "fitness-coach")),
            event(sequence, new ThinkingBlockStartEvent("reply-1", "thinking-1")),
            event(sequence, new ThinkingBlockDeltaEvent("reply-1", "thinking-1", "checking")),
            event(sequence, new TextBlockStartEvent("reply-1", "text-1")),
            event(sequence, new TextBlockDeltaEvent("reply-1", "text-1", "Here is your plan")),
            event(sequence, new TextBlockEndEvent("reply-1", "text-1")),
            event(sequence, new ToolCallStartEvent("reply-1", "call-1", "save_plan")),
            event(sequence, new ToolCallEndEvent("reply-1", "call-1", "save_plan")),
            event(sequence, new ToolResultStartEvent("reply-1", "call-1", "save_plan")),
            event(
                sequence,
                new ToolResultEndEvent("reply-1", "call-1", "save_plan", ToolResultState.SUCCESS)),
            event(sequence, new AgentEndEvent("reply-1")));

    assertEquals(
        List.of(
            RunEvent.Type.REPLY_STARTED,
            RunEvent.Type.BLOCK_STARTED,
            RunEvent.Type.BLOCK_DELTA,
            RunEvent.Type.BLOCK_STARTED,
            RunEvent.Type.BLOCK_DELTA,
            RunEvent.Type.BLOCK_COMPLETED,
            RunEvent.Type.BLOCK_STARTED,
            RunEvent.Type.BLOCK_COMPLETED,
            RunEvent.Type.BLOCK_STARTED,
            RunEvent.Type.BLOCK_COMPLETED,
            RunEvent.Type.REPLY_ENDED),
        events.stream().map(RunEvent::type).toList());
    var thinking = (RunEvent.BlockStarted) events.get(1).payload();
    assertEquals(ResponseBlock.BlockType.THINKING, thinking.block().type());
    var toolCall = (RunEvent.BlockStarted) events.get(6).payload();
    assertEquals(ResponseBlock.BlockType.TOOL_CALL, toolCall.block().type());
    assertEquals("save_plan", ((ResponseBlock.ToolCall) toolCall.block()).toolName());
    var toolResult = (RunEvent.BlockStarted) events.get(8).payload();
    assertEquals(ResponseBlock.BlockType.TOOL_RESULT, toolResult.block().type());
    assertEquals("call-1", ((ResponseBlock.ToolResult) toolResult.block()).toolCallId());
  }

  private static RunEvent event(AtomicLong sequence, io.agentscope.core.event.AgentEvent source) {
    return AgentScopeEventMapper.map(source).get(0).toEvent(sequence);
  }
}
