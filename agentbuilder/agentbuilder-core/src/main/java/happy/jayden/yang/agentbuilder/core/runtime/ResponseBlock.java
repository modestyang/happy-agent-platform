package happy.jayden.yang.agentbuilder.core.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One ordered part of an assistant reply, independent of any agent framework. */
public sealed interface ResponseBlock
    permits ResponseBlock.Text,
        ResponseBlock.Thinking,
        ResponseBlock.ToolCall,
        ResponseBlock.ToolResult,
        ResponseBlock.Media,
        ResponseBlock.Hint {

  String blockId();

  BlockType type();

  Fidelity fidelity();

  enum BlockType {
    TEXT,
    THINKING,
    TOOL_CALL,
    TOOL_RESULT,
    MEDIA,
    HINT
  }

  enum Fidelity {
    NATIVE,
    PROVIDER_METADATA,
    PROVIDER_MARKUP
  }

  enum ToolCallState {
    PENDING,
    ASKING,
    ALLOWED,
    SUBMITTED,
    FINISHED
  }

  enum ToolResultState {
    RUNNING,
    SUCCESS,
    ERROR,
    INTERRUPTED,
    DENIED
  }

  record Text(String blockId, String text, Fidelity fidelity) implements ResponseBlock {
    public Text {
      blockId = required(blockId, "blockId");
      text = text == null ? "" : text;
      Objects.requireNonNull(fidelity, "fidelity");
    }

    @Override
    public BlockType type() {
      return BlockType.TEXT;
    }
  }

  record Thinking(String blockId, String thinking, Fidelity fidelity) implements ResponseBlock {
    public Thinking {
      blockId = required(blockId, "blockId");
      thinking = thinking == null ? "" : thinking;
      Objects.requireNonNull(fidelity, "fidelity");
    }

    @Override
    public BlockType type() {
      return BlockType.THINKING;
    }
  }

  record ToolCall(
      String blockId,
      String toolCallId,
      String toolName,
      String input,
      ToolCallState state,
      Fidelity fidelity)
      implements ResponseBlock {
    public ToolCall {
      blockId = required(blockId, "blockId");
      toolCallId = required(toolCallId, "toolCallId");
      toolName = required(toolName, "toolName");
      input = input == null ? "" : input;
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(fidelity, "fidelity");
    }

    @Override
    public BlockType type() {
      return BlockType.TOOL_CALL;
    }
  }

  record ToolResult(
      String blockId,
      String toolCallId,
      String toolName,
      String output,
      ToolResultState state,
      Fidelity fidelity)
      implements ResponseBlock {
    public ToolResult {
      blockId = required(blockId, "blockId");
      toolCallId = required(toolCallId, "toolCallId");
      toolName = required(toolName, "toolName");
      output = output == null ? "" : output;
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(fidelity, "fidelity");
    }

    @Override
    public BlockType type() {
      return BlockType.TOOL_RESULT;
    }
  }

  record Media(String blockId, String mediaType, String source, Fidelity fidelity)
      implements ResponseBlock {
    public Media {
      blockId = required(blockId, "blockId");
      mediaType = required(mediaType, "mediaType");
      source = required(source, "source");
      Objects.requireNonNull(fidelity, "fidelity");
    }

    @Override
    public BlockType type() {
      return BlockType.MEDIA;
    }
  }

  record Hint(String blockId, String source, String content, Fidelity fidelity)
      implements ResponseBlock {
    public Hint {
      blockId = required(blockId, "blockId");
      source = required(source, "source");
      content = content == null ? "" : content;
      Objects.requireNonNull(fidelity, "fidelity");
    }

    @Override
    public BlockType type() {
      return BlockType.HINT;
    }
  }

  static Map<String, Object> traceValue(ResponseBlock block) {
    var result = new LinkedHashMap<String, Object>();
    result.put("blockId", block.blockId());
    result.put("type", block.type().name());
    result.put("fidelity", block.fidelity().name());
    result.put("block", block);
    return Map.copyOf(result);
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
