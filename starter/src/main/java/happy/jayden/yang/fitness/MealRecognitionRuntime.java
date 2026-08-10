package happy.jayden.yang.fitness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.StreamingChatClient;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionCandidate;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.UploadedMedia;
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

/** OpenAI-compatible, non-streaming visual recognition runtime. */
public final class MealRecognitionRuntime implements MealRecognitionPort {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);
  private final JdbcTemplate agentJdbc;
  private final ObjectMapper mapper;
  private final FitnessProviderCredentialAccess credentials;
  private final MediaUploadPort mediaUploadPort;

  public MealRecognitionRuntime(
      DataSource agentDataSource,
      DataSource fitnessDataSource,
      ObjectMapper mapper,
      String masterKeyFile) {
    this(agentDataSource, mapper, masterKeyFile, new LocalMediaUploadPort(fitnessDataSource));
  }

  public MealRecognitionRuntime(
      DataSource agentDataSource,
      ObjectMapper mapper,
      String masterKeyFile,
      MediaUploadPort mediaUploadPort) {
    this.agentJdbc = new JdbcTemplate(agentDataSource);
    this.mapper = mapper;
    this.credentials = new FitnessProviderCredentialAccess(agentDataSource, Path.of(masterKeyFile));
    this.mediaUploadPort = mediaUploadPort;
  }

  @Override
  public MealRecognitionResult recognize(
      UUID ignoredUserId, UUID mediaId, MealType mealType, Instant occurredAt) {
    char[] apiKey = null;
    try {
      RuntimeConfig config = config();
      try {
        apiKey =
            credentials.decryptPublishedSnapshot(
                config.providerKey(),
                config.credentialKeyVersion(),
                config.credentialCiphertext(),
                config.credentialIv());
      } catch (IllegalStateException | IllegalArgumentException | SecurityException exception) {
        return failed("DEPENDENCY_NOT_CONFIGURED", "已发布视觉凭据快照无法解密");
      }
      Image image = image(mediaId);
      JsonNode response = post(config, apiKey, image);
      return result(response);
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

  RuntimeConfig config() throws IOException, ConfigurationException {
    try {
      var published = PublishedFitnessAgentRuntime.load(agentJdbc, mapper, true);
      return new RuntimeConfig(
          published.providerKey(),
          published.model(),
          published.endpoint(),
          published.credentialCiphertext(),
          published.credentialIv(),
          published.credentialKeyVersion());
    } catch (PublishedFitnessAgentRuntime.ConfigurationException exception) {
      throw new ConfigurationException(exception.getMessage());
    }
  }

  private Image image(UUID mediaId) throws IOException, ConfigurationException {
    try {
      UploadedMedia uploaded = mediaUploadPort.readUploaded(mediaId);
      return new Image(uploaded.contentType(), uploaded.bytes());
    } catch (IllegalStateException exception) {
      throw new ConfigurationException("已上传图片不可读取");
    }
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
                            Map.of("type", "string", "minLength", 1, "maxLength", 120),
                            "estimatedKcal",
                            Map.of("type", "integer", "minimum", 0, "maximum", 20000),
                            "confidence",
                            Map.of("type", "number", "minimum", 0, "maximum", 1))))));
    Map<String, Object> body =
        Map.of(
            "model",
            config.model(),
            "max_tokens",
            1000,
            "messages",
            List.of(
                Map.of(
                    "role",
                    "user",
                    "content",
                    List.of(
                        Map.of(
                            "type",
                            "text",
                            "text",
                            "识别图片中的食物。只返回一个 JSON 对象且仅含 items 数组；每个元素仅含 name:string、estimatedKcal:integer、confidence:number(0-1)，不要 Markdown 或其他字段"),
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
    String content =
        StreamingChatClient.visibleJsonContent(
            outer.path("choices").path(0).path("message").path("content").asText());
    if (content.isBlank()) throw new IllegalArgumentException("empty completion");
    return mapper.readTree(content);
  }

  List<MealRecognitionCandidate> parseItems(JsonNode result) {
    if (!result.isObject() || result.size() != 1 || !result.has("items")) {
      throw new IllegalArgumentException("response must contain only items");
    }
    JsonNode items = result.get("items");
    if (!items.isArray() || items.isEmpty() || items.size() > 50) {
      throw new IllegalArgumentException("items");
    }
    List<MealRecognitionCandidate> parsed = new ArrayList<>();
    for (JsonNode item : items) {
      if (!item.isObject()
          || item.size() != 3
          || !item.has("name")
          || !item.has("estimatedKcal")
          || !item.has("confidence")
          || !item.get("name").isTextual()
          || !item.get("estimatedKcal").isInt()
          || !item.get("confidence").isNumber()) {
        throw new IllegalArgumentException("item shape");
      }
      String name = item.get("name").textValue();
      int kcal = item.get("estimatedKcal").intValue();
      double confidence = item.get("confidence").doubleValue();
      if (name.isBlank()
          || name.codePointCount(0, name.length()) > 120
          || kcal < 0
          || kcal > 20_000
          || !Double.isFinite(confidence)
          || confidence < 0
          || confidence > 1) {
        throw new IllegalArgumentException("item");
      }
      parsed.add(new MealRecognitionCandidate(name, kcal, confidence));
    }
    return parsed;
  }

  MealRecognitionResult result(JsonNode response) {
    try {
      return new MealRecognitionResult("SUCCEEDED", parseItems(response), null, null);
    } catch (IllegalArgumentException exception) {
      return failed("INVALID_MODEL_RESPONSE", "视觉模型返回不符合食物结果约束");
    }
  }

  private static MealRecognitionResult failed(String code, String message) {
    return new MealRecognitionResult("FAILED", List.of(), code, message);
  }

  record RuntimeConfig(
      String providerKey,
      String model,
      String endpoint,
      String credentialCiphertext,
      String credentialIv,
      int credentialKeyVersion) {
    RuntimeConfig(String providerKey, String model, String endpoint) {
      this(providerKey, model, endpoint, "", "", 0);
    }
  }

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
          id, "PUT", "/api/v1/app/media-uploads/" + id, List.of(), expiresAt, 10_485_760);
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

    @Override
    public void verifyUploaded(UUID userId, UUID mediaId) {
      Object[] meta = metadata(userId, mediaId);
      if ("UPLOADED".equals(meta[3])) return;
      Path target = Path.of("deploy", ".local", "media", mediaId + ".upload");
      try {
        byte[] bytes = Files.readAllBytes(target);
        if (bytes.length != (long) meta[0]
            || !meta[1].equals(HexFormat.of().formatHex(digest(bytes)))) {
          throw new InvalidRequestException("上传内容与票据不一致");
        }
      } catch (IOException exception) {
        throw new NotFoundException("上传内容不存在");
      }
    }

    @Override
    public UploadedMedia readUploaded(UUID mediaId) {
      var rows =
          fitnessJdbc.query(
              "SELECT content_type,status FROM media_objects WHERE media_id=?",
              (rs, row) -> new String[] {rs.getString(1), rs.getString(2)},
              mediaId);
      if (rows.isEmpty() || !"UPLOADED".equals(rows.get(0)[1])) {
        throw new IllegalStateException("图片未上传完成");
      }
      try {
        return new UploadedMedia(
            rows.get(0)[0],
            Files.readAllBytes(Path.of("deploy", ".local", "media", mediaId + ".upload")));
      } catch (IOException exception) {
        throw new IllegalStateException("本地上传图片不可读取", exception);
      }
    }

    private Object[] metadata(UUID userId, UUID mediaId) {
      var meta =
          fitnessJdbc.query(
              "SELECT content_length,sha256,content_type,status FROM media_objects WHERE media_id=?"
                  + " AND user_id=? AND expires_at > CURRENT_TIMESTAMP",
              (rs, row) ->
                  new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)},
              mediaId,
              userId);
      if (meta.isEmpty()) throw new NotFoundException("上传票据不存在或已失效");
      return meta.get(0);
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
