package happy.jayden.yang.agentbuilder.framework.adapter.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import happy.jayden.yang.agentbuilder.core.runtime.ResponseBlock;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

class SpringAiAlibabaNodeOutputTranslatorTest {

  @Test
  void translatesModelThinkingTextAndToolCallBlocksFromStreamingNodeOutput() {
    var events = new ArrayList<RunEvent>();
    var translator =
        new SpringAiAlibabaNodeOutputTranslator(
            "reply-1", "fitness", new AtomicLong(), events::add);
    var message =
        AssistantMessage.builder()
            .content("Here is your plan")
            .properties(Map.of("reasoningContent", "I should inspect the workout history."))
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "call-1", "function", "fitness.lookup", "{\"period\":\"week\"}")))
            .build();

    translator.accept(stream(message, OutputType.AGENT_MODEL_STREAMING));
    translator.accept(stream(new AssistantMessage(""), OutputType.AGENT_MODEL_FINISHED));

    assertEquals(
        List.of(
            RunEvent.Type.REPLY_STARTED,
            RunEvent.Type.MODEL_CALL_STARTED,
            RunEvent.Type.BLOCK_STARTED,
            RunEvent.Type.BLOCK_DELTA,
            RunEvent.Type.BLOCK_STARTED,
            RunEvent.Type.BLOCK_DELTA,
            RunEvent.Type.BLOCK_STARTED,
            RunEvent.Type.BLOCK_DELTA,
            RunEvent.Type.BLOCK_COMPLETED,
            RunEvent.Type.BLOCK_COMPLETED,
            RunEvent.Type.BLOCK_COMPLETED,
            RunEvent.Type.MODEL_CALL_COMPLETED),
        events.stream().map(RunEvent::type).toList());
    assertEquals(ResponseBlock.BlockType.THINKING.name(), events.get(2).data().get("type"));
    assertEquals(
        ResponseBlock.Fidelity.PROVIDER_METADATA.name(), events.get(2).data().get("fidelity"));
    assertEquals(ResponseBlock.BlockType.TEXT.name(), events.get(4).data().get("type"));
    assertEquals(ResponseBlock.BlockType.TOOL_CALL.name(), events.get(6).data().get("type"));
    assertEquals("{\"period\":\"week\"}", events.get(7).data().get("delta"));
  }

  @Test
  void translatesToolResponseNodeOutputIntoToolResultBlock() {
    var events = new ArrayList<RunEvent>();
    var translator =
        new SpringAiAlibabaNodeOutputTranslator(
            "reply-1", "fitness", new AtomicLong(), events::add);
    var message =
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        "call-1", "fitness.lookup", "{\"workouts\":1}")))
            .build();

    translator.accept(stream(message, OutputType.AGENT_TOOL_FINISHED));

    assertEquals(
        List.of(
            RunEvent.Type.REPLY_STARTED,
            RunEvent.Type.BLOCK_STARTED,
            RunEvent.Type.BLOCK_DELTA,
            RunEvent.Type.BLOCK_COMPLETED),
        events.stream().map(RunEvent::type).toList());
    assertEquals(ResponseBlock.BlockType.TOOL_RESULT.name(), events.get(1).data().get("type"));
    assertEquals("{\"workouts\":1}", events.get(2).data().get("delta"));
  }

  @Test
  void doesNotMisrepresentPlainTextAsThinkingWhenProviderMetadataIsAbsent() {
    var events = new ArrayList<RunEvent>();
    var translator =
        new SpringAiAlibabaNodeOutputTranslator(
            "reply-1", "fitness", new AtomicLong(), events::add);

    translator.accept(
        stream(new AssistantMessage("Ordinary response"), OutputType.AGENT_MODEL_STREAMING));
    translator.accept(stream(new AssistantMessage(""), OutputType.AGENT_MODEL_FINISHED));

    assertTrue(
        events.stream()
            .noneMatch(
                event -> ResponseBlock.BlockType.THINKING.name().equals(event.data().get("type"))));
    assertTrue(
        events.stream().anyMatch(event -> event.type() == RunEvent.Type.CAPABILITY_DEGRADED));
  }

  private static StreamingOutput<?> stream(
      org.springframework.ai.chat.messages.Message message, OutputType outputType) {
    return new StreamingOutput<>(message, "model", "fitness", new OverAllState(), outputType);
  }
}
