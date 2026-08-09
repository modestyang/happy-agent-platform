package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.UploadHeader;
import happy.jayden.yang.fitness.service.FitnessDtos.UploadedMedia;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** OSS direct-upload adapter with server-side HEAD/GET verification. */
final class OssPresignedMediaUploadPort implements MediaUploadPort {
  private static final Duration TTL = Duration.ofMinutes(10);
  static final int HTTP_TIMEOUT_MILLIS = 10_000;
  private static final String SHA_HEADER = "x-oss-meta-sha256";
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final String endpoint;
  private final String bucket;
  private final String accessKeyId;
  private final String accessKeySecret;

  OssPresignedMediaUploadPort(
      DataSource dataSource,
      Clock clock,
      String endpoint,
      String bucket,
      String accessKeyId,
      String accessKeySecret) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.clock = clock;
    this.endpoint = endpoint;
    this.bucket = bucket;
    this.accessKeyId = accessKeyId;
    this.accessKeySecret = accessKeySecret;
  }

  @Override
  public MediaUploadTicket createTicket(
      UUID userId, String contentType, long contentLength, String sha256) {
    requireConfigured();
    UUID id = UUID.randomUUID();
    Instant expires = clock.instant().plus(TTL);
    String key = "meal/" + userId + "/" + id;
    jdbc.update(
        "INSERT INTO"
            + " media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status,expires_at)"
            + " VALUES (?,?,?,?,?,?,'PENDING',?)",
        id,
        userId,
        key,
        contentType,
        contentLength,
        sha256,
        Timestamp.from(expires));
    return new MediaUploadTicket(
        id,
        "PUT",
        signedPutUrl(
            endpoint, bucket, key, contentType, sha256, expires, accessKeyId, accessKeySecret),
        List.of(
            new UploadHeader("Content-Type", contentType), new UploadHeader(SHA_HEADER, sha256)),
        expires,
        10_485_760);
  }

  @Override
  public void verifyUploaded(UUID userId, UUID mediaId) {
    MediaMeta meta = metadata(userId, mediaId);
    if ("UPLOADED".equals(meta.status())) return;
    try {
      HttpURLConnection connection = objectConnection("HEAD", meta.objectKey());
      try {
        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_NOT_FOUND) throw new NotFoundException("OSS 上传对象不存在");
        if (status < 200 || status >= 300)
          throw new IllegalStateException("OSS HEAD 失败: " + status);
        String actualType = normalize(connection.getHeaderField("Content-Type"));
        String actualSha = connection.getHeaderField(SHA_HEADER);
        if (connection.getContentLengthLong() != meta.contentLength()
            || !meta.contentType().equals(actualType)
            || !meta.sha256().equals(actualSha)) {
          throw new InvalidRequestException("OSS 上传对象与票据元数据不一致");
        }
      } finally {
        connection.disconnect();
      }
    } catch (IOException exception) {
      throw new IllegalStateException("无法确认 OSS 上传对象", exception);
    }
  }

  @Override
  public UploadedMedia readUploaded(UUID mediaId) {
    MediaMeta meta = metadata(mediaId);
    if (!"UPLOADED".equals(meta.status())) throw new IllegalStateException("图片未上传完成");
    try {
      HttpURLConnection connection = objectConnection("GET", meta.objectKey());
      try {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("OSS GET 失败: " + status);
        byte[] bytes;
        try (var input = connection.getInputStream()) {
          bytes = input.readAllBytes();
        }
        if (bytes.length != meta.contentLength()
            || !meta.sha256().equals(sha256(bytes))
            || !meta.contentType().equals(normalize(connection.getHeaderField("Content-Type")))) {
          throw new IllegalStateException("OSS 下载对象与票据元数据不一致");
        }
        return new UploadedMedia(meta.contentType(), bytes);
      } finally {
        connection.disconnect();
      }
    } catch (IOException exception) {
      throw new IllegalStateException("无法读取 OSS 上传对象", exception);
    }
  }

  private MediaMeta metadata(UUID userId, UUID mediaId) {
    var rows =
        jdbc.query(
            "SELECT object_key,content_type,content_length,sha256,status FROM media_objects"
                + " WHERE media_id=? AND user_id=? AND expires_at > CURRENT_TIMESTAMP",
            (rs, row) ->
                new MediaMeta(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getString(5)),
            mediaId,
            userId);
    if (rows.isEmpty()) throw new NotFoundException("上传票据不存在或已失效");
    return rows.get(0);
  }

  private MediaMeta metadata(UUID mediaId) {
    var rows =
        jdbc.query(
            "SELECT object_key,content_type,content_length,sha256,status FROM media_objects WHERE media_id=?",
            (rs, row) ->
                new MediaMeta(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getString(5)),
            mediaId);
    if (rows.isEmpty()) throw new IllegalStateException("图片不存在");
    return rows.get(0);
  }

  private HttpURLConnection objectConnection(String method, String objectKey) throws IOException {
    requireConfigured();
    URI target = objectUri(endpoint, bucket, objectKey);
    String date =
        DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(clock.instant());
    String canonical = method + "\n\n\n" + date + "\n/" + bucket + "/" + objectKey;
    HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
    connection.setReadTimeout(HTTP_TIMEOUT_MILLIS);
    connection.setRequestProperty("Date", date);
    connection.setRequestProperty(
        "Authorization", "OSS " + accessKeyId + ":" + hmac(accessKeySecret, canonical));
    return connection;
  }

  static String signedPutUrl(
      String endpoint,
      String bucket,
      String key,
      String contentType,
      String sha256,
      Instant expires,
      String id,
      String secret) {
    URI base = endpointUri(endpoint);
    if (!"https".equalsIgnoreCase(base.getScheme()))
      throw new IllegalArgumentException("OSS endpoint must use HTTPS");
    String path = "/" + bucket + "/" + key;
    String canonical =
        "PUT\n\n"
            + contentType
            + "\n"
            + expires.getEpochSecond()
            + "\n"
            + SHA_HEADER
            + ":"
            + sha256
            + "\n"
            + path;
    String signature = hmac(secret, canonical);
    return objectUri(endpoint, bucket, key).toString()
        + "?OSSAccessKeyId="
        + enc(id)
        + "&Expires="
        + expires.getEpochSecond()
        + "&Signature="
        + enc(signature);
  }

  private static URI objectUri(String endpoint, String bucket, String objectKey) {
    URI base = endpointUri(endpoint);
    String host = base.getHost();
    if (host == null || host.isBlank()) throw new IllegalArgumentException("Invalid OSS endpoint");
    if (!"https".equalsIgnoreCase(base.getScheme()) && !"localhost".equalsIgnoreCase(host)) {
      throw new IllegalArgumentException("OSS endpoint must use HTTPS");
    }
    // Only the in-process HTTP test server uses loopback path-style routing. Production OSS
    // endpoints are HTTPS and always use the virtual-hosted form below.
    if ("http".equalsIgnoreCase(base.getScheme()) && "localhost".equalsIgnoreCase(host)) {
      return URI.create(base.toString().replaceAll("/$", "") + "/" + bucket + "/" + objectKey);
    }
    String authority = bucket + "." + host + (base.getPort() < 0 ? "" : ":" + base.getPort());
    return URI.create(base.getScheme() + "://" + authority + "/" + objectKey);
  }

  private static URI endpointUri(String endpoint) {
    String value =
        endpoint.startsWith("http://") || endpoint.startsWith("https://")
            ? endpoint
            : "https://" + endpoint;
    return URI.create(value);
  }

  private void requireConfigured() {
    if (blank(endpoint) || blank(bucket) || blank(accessKeyId) || blank(accessKeySecret)) {
      throw new DependencyNotConfiguredException();
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.split(";", 2)[0].trim();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String hmac(String secret, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      return Base64.getEncoder()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private record MediaMeta(
      String objectKey, String contentType, long contentLength, String sha256, String status) {}
}
