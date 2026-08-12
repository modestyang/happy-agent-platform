package happy.jayden.yang.agentbuilder.framework.adapter.springai;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import happy.jayden.yang.agentbuilder.core.runtime.AssistantReply;
import happy.jayden.yang.agentbuilder.core.runtime.ResponseBlock;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** Maps SAA graph streaming outputs onto the framework-neutral reply event protocol. */
final class SpringAiAlibabaNodeOutputTranslator {
  private static final String REASONING_CONTENT = "reasoningContent";
  private static final String REASONING_CONTENT_SNAKE_CASE = "reasoning_content";
  private static final String REASONING = "reasoning";

  private final String replyId;
  private final String agentName;
  private final AtomicLong sequence;
  private final Consumer<RunEvent> eventConsumer;
  private final Map<String, String> latestToolCallIdByName = new LinkedHashMap<>();
  private final Map<String, String> activeBlocksByKey = new LinkedHashMap<>();
  private final Set<String> completedBlocks = new LinkedHashSet<>();
  private final StringBuilder text = new StringBuilder();
  private boolean replyStarted;
  private boolean modelCallOpen;
  private boolean reasoningObserved;
  private boolean replyEnded;
  private int modelCallNumber;

  SpringAiAlibabaNodeOutputTranslator(
      String replyId, String agentName, AtomicLong sequence, Consumer<RunEvent> eventConsumer) {
    this.replyId = required(replyId, "replyId");
    this.agentName = required(agentName, "agentName");
    this.sequence = Objects.requireNonNull(sequence, "sequence");
    this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
  }

  void accept(NodeOutput output) {
    if (!(output instanceof StreamingOutput<?> streamingOutput)) {
      return;
    }
    var outputType = streamingOutput.getOutputType();
    if (outputType == OutputType.AGENT_MODEL_STREAMING) {
      beginModelCall(output);
      translateModelMessage(streamingOutput.message());
      return;
    }
    if (outputType == OutputType.AGENT_MODEL_FINISHED) {
      beginModelCall(output);
      translateModelMessage(streamingOutput.message());
      finishModelCall();
      return;
    }
    if (outputType == OutputType.AGENT_TOOL_STREAMING
        || outputType == OutputType.AGENT_TOOL_FINISHED) {
      translateToolMessage(streamingOutput.message(), outputType == OutputType.AGENT_TOOL_FINISHED);
    }
  }

  void toolStarted(String toolName) {
    ensureReplyStarted();
    var toolCallId = toolCallId(toolName);
    var blockId = beginToolResult(toolCallId, toolName);
    activeBlocksByKey.put(toolResultKey(toolCallId), blockId);
  }

  void toolCompleted(String toolName, String result) {
    ensureReplyStarted();
    var toolCallId = toolCallId(toolName);
    appendToolResult(toolCallId, toolName, result, true);
  }

  void toolFailed(String toolName, String errorMessage) {
    ensureReplyStarted();
    var toolCallId = toolCallId(toolName);
    appendToolResult(toolCallId, toolName, errorMessage, true);
  }

  void complete() {
    if (!replyStarted || replyEnded) {
      return;
    }
    finishAllActiveBlocks();
    emitReplyEnded(AssistantReply.FinishReason.COMPLETED, "");
  }

  void fail(Throwable error) {
    if (!replyStarted || replyEnded) {
      return;
    }
    finishAllActiveBlocks();
    emitReplyEnded(AssistantReply.FinishReason.ERROR, message(error));
  }

  void interrupted() {
    if (!replyStarted || replyEnded) {
      return;
    }
    finishAllActiveBlocks();
    emitReplyEnded(AssistantReply.FinishReason.INTERRUPTED, "");
  }

  String text() {
    return text.toString();
  }

