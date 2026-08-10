package happy.jayden.yang.fitness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves the immutable model and credential snapshot shared by every fitness AI task. */
final class PublishedFitnessAgentRuntime {
  private static final String AGENT_KEY = "fitness.coach";
  private static final String RUNTIME_KEY = "currentGoalReportRuntime";

  private PublishedFitnessAgentRuntime() {}

  static Config load(JdbcTemplate jdbc, ObjectMapper mapper, boolean requiresVision)
      throws IOException, ConfigurationException {
    var published =
        jdbc.query(
            "SELECT configuration::text FROM agent_versions WHERE agent_key=?"
                + " AND status='PUBLISHED' ORDER BY version DESC LIMIT 1",
            (rs, row) -> rs.getString("configuration"),
            AGENT_KEY);
    if (published.isEmpty()) throw new ConfigurationException("未发布可用的健身 Agent");

    JsonNode snapshot = mapper.readTree(published.get(0));
    JsonNode runtime = object(snapshot, RUNTIME_KEY, "已发布 Agent 运行时快照缺失");
    JsonNode provider = object(runtime, "provider", "已发布 Agent Provider 快照缺失");
    JsonNode model = object(runtime, "model", "已发布 Agent 模型快照缺失");
    JsonNode credential = object(runtime, "credential", "已发布 Agent 凭据快照缺失");
    String providerKey = text(provider, "key", "已发布 Agent 未绑定 Provider");
    String modelKey = text(model, "key", "已发布 Agent 未绑定模型");

    String selectedProvider = text(snapshot, "providerKey", "已发布 Agent 未绑定 Provider");
    String selectedModel = text(snapshot, "modelKey", "已发布 Agent 未绑定模型");
    if (!providerKey.equals(selectedProvider) || !modelKey.equals(selectedModel)) {
      throw new ConfigurationException("已发布 Agent 与运行时快照不一致");
    }
    if (!"AVAILABLE".equals(text(provider, "status", "Provider 快照不可用"))
        || !"AVAILABLE".equals(text(model, "status", "模型快照不可用"))) {
      throw new ConfigurationException("已发布模型或 Provider 未启用");
    }

    JsonNode providerConfig = object(provider, "config", "Provider 快照缺少配置");
    JsonNode modelConfig = object(model, "config", "模型快照缺少配置");
    if (!providerKey.equals(text(modelConfig, "providerKey", "模型未显式绑定 Provider"))) {
      throw new ConfigurationException("模型未绑定当前 Provider");
    }
    if (requiresVision
        && !modelConfig.path("supportsVision").asBoolean(false)
        && !modelConfig.path("vision").asBoolean(false)) {
      throw new ConfigurationException("已发布 Agent 模型不支持视觉输入");
    }

    String endpoint = text(providerConfig, "endpoint", "Provider 未配置 endpoint");
    int keyVersion = credential.path("keyVersion").asInt(0);
    if (keyVersion < 1) throw new ConfigurationException("已发布 Agent 凭据版本不合法");
    return new Config(
        providerKey,
        optionalText(modelConfig, "model", modelKey),
        endpoint.replaceAll("/+$", ""),
        text(credential, "ciphertext", "已发布 Agent 凭据快照缺少密文"),
        text(credential, "iv", "已发布 Agent 凭据快照缺少初始化向量"),
        keyVersion);
  }

  private static JsonNode object(JsonNode node, String field, String message)
      throws ConfigurationException {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) throw new ConfigurationException(message);
    return value;
  }

  private static String text(JsonNode node, String field, String message)
      throws ConfigurationException {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new ConfigurationException(message);
    }
    return value.asText();
  }

  private static String optionalText(JsonNode node, String field, String fallback)
      throws ConfigurationException {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return fallback;
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new ConfigurationException("模型快照 model 不合法");
    }
    return value.asText();
  }

  record Config(
      String providerKey,
      String model,
      String endpoint,
      String credentialCiphertext,
      String credentialIv,
      int credentialKeyVersion) {}

  static final class ConfigurationException extends Exception {
    ConfigurationException(String message) {
      super(message);
    }
  }
}
