package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.UploadHeader;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Minimal OSS v1 signed PUT adapter; secrets are only used in the signature, never returned. */
final class OssPresignedMediaUploadPort implements MediaUploadPort {
  private static final Duration TTL = Duration.ofMinutes(10);
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final String endpoint, bucket, accessKeyId, accessKeySecret;

  OssPresignedMediaUploadPort(DataSource ds, Clock clock, String endpoint, String bucket, String accessKeyId, String accessKeySecret) {
    this.jdbc = new JdbcTemplate(ds); this.clock = clock; this.endpoint = endpoint; this.bucket = bucket; this.accessKeyId = accessKeyId; this.accessKeySecret = accessKeySecret;
  }
  @Override public MediaUploadTicket createTicket(UUID userId, String contentType, long contentLength, String sha256) {
    if (blank(endpoint) || blank(bucket) || blank(accessKeyId) || blank(accessKeySecret)) throw new DependencyNotConfiguredException();
    UUID id = UUID.randomUUID(); Instant expires = clock.instant().plus(TTL); String key = "meal/" + userId + "/" + id;
    jdbc.update("INSERT INTO media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status,expires_at) VALUES (?,?,?,?,?,?,'PENDING',?)", id,userId,key,contentType,contentLength,sha256, Timestamp.from(expires));
    return new MediaUploadTicket(id,"PUT",signedPutUrl(endpoint,bucket,key,contentType,expires,accessKeyId,accessKeySecret),List.of(new UploadHeader("Content-Type",contentType)),expires,10_485_760);
  }
  static String signedPutUrl(String endpoint, String bucket, String key, String contentType, Instant expires, String id, String secret) {
    URI base = URI.create(endpoint.startsWith("https://") ? endpoint : "https://" + endpoint);
    if (!"https".equalsIgnoreCase(base.getScheme())) throw new IllegalArgumentException("OSS endpoint must use HTTPS");
    String path = "/" + bucket + "/" + key;
    String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(expires.minus(TTL));
    String canonical = "PUT\n\n" + contentType + "\n" + expires.getEpochSecond() + "\n" + path;
    String sig = hmac(secret, canonical);
    return base.getScheme()+"://"+base.getAuthority()+path+"?OSSAccessKeyId="+enc(id)+"&Expires="+expires.getEpochSecond()+"&Signature="+enc(sig);
  }
  private static String hmac(String secret,String value) { try { Mac mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA1")); return java.util.Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
  private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private static boolean blank(String s) { return s == null || s.isBlank(); }
}
