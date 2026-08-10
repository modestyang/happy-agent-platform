package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Streaming OpenAI-compatible chat completion client. Returns token chunks as they arrive over
 * Server-Sent Events.
 *
 * <p>The client uses HttpURLConnection rather than RestTemplate so we can read the response stream
 * incrementally without buffering the whole response. Jackson is used for JSON serialisation and
 * parsing.
 */
public final class StreamingChatClient implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DONE_MARKER = "[DONE]";
  private static final Pattern THINKING_BLOCK =
      Pattern.compile("<think>.*?</think>\\s*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private final String endpoint;
  private final String model;
  private final char[] apiKey;

  public StreamingChatClient(String endpoint, String model, char[] apiKey) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.model = Objects.requireNonNull(model, "model");
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey").clone();
  }

  /**
   * Send a streaming chat completion request. The callback receives one {@link StreamChunk} per
   * Server-Sent Event payload.
   */
  public StreamResult stream(
      List<Map<String, Object>> messages,
      double temperature,
      int maxTokens,
      Consumer<StreamChunk> onChunk) {
    return stream(messages, List.of(), temperature, maxTokens, onChunk);
  }

  /** Sends only the Tool contracts bound to the published Agent. */
  public StreamResult stream(
      List<Map<String, Object>> messages,
      List<ToolDescriptor> tools,
      double temperature,
      int maxTokens,
      Consumer<StreamChunk> onChunk) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(tools, "tools");
    Objects.requireNonNull(onChunk, "onChunk");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", messages);
    body.put("temperature", temperature);
    body.put("max_tokens", maxTokens);
    body.put("stream", true);
    if (!tools.isEmpty()) {
      body.put("tools", openAiTools(tools));
      body.put("tool_choice", "auto");
    }
    HttpURLConnection connection = null;
    StringBuilder buffer = new StringBuilder();
    VisibleDeltaFilter visibleDeltas =
        new VisibleDeltaFilter(delta -> onChunk.accept(new StreamChunk(delta, null)));
    StreamUsage usage = StreamUsage.empty();
    ToolCallAccumulator toolCalls = new ToolCallAccumulator();
    boolean completed = false;
    try {
      connection = openConnection();
      writeJsonBody(connection, body);
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        throw new IllegalStateException(
            "chat endpoint returned " + status + ": " + readError(connection));
      }
      try (InputStream stream = connection.getInputStream();
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          String payload = stripDataPrefix(line);
          if (payload == null) continue;
          if (DONE_MARKER.equals(payload)) {
            completed = true;
            break;
          }
          StreamChunk chunk = parseChunk(payload);
          if (chunk != null) {
            buffer.append(chunk.delta());
            visibleDeltas.accept(chunk.delta());
            toolCalls.accept(chunk.toolCallDeltas());
            if (chunk.usage() != null) {
              usage = chunk.usage();
            }
          }
        }
      }
      visibleDeltas.finish();
      return new StreamResult(
          visibleContent(buffer.toString()), usage, completed, toolCalls.completed());
    } catch (IOException exception) {
      throw new IllegalStateException(
          "chat stream interrupted: " + exception.getMessage(), exception);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  public void close() {
    Arrays.fill(apiKey, '\0');
  }

  /** Removes provider reasoning blocks before content reaches users, traces, or JSON parsers. */
  public static String visibleContent(String content) {
    if (content == null || content.isBlank()) return "";
    String visible = THINKING_BLOCK.matcher(content).replaceAll("").trim();
    int unfinished = visible.toLowerCase(java.util.Locale.ROOT).indexOf("<think>");
    return (unfinished < 0 ? visible : visible.substring(0, unfinished)).trim();
  }

  public static String visibleJsonContent(String content) {
    String visible = visibleContent(content);
    if (!visible.startsWith("```") || !visible.endsWith("```")) return visible;
    int firstLineEnd = visible.indexOf('\n');
    if (firstLineEnd < 0) return visible;
    String language = visible.substring(3, firstLineEnd).trim();
    if (!language.isEmpty() && !language.equalsIgnoreCase("json")) return visible;
    return visible.substring(firstLineEnd + 1, visible.length() - 3).trim();
  }

  private HttpURLConnection openConnection() throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) URI.create(endpoint + "/chat/completions").toURL().openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Authorization", "Bearer " + new String(apiKey).trim());
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setRequestProperty("Accept", "text/event-stream");
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(120000);
    connection.setDoOutput(true);
    return connection;
  }

  private static void writeJsonBody(HttpURLConnection connection, Map<String, Object> body)
      throws IOException {
    byte[] payload = MAPPER.writeValueAsBytes(body);
    try (var output = connection.getOutputStream()) {
      output.write(payload);
    }
  }

  /** Strip the SSE "data: " prefix and return the rest of the line, or null. */
  private static String stripDataPrefix(String line) {
    if (line.startsWith("data:")) {
      String rest = line.substring("data:".length()).trim();
      return rest.isEmpty() ? null : rest;
    }
    return null;
  }

  static List<Map<String, Object>> openAiTools(List<ToolDescriptor> descriptors) {
    return descriptors.stream()
        .map(
            descriptor -> {
              Map<String, Object> function = new LinkedHashMap<>();
              function.put("name", descriptor.runtimeName());
              function.put(
                  "description",
                  descriptor.description()
                      + "\n何时使用："
                      + descriptor.whenToUse()
                      + "\n何时不要使用："
                      + descriptor.whenNotToUse());
              function.put("strict", descriptor.strictInput());
              function.put("parameters", descriptor.inputSchema().document());
              return Map.<String, Object>of("type", "function", "function", function);
            })
        .toList();
  }

  @SuppressWarnings("unchecked")
  static StreamChunk parseChunk(String payload) {
    try {
      Map<String, Object> parsed = MAPPER.readValue(payload, Map.class);
      List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
      String delta = "";
      var toolCallDeltas = new ArrayList<ToolCallDelta>();
      if (choices != null && !choices.isEmpty()) {
        Map<String, Object> first = choices.get(0);
        if (first != null) {
          Map<String, Object> deltaObj = (Map<String, Object>) first.get("delta");
          if (deltaObj != null && deltaObj.get("content") instanceof String value) {
            delta = value;
          }
          if (deltaObj != null && deltaObj.get("tool_calls") instanceof List<?> rawCalls) {
            for (Object rawCall : rawCalls) {
              if (!(rawCall instanceof Map<?, ?> call)) continue;
              int index = numberOf(call.get("index"));
              String id = call.get("id") instanceof String value ? value : "";
              String name = "";
              String arguments = "";
              if (call.get("function") instanceof Map<?, ?> function) {
                if (function.get("name") instanceof String value) name = value;
                if (function.get("arguments") instanceof String value) arguments = value;
              }
              toolCallDeltas.add(new ToolCallDelta(index, id, name, arguments));
            }
          }
        }
      }
      StreamUsage usage = null;
      Object usageObj = parsed.get("usage");
      if (usageObj instanceof Map<?, ?> usageMap) {
        int prompt = numberOf(usageMap.get("prompt_tokens"));
        int completion = numberOf(usageMap.get("completion_tokens"));
        usage = new StreamUsage(prompt, completion);
      }
      return new StreamChunk(delta, usage, toolCallDeltas);
    } catch (IOException exception) {
      // Drop malformed chunk and continue streaming.
      return null;
    }
  }

  private static int numberOf(Object value) {
    if (value instanceof Number number) return number.intValue();
    return 0;
  }

  private static String readError(HttpURLConnection connection) {
    try (InputStream stream = connection.getErrorStream();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      StringBuilder builder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        builder.append(line);
      }
      return builder.toString();
    } catch (IOException exception) {
      return "<unable to read error stream>";
    }
  }

  /** One token delta emitted by the upstream model. */
  public record StreamChunk(String delta, StreamUsage usage, List<ToolCallDelta> toolCallDeltas) {
    public StreamChunk {
      delta = delta == null ? "" : delta;
      toolCallDeltas = List.copyOf(toolCallDeltas == null ? List.of() : toolCallDeltas);
    }

    public StreamChunk(String delta, StreamUsage usage) {
      this(delta, usage, List.of());
    }
  }

  public record ToolCallDelta(int index, String id, String name, String arguments) {}

  public record ToolCall(String id, String name, String arguments) {}

  /** Token accounting that may or may not be present on a given chunk. */
  public record StreamUsage(int promptTokens, int completionTokens) {
    public static StreamUsage empty() {
      return new StreamUsage(0, 0);
    }
  }

  /** End-of-stream aggregator. */
  public record StreamResult(
      String text, StreamUsage usage, boolean completed, List<ToolCall> toolCalls) {
    public StreamResult {
      text = Objects.requireNonNull(text, "text");
      toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    }

    public StreamResult(String text, StreamUsage usage, boolean completed) {
      this(text, usage, completed, List.of());
    }
  }

  private static final class ToolCallAccumulator {
    private final Map<Integer, MutableToolCall> values = new java.util.TreeMap<>();

    void accept(List<ToolCallDelta> deltas) {
      for (var delta : deltas) {
        var value = values.computeIfAbsent(delta.index(), ignored -> new MutableToolCall());
        if (!delta.id().isEmpty()) value.id = delta.id();
        if (!delta.name().isEmpty()) value.name = delta.name();
        value.arguments.append(delta.arguments());
      }
    }

    List<ToolCall> completed() {
      return values.values().stream()
          .map(value -> new ToolCall(value.id, value.name, value.arguments.toString()))
          .toList();
    }
  }

  private static final class MutableToolCall {
    private String id = "";
    private String name = "";
    private final StringBuilder arguments = new StringBuilder();
  }

  static final class VisibleDeltaFilter {
    private final Consumer<String> sink;
    private final StringBuilder received = new StringBuilder();
    private String emitted = "";

    VisibleDeltaFilter(Consumer<String> sink) {
      this.sink = Objects.requireNonNull(sink, "sink");
    }

    void accept(String delta) {
      if (delta == null || delta.isEmpty()) return;
      received.append(delta);
      emitAvailable();
    }

    void finish() {
      emitAvailable();
    }

    private void emitAvailable() {
      String undecidedPrefix =
          received.toString().stripLeading().toLowerCase(java.util.Locale.ROOT);
      if ("<think>".startsWith(undecidedPrefix)) return;
      String visible = visibleContent(received.toString());
      if (visible.length() <= emitted.length() || !visible.startsWith(emitted)) return;
      String delta = visible.substring(emitted.length());
      emitted = visible;
      if (!delta.isEmpty()) sink.accept(delta);
    }
  }
}
