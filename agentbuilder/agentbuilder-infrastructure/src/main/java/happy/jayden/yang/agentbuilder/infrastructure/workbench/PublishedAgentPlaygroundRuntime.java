package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.EncryptedSecret;
import happy.jayden.yang.agentbuilder.infrastructure.security.AesGcmCredentialCipher;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.ConversationMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Executes a real, tool-free probe against an Agent's immutable published configuration. */
public final class PublishedAgentPlaygroundRuntime {
  private static final UUID DEVELOPER_USER_ID =
      UUID.nameUUIDFromBytes("happy-agent:developer-playground".getBytes(StandardCharsets.UTF_8));
  private static final String PUBLISHED_AGENT_SQL =
      "SELECT version,configuration::text FROM agent_versions WHERE agent_key=?"
          + " AND status='PUBLISHED' ORDER BY version DESC LIMIT 1";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Path masterKeyFile;
  private final JdbcRunTraceRepository traces;

  public PublishedAgentPlaygroundRuntime(
      DataSource dataSource, ObjectMapper mapper, Path masterKeyFile, JdbcRunTraceRepository traces) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.masterKeyFile = Objects.requireNonNull(masterKeyFile, "masterKeyFile").toAbsolutePath();
    this.traces = Objects.requireNonNull(traces, "traces");
  }

  public String send(String agentKey, String message) {
    if (agentKey == null || agentKey.isBlank()) throw new IllegalArgumentException("agentKey 必填");
    if (message == null || message.isBlank()) throw new IllegalArgumentException("message 不能为空");
    RuntimeConfig config = load(agentKey.trim());
    Instant startedAt = Instant.now();
    UUID runId = UUID.randomUUID();
    var conversation = traces.resolveConversation(DEVELOPER_USER_ID, config.agentKey(), startedAt);
    List<ConversationMessage> history =
        traces.recentConversationMessages(conversation.conversationId(), 20);
    traces.insertRun(
        runId,
        DEVELOPER_USER_ID,
        conversation.conversationId(),
        config.agentKey(),
        config.version(),
        config.frameworkKey(),
        config.modelKey(),
        truncate(message, 1000));
    traces.appendConversationMessage(
        conversation.conversationId(), runId, "USER", message.trim(), startedAt);
    long[] sequence = {0};
    traces.appendEvent(
        runId,
        ++sequence[0],
        "RUN_STARTED",
        "开始调试",
        "agent=" + config.agentKey() + ",version=" + config.version());
    traces.appendEvent(
        runId,
        ++sequence[0],
        "CONVERSATION_CONTEXT",
        "已载入会话上下文",
        "historyMessages=" + history.size());

    char[] apiKey = null;
    try {
      apiKey = decrypt(config);
      var messages = new ArrayList<Map<String, Object>>();
      messages.add(Map.of("role", "system", "content", config.systemPrompt()));
      appendHistory(messages, history);
      messages.add(Map.of("role", "user", "content", message.trim()));
      try (StreamingChatClient client =
          new StreamingChatClient(config.endpoint(), config.modelKey(), apiKey)) {
        StringBuilder answer = new StringBuilder();
        var result =
            client.stream(
                messages,
                config.temperature(),
                1500,
                chunk -> {
                  if (chunk.delta().isEmpty()) return;
                  answer.append(chunk.delta());
                  traces.appendEvent(
                      runId, ++sequence[0], "TOKEN", "delta", truncate(chunk.delta(), 250));
                });
        String response = answer.toString().trim();
        if (response.isEmpty()) throw new IllegalStateException("AI 返回内容为空");
        Instant completedAt = Instant.now();
        long durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();
        traces.appendEvent(runId, ++sequence[0], "RUN_COMPLETED", "执行完成", "durationMs=" + durationMs);
        traces.markCompleted(
            runId,
            "SUCCEEDED",
            completedAt,
            durationMs,
            0,
            result.usage().promptTokens(),
            result.usage().completionTokens(),
            0,
            config.modelKey(),
            null,
            null,
            truncate(response, 1500));
        traces.appendConversationMessage(
            conversation.conversationId(), runId, "ASSISTANT", response, completedAt);
        return response;
      }
    } catch (RuntimeException exception) {
      failure(runId, conversation.conversationId(), sequence, exception.getMessage());
      throw new PlaygroundRuntimeUnavailableException(
          "所选 Agent 运行失败：" + safeMessage(exception), exception);
    } finally {
      if (apiKey != null) Arrays.fill(apiKey, '\0');
    }
  }

  private RuntimeConfig load(String agentKey) {
    var rows =
        jdbc.query(
            PUBLISHED_AGENT_SQL,
            (rs, row) -> new PublishedConfig(rs.getInt("version"), rs.getString("configuration")),
            agentKey);
    if (rows.isEmpty()) throw new IllegalArgumentException("所选 Agent 尚未发布");
    try {
      var published = rows.get(0);
      JsonNode root = mapper.readTree(published.configuration());
      JsonNode runtime = object(root, "agentRuntime", "已发布版本缺少运行时快照，请重新发布");
      JsonNode provider = object(runtime, "provider", "已发布版本缺少 Provider 快照");
      JsonNode model = object(runtime, "model", "已发布版本缺少模型快照");
      JsonNode prompt = object(runtime, "prompt", "已发布版本缺少提示词快照，请重新发布");
      JsonNode credential = object(runtime, "credential", "已发布版本缺少 Provider 凭据快照，请重新发布");
      String providerKey = text(provider, "key", "Provider 快照不完整");
      String modelKey = text(model, "key", "模型快照不完整");
      String endpoint = text(object(provider, "config", "Provider 配置缺失"), "endpoint", "Provider 未配置 endpoint").replaceAll("/+$", "");
      String modelName = optional(object(model, "config", "模型配置缺失"), "model", modelKey);
      String promptText = text(object(prompt, "config", "提示词配置缺失"), "template", "提示词模板为空");
      return new RuntimeConfig(
          agentKey,
          published.version(),
          text(root, "frameworkKey", "已发布版本未绑定 Framework"),
          providerKey,
          modelName,
          endpoint,
          promptText,
          root.path("temperature").asDouble(0.5),
          credential.path("keyVersion").asInt(0),
          text(credential, "ciphertext", "凭据快照缺少密文"),
          text(credential, "iv", "凭据快照缺少初始化向量"));
    } catch (PlaygroundRuntimeUnavailableException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new PlaygroundRuntimeUnavailableException("已发布 Agent 配置解析失败", exception);
    }
  }

  private char[] decrypt(RuntimeConfig config) {
    if (config.credentialVersion() < 1) {
      throw new PlaygroundRuntimeUnavailableException("已发布 Provider 凭据快照版本不合法");
    }
    byte[] ciphertext = Base64.getDecoder().decode(config.ciphertext());
    byte[] iv = Base64.getDecoder().decode(config.iv());
    try {
      var ref =
          new ComponentRef(
              new ComponentKey(config.providerKey()), new ComponentVersion(config.credentialVersion()));
      var cipher =
          AesGcmCredentialCipher.fromEnvironment(
              Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, masterKeyFile.toString()), ref);
      return cipher.decrypt(new EncryptedSecret(ref, ciphertext, iv));
    } finally {
      Arrays.fill(ciphertext, (byte) 0);
      Arrays.fill(iv, (byte) 0);
    }
  }

  private void failure(UUID runId, UUID conversationId, long[] sequence, String error) {
    Instant completedAt = Instant.now();
    traces.appendEvent(runId, ++sequence[0], "RUN_FAILED", "执行失败", truncate(error, 500));
    traces.markCompleted(runId, "FAILED", completedAt, 0, 0, 0, 0, 0, null, "RUNTIME_ERROR", safeMessage(error), "");
    traces.appendConversationMessage(
        conversationId, runId, "ASSISTANT", "本次请求暂未完成，请稍后重试。", completedAt);
  }

  private static void appendHistory(List<Map<String, Object>> messages, List<ConversationMessage> history) {
    for (var item : history) {
      if ("USER".equals(item.role())) messages.add(Map.of("role", "user", "content", item.content()));
      if ("ASSISTANT".equals(item.role())) messages.add(Map.of("role", "assistant", "content", item.content()));
    }
  }

  private static JsonNode object(JsonNode source, String key, String failure) {
    JsonNode value = source.path(key);
    if (!value.isObject()) throw new PlaygroundRuntimeUnavailableException(failure);
    return value;
  }

  private static String text(JsonNode source, String key, String failure) {
    String value = source.path(key).asText("").trim();
    if (value.isBlank()) throw new PlaygroundRuntimeUnavailableException(failure);
    return value;
  }

  private static String optional(JsonNode source, String key, String fallback) {
    String value = source.path(key).asText("").trim();
    return value.isBlank() ? fallback : value;
  }

  private static String truncate(String value, int limit) {
    if (value == null) return "";
    return value.length() <= limit ? value : value.substring(0, limit) + "…";
  }

  private static String safeMessage(Exception exception) {
    return safeMessage(exception.getMessage());
  }

  private static String safeMessage(String message) {
    return message == null || message.isBlank() ? "未知运行时错误" : truncate(message, 300);
  }

  private record PublishedConfig(int version, String configuration) {}

  private record RuntimeConfig(
      String agentKey,
      int version,
      String frameworkKey,
      String providerKey,
      String modelKey,
      String endpoint,
      String systemPrompt,
      double temperature,
      int credentialVersion,
      String ciphertext,
      String iv) {}

  public static final class PlaygroundRuntimeUnavailableException extends RuntimeException {
    public PlaygroundRuntimeUnavailableException(String message) {
      super(message);
    }

    public PlaygroundRuntimeUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
