package happy.jayden.yang.fitness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcCredentialAccess;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionCandidate;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bailian-compatible, non-streaming visual recognition runtime. */
public final class MealRecognitionRuntime implements MealRecognitionPort {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);
  private final JdbcTemplate agentJdbc;
  private final JdbcTemplate fitnessJdbc;
  private final ObjectMapper mapper;
  private final JdbcCredentialAccess credentials;

  public MealRecognitionRuntime(
      DataSource agentDataSource,
      DataSource fitnessDataSource,
      ObjectMapper mapper,
      String masterKeyFile) {
    this.agentJdbc = new JdbcTemplate(agentDataSource);
    this.fitnessJdbc = new JdbcTemplate(fitnessDataSource);
    this.mapper = mapper;
    this.credentials = new JdbcCredentialAccess(agentDataSource, Path.of(masterKeyFile));
  }

  @Override
  public MealRecognitionResult recognize(
      UUID ignoredUserId, UUID mediaId, MealType mealType, Instant occurredAt) {
    char[] apiKey = null;
    try {
      RuntimeConfig config = config();
      apiKey = credentials.readApiKey(config.providerKey()).orElse(null);
      if (apiKey == null) return failed("DEPENDENCY_NOT_CONFIGURED", "视觉 Provider 凭据未配置");
      Image image = image(mediaId);
      JsonNode response = post(config, apiKey, image);
      return new MealRecognitionResult("SUCCEEDED", parseItems(response), null, null);
    } catch (ConfigurationException exception) {
      return failed("DEPENDENCY_NOT_CONFIGURED", exception.getMessage());
    } catch (java.net.SocketTimeoutException exception) {
      return failed("TIMEOUT", "视觉模型调用超时");
    } catch (HttpException exception) {
      return failed("DEPENDENCY_UNAVAILABLE", "视觉模型 HTTP " + exception.status());
    } catch (IOException | IllegalArgumentException exception) {
      return failed("INVALID_MODEL_RESPONSE", "视觉模型返回不符合食物结果约束");
    } finally {
      if (apiKey != null) Arrays.fill(apiKey, '\0');
    }
  }

  private RuntimeConfig config() throws IOException, ConfigurationException {
    var selected =
        agentJdbc.query(
            "SELECT provider_key,model_key FROM agent_drafts WHERE agent_key='fitness.coach' AND"
                + " current_published_version>0 LIMIT 1",
            (rs, row) -> new String[] {rs.getString("provider_key"), rs.getString("model_key")});
    if (selected.isEmpty()) throw new ConfigurationException("未发布可用的视觉 Agent");
    String providerKey = selected.get(0)[0];
    String modelKey = selected.get(0)[1];
    var modelConfigs =
        agentJdbc.query(
            "SELECT config::text,status FROM agent_component_projection WHERE"
                + " component_type='MODEL' AND component_key=? ORDER BY version DESC LIMIT 1",
            (rs, row) -> new String[] {rs.getString("config"), rs.getString("status")},
            modelKey);
    var providerConfigs =
        agentJdbc.query(
            "SELECT config::text,status FROM agent_component_projection WHERE"
                + " component_type='PROVIDER' AND component_key=? ORDER BY version DESC LIMIT 1",
            (rs, row) -> new String[] {rs.getString("config"), rs.getString("status")},
            providerKey);
    if (modelConfigs.isEmpty()
        || providerConfigs.isEmpty()
        || !"AVAILABLE".equals(modelConfigs.get(0)[1])
        || !"AVAILABLE".equals(providerConfigs.get(0)[1]))
      throw new ConfigurationException("视觉模型或 Provider 未启用");
    JsonNode model = mapper.readTree(modelConfigs.get(0)[0]);
    JsonNode provider = mapper.readTree(providerConfigs.get(0)[0]);
    if (!model.path("supportsVision").asBoolean(false) && !model.path("vision").asBoolean(false))
      throw new ConfigurationException("所选模型不支持视觉输入");
    String boundProvider = model.path("providerKey").asText(providerKey);
    if (!providerKey.equals(boundProvider)) throw new ConfigurationException("模型未绑定当前 Provider");
    String endpoint = provider.path("endpoint").asText();
    if (endpoint.isBlank()) throw new ConfigurationException("Provider 未配置 endpoint");
    String apiModel = model.path("model").asText(modelKey);
    return new RuntimeConfig(providerKey, apiModel, endpoint.replaceAll("/$", ""));
  }

  private Image image(UUID mediaId) throws IOException, ConfigurationException {
    var rows =
        fitnessJdbc.query(
            "SELECT object_key,content_type,status FROM media_objects WHERE media_id=?",
            (rs, row) ->
                new String[] {
                  rs.getString("object_key"), rs.getString("content_type"), rs.getString("status")
                },
            mediaId);
    if (rows.isEmpty() || !"UPLOADED".equals(rows.get(0)[2]))
      throw new ConfigurationException("图片未上传完成");
    Path image = Path.of("deploy", ".local", "media", mediaId + ".upload");
    if (!Files.isRegularFile(image)) throw new ConfigurationException("本地上传图片不可读取");
    return new Image(rows.get(0)[1], Files.readAllBytes(image));
  }

  JsonNode post(RuntimeConfig config, char[] key, Image image) throws IOException, HttpException {
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("items"),
            "properties",
            Map.of(
                "items",
                Map.of(
                    "type",
                    "array",
                    "minItems",
                    1,
                    "items",
                    Map.of(
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "required",
                        List.of("name", "estimatedKcal", "confidence"),
                        "properties",
                        Map.of(
                            "name",
                            Map.of("type", "string"),
                            "estimatedKcal",
                            Map.of("type", "integer", "minimum", 0),
                            "confidence",
                            Map.of("type", "number", "minimum", 0, "maximum", 1))))));
    Map<String, Object> body =
        Map.of(
            "model",
            config.model(),
            "messages",
            List.of(
                Map.of(
                    "role",
                    "user",
                    "content",
                    List.of(
                        Map.of("type", "text", "text", "识别图片中的食物，仅按 schema 输出 JSON"),
                        Map.of(
                            "type",
                            "image_url",
                            "image_url",
                            Map.of(
                                "url",
                                "data:"
                                    + image.contentType()
                                    + ";base64,"
                                    + Base64.getEncoder().encodeToString(image.bytes())))))),
            "response_format",
            Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of("name", "meal_recognition", "strict", true, "schema", schema)));
    HttpURLConnection connection =
        (HttpURLConnection)
            URI.create(config.endpoint() + "/chat/completions").toURL().openConnection();
    connection.setRequestMethod("POST");
    connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
    connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
    connection.setDoOutput(true);
    connection.setRequestProperty("Authorization", "Bearer " + new String(key));
    connection.setRequestProperty("Content-Type", "application/json");
    try (var out = connection.getOutputStream()) {
      out.write(mapper.writeValueAsBytes(body));
    }
    int status = connection.getResponseCode();
    if (status < 200 || status >= 300) throw new HttpException(status);
    JsonNode outer = mapper.readTree(connection.getInputStream());
    String content = outer.path("choices").path(0).path("message").path("content").asText();
    if (content.isBlank()) throw new IllegalArgumentException("empty completion");
    return mapper.readTree(content);
  }

  private List<MealRecognitionCandidate> parseItems(JsonNode result) {
    JsonNode items = result.path("items");
    if (!items.isArray() || items.isEmpty()) throw new IllegalArgumentException("items");
    List<MealRecognitionCandidate> parsed = new ArrayList<>();
    for (JsonNode item : items) {
      String name = item.path("name").asText();
      int kcal = item.path("estimatedKcal").asInt(-1);
      double confidence = item.path("confidence").asDouble(-1);
      if (name.isBlank() || kcal < 0 || confidence < 0 || confidence > 1)
        throw new IllegalArgumentException("item");
      parsed.add(new MealRecognitionCandidate(name, kcal, confidence));
    }
    return parsed;
  }

  private static MealRecognitionResult failed(String code, String message) {
    return new MealRecognitionResult("FAILED", List.of(), code, message);
  }

  record RuntimeConfig(String providerKey, String model, String endpoint) {}

  record Image(String contentType, byte[] bytes) {}

  private static final class ConfigurationException extends Exception {
    ConfigurationException(String message) {
      super(message);
    }
  }

  private static final class HttpException extends Exception {
    private final int status;

    HttpException(int status) {
      this.status = status;
    }

    int status() {
      return status;
    }
  }

  public static final class LocalMediaUploadPort implements MediaUploadPort {
    private final JdbcTemplate fitnessJdbc;

    public LocalMediaUploadPort(DataSource dataSource) {
      this.fitnessJdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public MediaUploadTicket createTicket(
        UUID userId, String contentType, long contentLength, String sha256) {
      UUID id = UUID.randomUUID();
      Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
      fitnessJdbc.update(
          "INSERT INTO"
              + " media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status,expires_at)"
              + " VALUES (?,?,?,?,?,?,'PENDING',?)",
          id,
          userId,
          "local/" + id,
          contentType,
          contentLength,
          sha256,
          java.sql.Timestamp.from(expiresAt));
      return new MediaUploadTicket(
          id,
          "PUT",
          "http://localhost/api/v1/app/media-uploads/" + id,
          List.of(),
          expiresAt,
          10_485_760);
    }

    public void upload(UUID userId, UUID mediaId, String requestContentType, byte[] bytes) {
      var meta =
          fitnessJdbc.query(
              "SELECT content_length,sha256,content_type FROM media_objects WHERE media_id=? AND"
                  + " user_id=? AND status='PENDING' AND expires_at > CURRENT_TIMESTAMP",
              (rs, row) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)},
              mediaId,
              userId);
      if (meta.isEmpty()) {
        throw new NotFoundException("上传票据不存在或已失效");
      }
      if (!meta.get(0)[2].equals(requestContentType)
          || bytes.length != (long) meta.get(0)[0]
          || !HexFormat.of().formatHex(digest(bytes)).equals(meta.get(0)[1])) {
        throw new InvalidRequestException("上传内容与票据不一致");
      }
      Path target = Path.of("deploy", ".local", "media", mediaId + ".upload");
      try {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
      } catch (IOException exception) {
        throw new IllegalStateException(exception);
      }
    }

    private static byte[] digest(byte[] value) {
      try {
        return java.security.MessageDigest.getInstance("SHA-256").digest(value);
      } catch (java.security.NoSuchAlgorithmException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }
}
