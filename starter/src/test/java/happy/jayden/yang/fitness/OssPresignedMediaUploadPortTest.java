package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OssPresignedMediaUploadPortTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.14-alpine3.24")
          .withDatabaseName("fitness")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  void canonicalSignedPutSignsTheRequiredObjectMetadataWithoutExposingSecret() {
    assertThat(OssPresignedMediaUploadPort.HTTP_TIMEOUT_MILLIS).isEqualTo(10_000);
    String url =
        OssPresignedMediaUploadPort.signedPutUrl(
            "https://oss.example.test",
            "photos",
            "meal/u/m",
            "image/png",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            Instant.parse("2026-08-09T00:10:00Z"),
            "id",
            "super-secret");
    assertThat(url)
        .isEqualTo(
            "https://photos.oss.example.test/meal/u/m?OSSAccessKeyId=id&Expires=1786234200"
                + "&Signature=9P%2Fwj9dgbQqUctdUh%2FyNTE%2FOPfI%3D");
    assertThat(url).doesNotContain("super-secret");
  }

  @Test
  void verifiesDirectObjectMetadataAndReadsTheSameOssBytesForWorker() throws Exception {
    byte[] bytes = "oss-image".getBytes(StandardCharsets.UTF_8);
    String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    StringBuilder methods = new StringBuilder();
    StringBuilder hosts = new StringBuilder();
    StringBuilder authorizations = new StringBuilder();
    HttpServer oss = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    oss.createContext(
        "/bucket/meal/u/m",
        exchange -> {
          methods.append(exchange.getRequestMethod()).append(',');
          hosts.append(exchange.getRequestHeaders().getFirst("Host")).append(',');
          authorizations.append(exchange.getRequestHeaders().getFirst("Authorization")).append(',');
          exchange.getResponseHeaders().set("Content-Type", "image/png");
          exchange.getResponseHeaders().set("x-oss-meta-sha256", sha256);
          exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
          if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
          } else {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    oss.start();
    try {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres");
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      jdbc.execute(
          "CREATE TABLE media_objects(media_id UUID PRIMARY KEY,user_id UUID NOT NULL,object_key TEXT NOT NULL,"
              + "content_type TEXT NOT NULL,content_length BIGINT NOT NULL,sha256 TEXT NOT NULL,status TEXT NOT NULL,"
              + "expires_at TIMESTAMPTZ NOT NULL)");
      UUID userId = UUID.randomUUID();
      UUID mediaId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO media_objects VALUES (?,?,?,?,?,?,?,?)",
          mediaId,
          userId,
          "meal/u/m",
          "image/png",
          bytes.length,
          sha256,
          "PENDING",
          Timestamp.from(Instant.now().plusSeconds(60)));
      OssPresignedMediaUploadPort port =
          new OssPresignedMediaUploadPort(
              dataSource,
              java.time.Clock.systemUTC(),
              "http://localhost:" + oss.getAddress().getPort(),
              "bucket",
              "id",
              "secret");

      port.verifyUploaded(userId, mediaId);
      jdbc.update("UPDATE media_objects SET status='UPLOADED' WHERE media_id=?", mediaId);

      assertThat(port.readUploaded(mediaId).bytes()).isEqualTo(bytes);
      assertThat(methods.toString()).isEqualTo("HEAD,GET,");
      assertThat(hosts.toString()).startsWith("localhost:");
      assertThat(authorizations.toString()).startsWith("OSS id:");
    } finally {
      oss.stop(0);
    }
  }
}
