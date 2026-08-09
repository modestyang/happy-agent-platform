package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deliberately resolves vision capability from the Agent-owned schema only. It never returns a
 * synthetic food: incomplete runtime configuration is materialized as a closed job failure.
 */
public final class MealRecognitionRuntime implements MealRecognitionPort {
  private final JdbcTemplate agentJdbc;

  public MealRecognitionRuntime(DataSource agentDataSource) {
    this.agentJdbc = new JdbcTemplate(agentDataSource);
  }

  @Override
  public MealRecognitionResult recognize(UUID userId, UUID mediaId, MealType mealType, Instant occurredAt) {
    Long configured =
        agentJdbc.queryForObject(
            "SELECT COUNT(*) FROM agent_component_projection m "
                + "JOIN agent_component_projection p ON p.component_type='PROVIDER' AND p.status='AVAILABLE' "
                + "JOIN agent_provider_credentials c ON c.provider_key=p.component_key "
                + "WHERE m.component_type='MODEL' AND m.status='AVAILABLE' "
                + "AND (m.tags @> ARRAY['VISION'] OR m.config->>'supportsVision'='true')",
            Long.class);
    if (configured == null || configured == 0) {
      return new MealRecognitionResult(
          "FAILED", List.of(), "DEPENDENCY_NOT_CONFIGURED", "未配置已启用的视觉模型和 Provider");
    }
    // Credential decryption and the Bailian-compatible HTTP client are intentionally not faked.
    // A configured but not yet executable runtime is a durable failure, never fabricated food.
    return new MealRecognitionResult(
        "FAILED", List.of(), "MODEL_RUNTIME_UNAVAILABLE", "视觉模型运行时不可用，请检查 Provider 配置");
  }

  /** Local-only direct upload adapter. Production must replace this bean with a signed OSS port. */
  public static final class LocalMediaUploadPort implements MediaUploadPort {
    private static final long MAX_BYTES = 10_485_760;
    private final JdbcTemplate fitnessJdbc;
    private final Path root = Path.of("deploy", ".local", "media");

    public LocalMediaUploadPort(DataSource fitnessDataSource) {
      this.fitnessJdbc = new JdbcTemplate(fitnessDataSource);
    }

    @Override
    public MediaUploadTicket createTicket(
        UUID userId, String contentType, long contentLength, String sha256) {
      UUID mediaId = UUID.randomUUID();
      Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
      fitnessJdbc.update(
          "INSERT INTO media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status) VALUES (?,?,?,?,?,?,'PENDING')",
          mediaId,
          userId,
          "local/meal/" + userId + "/" + mediaId,
          contentType,
          contentLength,
          sha256);
      return new MediaUploadTicket(
          mediaId,
          "PUT",
          "/api/app/media-uploads/" + mediaId,
          List.of(),
          expiresAt,
          MAX_BYTES);
    }

    public void upload(UUID userId, UUID mediaId, byte[] content) {
      var metadata = fitnessJdbc.query(
          "SELECT content_length,sha256 FROM media_objects WHERE media_id=? AND user_id=? AND status='PENDING'",
          (rs, row) -> new UploadMetadata(rs.getLong("content_length"), rs.getString("sha256")), mediaId, userId);
      if (metadata.isEmpty()) throw new IllegalArgumentException("上传票据不存在或已失效");
      UploadMetadata expected = metadata.get(0);
      if (content.length != expected.length() || !HexFormat.of().formatHex(sha256(content)).equals(expected.sha256())) {
        throw new IllegalArgumentException("上传内容与票据校验信息不一致");
      }
      try {
        Files.createDirectories(root);
        Files.write(root.resolve(mediaId + ".upload"), content);
      } catch (java.io.IOException exception) { throw new IllegalStateException("本地媒体文件写入失败", exception); }
    }

    private static byte[] sha256(byte[] content) {
      try { return MessageDigest.getInstance("SHA-256").digest(content); }
      catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record UploadMetadata(long length, String sha256) {}
  }
}
