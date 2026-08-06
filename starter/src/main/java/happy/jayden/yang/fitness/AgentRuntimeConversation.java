package happy.jayden.yang.fitness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.EncryptedSecret;
import happy.jayden.yang.agentbuilder.infrastructure.security.AesGcmCredentialCipher;
import happy.jayden.yang.fitness.service.FitnessDtos.AiMessageResponse;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapData;
import happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto;
import happy.jayden.yang.fitness.service.FitnessPorts.AiConversation;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyUnavailableException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

final class AgentRuntimeConversation implements AiConversation {

  private static final String TARGET_AGENT = "fitness.coach";
  private static final String DEFAULT_DASHSCOPE_ENDPOINT =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";
  private static final String AGENT_DRAFT_SQL =
      """
      SELECT agent_key,provider_key,model_key,prompt_key,current_published_version
      FROM agent_drafts
      WHERE agent_key = ? AND current_published_version > 0
      ORDER BY current_published_version DESC
      LIMIT 1
      """;
  private static final String COMPONENT_SQL =
      """
      SELECT config::text
      FROM agent_component_projection
      WHERE component_type=? AND component_key=?
      ORDER BY version DESC
      LIMIT 1
      """;
  private static final String PROVIDER_CREDENTIAL_SQL =
      """
      SELECT credential_ciphertext,credential_iv
      FROM agent_provider_credentials
      WHERE provider_key=?
      """;
  private static final String SYSTEM_PROMPT =
      "你是“瘦瘦 AI 花爷”，用户的 AI 健身陪伴。请用中文输出，语气亲切但不矫揉造作。"
          + "你只基于用户输入与当前上下文给建议，不要发散。";

  private static final String PROMPT_TEMPLATE =
      """
      用户信息：
      用户名：%s
      当前目标：%s（起始%s斤，目标%s斤，状态%s）
      最近体重：%s 斤
      最近腰围：%s cm
      今日计划：%s
      已完成训练次数：%d 次

      用户问题：%s
      """;