  private void beginModelCall(NodeOutput output) {
    ensureReplyStarted();
    if (modelCallOpen) {
      return;
    }
    modelCallOpen = true;
    reasoningObserved = false;
    modelCallNumber++;
    emit(
        RunEvent.Type.MODEL_CALL_STARTED,
        Map.of(
            "replyId",
            replyId,
            "modelCall",
            modelCallNumber,
            "node",
            nullable(output.node()),
            "agent",
            nullable(output.agent())));
  }

  private void finishModelCall() {
    if (!modelCallOpen) {
      return;
    }
    finishBlock("thinking-" + modelCallNumber);
    finishBlock("text-" + modelCallNumber);
    for (var blockKey : activeBlocksByKey.keySet().stream().toList()) {
      if (blockKey.startsWith("tool-call-" + modelCallNumber + '-')) {
        finishBlock(blockKey);
      }
    }
    if (!reasoningObserved) {
      emit(
          RunEvent.Type.CAPABILITY_DEGRADED,
          Map.of(
              "replyId",
              replyId,
              "capability",
              "reasoning",
              "reason",
              "SAA did not expose explicit reasoning metadata for this model call"));
    }
    emit(
        RunEvent.Type.MODEL_CALL_COMPLETED,
        Map.of("replyId", replyId, "modelCall", modelCallNumber));
    modelCallOpen = false;
  }

  private void translateModelMessage(Message message) {
    if (!(message instanceof AssistantMessage assistant)) {
      return;
    }
    var reasoning = explicitReasoning(assistant);
    if (!reasoning.isBlank()) {
      reasoningObserved = true;
      appendTextBlock(
          "thinking-" + modelCallNumber,
          new ResponseBlock.Thinking(
              blockId("thinking-" + modelCallNumber), "", ResponseBlock.Fidelity.PROVIDER_METADATA),
          reasoning);
    }
    var body = assistant.getText();
    if (!body.isBlank()) {
      var markup = ThinkingMarkup.parse(body);
      if (markup != null) {
        reasoningObserved = true;
        appendTextBlock(
            "thinking-" + modelCallNumber,
            new ResponseBlock.Thinking(
                blockId("thinking-" + modelCallNumber), "", ResponseBlock.Fidelity.PROVIDER_MARKUP),
            markup.thinking());
        body = markup.text();
      }
      if (!body.isBlank()) {
        appendTextBlock(
            "text-" + modelCallNumber,
            new ResponseBlock.Text(
                blockId("text-" + modelCallNumber), "", ResponseBlock.Fidelity.NATIVE),
            body);
        text.append(body);
      }
    }
    for (var toolCall : assistant.getToolCalls()) {
      var key = "tool-call-" + modelCallNumber + '-' + required(toolCall.id(), "toolCall.id");
      var blockId =
          activeBlocksByKey.computeIfAbsent(
              key,
              ignored -> {
                var id = blockId(key);
                emit(
                    RunEvent.blockStarted(
                        nextSequence(),
                        Instant.now(),
                        replyId,
                        new ResponseBlock.ToolCall(
                            id,
                            toolCall.id(),
                            toolCall.name(),
                            "",
                            ResponseBlock.ToolCallState.SUBMITTED,
                            ResponseBlock.Fidelity.NATIVE)));
                latestToolCallIdByName.put(toolCall.name(), toolCall.id());
                return id;
              });
      if (!toolCall.arguments().isBlank()) {
        emit(
            RunEvent.blockDelta(
                nextSequence(), Instant.now(), replyId, blockId, toolCall.arguments()));
      }
    }
  }

  private void translateToolMessage(Message message, boolean finished) {
    ensureReplyStarted();
    if (!(message instanceof ToolResponseMessage toolResponse)) {
      return;
    }
    for (var response : toolResponse.getResponses()) {
      appendToolResult(response.id(), response.name(), response.responseData(), finished);
    }
  }

