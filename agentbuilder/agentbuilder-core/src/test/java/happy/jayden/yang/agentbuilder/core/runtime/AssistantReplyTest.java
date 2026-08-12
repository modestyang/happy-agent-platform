package happy.jayden.yang.agentbuilder.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantReplyTest {

  @Test
  void rebuildsOneCompleteReplyFromOrderedBlockEvents() {
    Instant now = Instant.parse("2026-08-11T00:00:00Z");
    var reply =
        AssistantReply.rebuild(
            List.of(
                RunEvent.replyStarted(1, now, "reply-1", "fitness.coach"),
                RunEvent.blockStarted(
                    2,
                    now,
                    "reply-1",
                    new ResponseBlock.Thinking("thinking-1", "", ResponseBlock.Fidelity.NATIVE)),
                RunEvent.blockDelta(3, now, "reply-1", "thinking-1", "Need a safe plan."),
                RunEvent.blockCompleted(4, now, "reply-1", "thinking-1"),
                RunEvent.blockStarted(
                    5,
                    now,
                    "reply-1",
                    new ResponseBlock.Text("text-1", "", ResponseBlock.Fidelity.NATIVE)),
                RunEvent.blockDelta(6, now, "reply-1", "text-1", "我已为你准备好训练计划。"),
                RunEvent.blockCompleted(7, now, "reply-1", "text-1"),
                RunEvent.blockStarted(
                    8,
                    now,
                    "reply-1",
                    new ResponseBlock.ToolCall(
                        "tool-call-1",
                        "save-plan-1",
                        "fitness.plan.save",
                        "{\"scope\":\"DAY\"}",
                        ResponseBlock.ToolCallState.ASKING,
                        ResponseBlock.Fidelity.NATIVE)),
                RunEvent.blockCompleted(9, now, "reply-1", "tool-call-1"),
                RunEvent.blockStarted(
                    10,
                    now,
                    "reply-1",
                    new ResponseBlock.ToolResult(
                        "tool-result-1",
                        "save-plan-1",
                        "fitness.plan.save",
                        "计划已保存",
                        ResponseBlock.ToolResultState.SUCCESS,
                        ResponseBlock.Fidelity.NATIVE)),
                RunEvent.blockCompleted(11, now, "reply-1", "tool-result-1"),
                RunEvent.replyEnded(
                    12, now, "reply-1", AssistantReply.FinishReason.COMPLETED, null)));

    assertEquals("reply-1", reply.replyId());
    assertEquals(AssistantReply.Status.COMPLETED, reply.status());
    assertEquals("Need a safe plan.", ((ResponseBlock.Thinking) reply.blocks().get(0)).thinking());
    assertEquals("我已为你准备好训练计划。", ((ResponseBlock.Text) reply.blocks().get(1)).text());
    assertEquals("save-plan-1", ((ResponseBlock.ToolCall) reply.blocks().get(2)).toolCallId());
    assertEquals(
        ResponseBlock.ToolResultState.SUCCESS,
        ((ResponseBlock.ToolResult) reply.blocks().get(3)).state());
  }
}