  private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};

  private final FitnessStore fitnessStore;
  private final JdbcTemplate agentJdbc;
  private final ObjectMapper mapper;
  private final String masterKeyFile;
  private final RestTemplate restTemplate;

  AgentRuntimeConversation(
      FitnessStore fitnessStore, DataSource agentDataSource, ObjectMapper mapper, String masterKeyFile) {
    this.fitnessStore = fitnessStore;
    this.agentJdbc = new JdbcTemplate(agentDataSource);
    this.mapper = mapper;
    this.masterKeyFile = masterKeyFile;
    this.restTemplate = new RestTemplate();
  }

  @Override
  public AiMessageResponse send(UUID userId, String message) {
    var config = loadRuntimeConfig();
    String apiKey = loadProviderApiKey(config.providerKey());
    try {
      BootstrapData context = fitnessStore.loadForAi(userId);
      String requestPrompt =
          String.format(
              Locale.ROOT,
              PROMPT_TEMPLATE,
              context.user().nickname(),
              context.goal().name(),
              toDisplay(context.goal().startWeightJin()),
              toDisplay(context.goal().targetWeightJin()),
              context.goal().status(),
              toDisplay(latest(context.bodyRecords(), BodyRecordDto::weightJin)),
              toDisplay(latest(context.bodyRecords(), BodyRecordDto::waistCm)),
              context.plan() == null ? "今日暂无计划" : context.plan().title(),
              context.completedWorkoutCount(),
              message);

      var request =
          Map.of(
              "model", config.modelKey(),
              "messages",
              List.of(
                  Map.of("role", "system", "content", SYSTEM_PROMPT),
                  Map.of("role", "user", "content", requestPrompt)));
      var endpoint = normalizeEndpoint(config.providerEndpoint()) + "/chat/completions";
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);
      var response =
          restTemplate.postForEntity(URI.create(endpoint), new HttpEntity<>(request, headers), ChatCompletion.class);

      if (!response.getStatusCode().is2xxSuccessful()) {
        throw new DependencyUnavailableException("AI 调用返回错误：" + response.getStatusCode());
      }

      var body = response.getBody();
      if (body == null || body.choices == null || body.choices.isEmpty()) {
        throw new DependencyUnavailableException("AI 返回结果为空");
      }

      String answer = body.choices.get(0).message == null ? null : body.choices.get(0).message.content();
      if (answer == null || answer.isBlank()) {
        throw new DependencyUnavailableException("AI 返回内容为空");
      }
      return new AiMessageResponse(answer.trim());
    } catch (HttpClientErrorException exception) {
      throw new DependencyUnavailableException(
          "请求模型失败: " + exception.getStatusCode() + " " + safe(exception.getResponseBodyAsString()), exception);
    } catch (ResourceAccessException exception) {
      throw new DependencyUnavailableException("连接模型服务超时或不可达：" + exception.getMessage(), exception);
    } catch (DependencyUnavailableException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new DependencyUnavailableException("AI 运行时异常：" + exception.getMessage(), exception);
    } finally {
      if (apiKey != null) Arrays.fill(apiKey.toCharArray(), '\0');
    }
  }

  private static String normalizeEndpoint(String endpoint) {
    String value = endpoint == null ? "" : endpoint.trim();
    if (value.isEmpty()) return DEFAULT_DASHSCOPE_ENDPOINT;
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String loadProviderApiKey(String providerKey) {
    ProviderConfig config =
        agentJdbc
            .query(
                PROVIDER_CREDENTIAL_SQL,
                (rs, row) ->
                    new ProviderConfig(
                        rs.getBytes("credential_ciphertext"),
                        rs.getBytes("credential_iv"),
                        providerKey),
                providerKey)
            .stream()
            .findFirst()
            .orElseThrow(() -> new DependencyUnavailableException("Provider 尚未配置 API Key"));

    var componentRef = new ComponentRef(new ComponentKey(providerKey), new ComponentVersion(1));
    var cipher =
        AesGcmCredentialCipher.fromEnvironment(
            Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, masterKeyFile), componentRef);
    var secret = new EncryptedSecret(componentRef, config.cipherText(), config.iv());
    char[] plain = cipher.decrypt(secret);
    try {
      return new String(toBytes(plain), StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(plain, '\0');
    }
  }

  private static byte[] toBytes(char[] chars) {
    byte[] bytes = new byte[chars.length];
    for (int i = 0; i < chars.length; i++) {
      bytes[i] = (byte) chars[i];
    }
    return bytes;
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "（无响应体）" : value;
  }

  private RuntimeConfig loadRuntimeConfig() {
    var drafts = agentJdbc.query(
        AGENT_DRAFT_SQL,
        (rs, row) ->
            new RuntimeConfig(
                rs.getString("agent_key"),
                rs.getString("provider_key"),
                rs.getString("model_key"),
                rs.getString("prompt_key"),
                null,
                rs.getInt("current_published_version")),
        TARGET_AGENT);

    if (drafts.isEmpty()) {
      throw new DependencyUnavailableException(
          "未找到已发布的 Agent（fitness.coach），请先在工作台发布");
    }

    RuntimeConfig draft = drafts.get(0);
    if (draft.providerKey() == null || draft.providerKey().isBlank()) {
      throw new DependencyUnavailableException("当前 Agent 未绑定 Provider");
    }
    if (draft.modelKey() == null || draft.modelKey().isBlank()) {
      throw new DependencyUnavailableException("当前 Agent 未绑定模型");
    }

    String providerConfigJson =
        agentJdbc
            .query(
                COMPONENT_SQL,
                (rs, row) -> rs.getString("config"),
                "PROVIDER",
                draft.providerKey())
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new DependencyUnavailableException("Provider 组件不存在或未上架"));

    String endpoint =
        Optional.ofNullable(readConfigMap(providerConfigJson).get("endpoint"))
            .map(Object::toString)
            .filter(value -> !value.isBlank())
            .orElse(DEFAULT_DASHSCOPE_ENDPOINT);
    return draft.withProviderEndpoint(endpoint);
  }

  private Map<String, Object> readConfigMap(String configJson) {
    try {
      return mapper.readValue(configJson, STRING_MAP);
    } catch (Exception exception) {
      throw new DependencyUnavailableException("组件配置解析失败", exception);
    }
  }

  private static <T> T latest(List<BodyRecordDto> items, java.util.function.Function<BodyRecordDto, T> extractor) {
    return items.stream().map(extractor).filter(value -> value != null).findFirst().orElse(null);
  }

  private static String toDisplay(BigDecimal value) {
    return value == null ? "未记录" : value.toPlainString();
  }

  private record RuntimeConfig(
      String agentKey,
      String providerKey,
      String modelKey,
      String promptKey,
      String providerEndpoint,
      int publishedVersion) {
    RuntimeConfig withProviderEndpoint(String endpoint) {
      return new RuntimeConfig(
          agentKey, providerKey, modelKey, promptKey, endpoint, publishedVersion);
    }
  }

  private record ProviderConfig(byte[] cipherText, byte[] iv, String providerKey) {}

  private record ChatCompletion(List<ChatChoice> choices) {}

  private record ChatChoice(ChatMessage message) {}

  private record ChatMessage(String role, String content) {}
}