  private void appendToolResult(
      String toolCallId, String toolName, String result, boolean finished) {
    var key = toolResultKey(toolCallId);
    var blockId = activeBlocksByKey.get(key);
    if (blockId == null) {
      blockId = beginToolResult(toolCallId, toolName);
    }
    if (!completedBlocks.contains(blockId) && result != null && !result.isBlank()) {
      emit(RunEvent.blockDelta(nextSequence(), Instant.now(), replyId, blockId, result));
    }
    if (finished) {
      finishBlock(key);
    }
  }

  private String beginToolResult(String toolCallId, String toolName) {
    var key = toolResultKey(toolCallId);
    var existing = activeBlocksByKey.get(key);
    if (existing != null) {
      return existing;
    }
    var blockId = blockId(key);
    activeBlocksByKey.put(key, blockId);
    emit(
        RunEvent.blockStarted(
            nextSequence(),
            Instant.now(),
            replyId,
            new ResponseBlock.ToolResult(
                blockId,
                toolCallId,
                toolName,
                "",
                ResponseBlock.ToolResultState.RUNNING,
                ResponseBlock.Fidelity.NATIVE)));
    return blockId;
  }

  private void appendTextBlock(String key, ResponseBlock block, String delta) {
    var blockId = activeBlocksByKey.computeIfAbsent(key, ignored -> startBlock(block));
    emit(RunEvent.blockDelta(nextSequence(), Instant.now(), replyId, blockId, delta));
  }

  private String startBlock(ResponseBlock block) {
    emit(RunEvent.blockStarted(nextSequence(), Instant.now(), replyId, block));
    return block.blockId();
  }

  private void finishAllActiveBlocks() {
    for (var blockKey : activeBlocksByKey.keySet().stream().toList()) {
      finishBlock(blockKey);
    }
  }

  private void finishBlock(String key) {
    var blockId = activeBlocksByKey.get(key);
    if (blockId != null && completedBlocks.add(blockId)) {
      emit(RunEvent.blockCompleted(nextSequence(), Instant.now(), replyId, blockId));
    }
  }

  private void ensureReplyStarted() {
    if (!replyStarted) {
      replyStarted = true;
      emit(RunEvent.replyStarted(nextSequence(), Instant.now(), replyId, agentName));
    }
  }

  private void emitReplyEnded(AssistantReply.FinishReason finishReason, String errorMessage) {
    emit(RunEvent.replyEnded(nextSequence(), Instant.now(), replyId, finishReason, errorMessage));
    replyEnded = true;
  }

  private String toolCallId(String toolName) {
    return latestToolCallIdByName.computeIfAbsent(
        toolName, ignored -> "tool-" + (latestToolCallIdByName.size() + 1));
  }

  private String toolResultKey(String toolCallId) {
    return "tool-result-" + required(toolCallId, "toolCallId");
  }

  private String blockId(String key) {
    return replyId + '-' + key;
  }

  private void emit(RunEvent event) {
    eventConsumer.accept(event);
  }

  private void emit(RunEvent.Type type, Map<String, Object> data) {
    emit(new RunEvent(nextSequence(), type, Instant.now(), data));
  }

  private long nextSequence() {
    return sequence.incrementAndGet();
  }

  private static String explicitReasoning(AssistantMessage message) {
    var metadata = message.getMetadata();
    for (var key : new String[] {REASONING_CONTENT, REASONING_CONTENT_SNAKE_CASE, REASONING}) {
      var value = metadata.get(key);
      if (value instanceof String text && !text.isBlank()) {
        return text;
      }
    }
    return "";
  }

  private static String nullable(String value) {
    return value == null ? "" : value;
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static String message(Throwable error) {
    return error.getMessage() == null || error.getMessage().isBlank()
        ? "Framework execution failed"
        : error.getMessage();
  }

  private record ThinkingMarkup(String thinking, String text) {
    private static ThinkingMarkup parse(String value) {
      if (!value.startsWith("<think>")) {
        return null;
      }
      var closingTag = value.indexOf("</think>");
      if (closingTag < 0) {
        return null;
      }
      return new ThinkingMarkup(value.substring(7, closingTag), value.substring(closingTag + 8));
    }
  }
}
