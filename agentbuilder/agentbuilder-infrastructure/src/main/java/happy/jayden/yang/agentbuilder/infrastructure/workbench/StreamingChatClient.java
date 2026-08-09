package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(onChunk, "onChunk");
    Map<String, Object> body =
        Map.of(
            "model", model,
            "messages", messages,
            "temperature", temperature,
            "max_tokens", maxTokens,
            "stream", true);
    HttpURLConnection connection = null;
    StringBuilder buffer = new StringBuilder();
    StreamUsage usage = StreamUsage.empty();
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
            onChunk.accept(chunk);
            buffer.append(chunk.delta());
            if (chunk.usage() != null) {
              usage = chunk.usage();
            }
          }
        }
      }
      return new StreamResult(buffer.toString(), usage, completed);
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

  @SuppressWarnings("unchecked")
  private static StreamChunk parseChunk(String payload) {
    try {
      Map<String, Object> parsed = MAPPER.readValue(payload, Map.class);
      List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
      String delta = "";
      if (choices != null && !choices.isEmpty()) {
        Map<String, Object> first = choices.get(0);
        if (first != null) {
          Map<String, Object> deltaObj = (Map<String, Object>) first.get("delta");
          if (deltaObj != null && deltaObj.get("content") instanceof String value) {
            delta = value;
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
      return new StreamChunk(delta, usage);
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
  public record StreamChunk(String delta, StreamUsage usage) {
    public StreamChunk {
      delta = delta == null ? "" : delta;
    }
  }

  /** Token accounting that may or may not be present on a given chunk. */
  public record StreamUsage(int promptTokens, int completionTokens) {
    public static StreamUsage empty() {
      return new StreamUsage(0, 0);
    }
  }

  /** End-of-stream aggregator. */
  public record StreamResult(String text, StreamUsage usage, boolean completed) {
    public StreamResult {
      text = Objects.requireNonNull(text, "text");
    }
  }
}
