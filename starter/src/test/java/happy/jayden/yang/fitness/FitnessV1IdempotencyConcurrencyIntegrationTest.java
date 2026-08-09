package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import happy.jayden.yang.StarterApplication;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@SpringBootTest(classes = StarterApplication.class)
@AutoConfigureMockMvc
@Import(FitnessV1IdempotencyConcurrencyIntegrationTest.RacingMediaConfiguration.class)
class FitnessV1IdempotencyConcurrencyIntegrationTest {

  private static final Path PROJECT_ROOT = projectRoot();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.14-alpine3.24")
          .withDatabaseName("happy_agent")
          .withUsername("postgres")
          .withPassword("postgres")
          .withEnv("FITNESS_DB_PASSWORD_FILE", "/run/secrets/fitness_db_password")
          .withEnv("AGENT_DB_PASSWORD_FILE", "/run/secrets/agent_db_password")
          .withCopyFileToContainer(
              MountableFile.forHostPath(PROJECT_ROOT.resolve("deploy/postgres/init.sh")),
              "/docker-entrypoint-initdb.d/00-init.sh")
          .withCopyFileToContainer(
              MountableFile.forHostPath(PROJECT_ROOT.resolve("deploy/postgres/init.sql")),
              "/usr/local/share/happy-agent-init.sql")
          .withCopyFileToContainer(
              MountableFile.forHostPath(testSecret("fitness_db_password", "fitness-test-password")),
              "/run/secrets/fitness_db_password")
          .withCopyFileToContainer(
              MountableFile.forHostPath(testSecret("agent_db_password", "agent-test-password")),
              "/run/secrets/agent_db_password");

  @Autowired private MockMvc mvc;

  @Autowired
  @Qualifier("fitnessDataSource")
  private DataSource fitnessDataSource;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("happy.datasource.fitness.url", POSTGRES::getJdbcUrl);
    registry.add("happy.datasource.agent.url", POSTGRES::getJdbcUrl);
    registry.add("happy.datasource.fitness.password", () -> "fitness-test-password");
    registry.add("happy.datasource.agent.password", () -> "agent-test-password");
    registry.add("happy.fitness.local-seed.enabled", () -> "true");
    registry.add("happy.fitness.local-media.enabled", () -> "false");
    registry.add("happy.fitness.recognition.initial-delay-ms", () -> "3600000");
  }

  @Test
  void concurrentSameKeyReplaysOneCommittedTicketWithoutASecondMediaObject() throws Exception {
    Cookie owner = login();
    String request =
        """
{"purpose":"MEAL_RECOGNITION","contentType":"image/png","contentLength":3,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
""";
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> createTicket(start, owner, request));
      var second = executor.submit(() -> createTicket(start, owner, request));
      start.countDown();

      MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
      MvcResult secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
      assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
      assertThat(firstResult.getResponse().getContentAsString())
          .contains("\"mediaId\"")
          .isEqualTo(secondResult.getResponse().getContentAsString());
    } finally {
      executor.shutdownNow();
    }

    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM media_objects WHERE object_key LIKE 'race/%'", Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM fitness_idempotency_keys WHERE operation='ticket' AND"
                    + " idempotency_key='same-key-0001'",
                Long.class))
        .isEqualTo(1L);
    assertThat(
            mvc.perform(
                    post("/api/v1/app/media-upload-tickets")
                        .cookie(owner)
                        .header("Idempotency-Key", "same-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("\"contentLength\":3", "\"contentLength\":4")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            mvc.perform(
                    post("/api/v1/app/media-upload-tickets")
                        .cookie(owner)
                        .header("Idempotency-Key", "same-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("\"contentLength\":3", "\"contentLength\":4")))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .contains("\"code\":\"IDEMPOTENCY_CONFLICT\"");
  }

  @Test
  void concurrentJobAndMealKeysReplayOneRowAndRejectChangedPayloads() throws Exception {
    Cookie owner = login();
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);
    UUID mediaId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status,expires_at)"
            + " VALUES (?,?,'race/uploaded','image/png',3,?,'UPLOADED',CURRENT_TIMESTAMP + INTERVAL '10 minutes')",
        mediaId,
        UUID.fromString("10000000-0000-0000-0000-000000000001"),
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    String jobBody =
        "{\"mediaId\":\"%s\",\"mealType\":\"LUNCH\",\"occurredAt\":\"2026-08-09T08:00:00Z\"}"
            .formatted(mediaId);

    List<MvcResult> jobs =
        concurrentPost(owner, "/api/v1/app/meal-recognition-jobs", "job-race-0001", jobBody);
    assertThat(jobs)
        .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(202));
    assertThat(jobs.get(0).getResponse().getContentAsString())
        .isEqualTo(jobs.get(1).getResponse().getContentAsString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM meal_recognition_jobs WHERE media_id=?", Long.class, mediaId))
        .isEqualTo(1L);
    assertThat(
            performPost(
                    owner,
                    "/api/v1/app/meal-recognition-jobs",
                    "job-race-0001",
                    jobBody.replace("08:00", "09:00"))
                .getResponse()
                .getStatus())
        .isEqualTo(409);

    String mealBody =
        """
        {"mealType":"DINNER","occurredAt":"2026-08-09T10:00:00Z","source":"MANUAL","note":"race-note","items":[{"name":"rice","estimatedKcal":200}]}
        """;
    List<MvcResult> meals =
        concurrentPost(owner, "/api/v1/app/meal-records", "meal-race-0001", mealBody);
    assertThat(meals)
        .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
    assertThat(meals.get(0).getResponse().getContentAsString())
        .isEqualTo(meals.get(1).getResponse().getContentAsString());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM meals WHERE note='race-note'", Long.class))
        .isEqualTo(1L);
    assertThat(
            performPost(
                    owner,
                    "/api/v1/app/meal-records",
                    "meal-race-0001",
                    mealBody.replace("race-note", "other-note"))
                .getResponse()
                .getStatus())
        .isEqualTo(409);
  }

  private MvcResult createTicket(CountDownLatch start, Cookie owner, String request) {
    try {
      start.await(10, TimeUnit.SECONDS);
      return performPost(owner, "/api/v1/app/media-upload-tickets", "same-key-0001", request);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private List<MvcResult> concurrentPost(Cookie owner, String path, String key, String request)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> awaitAndPost(start, owner, path, key, request));
      var second = executor.submit(() -> awaitAndPost(start, owner, path, key, request));
      start.countDown();
      return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  private MvcResult awaitAndPost(
      CountDownLatch start, Cookie owner, String path, String key, String request) {
    try {
      start.await(10, TimeUnit.SECONDS);
      return performPost(owner, path, key, request);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private MvcResult performPost(Cookie owner, String path, String key, String request)
      throws Exception {
    return mvc.perform(
            post(path)
                .cookie(owner)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andReturn();
  }

  private Cookie login() throws Exception {
    return mvc.perform(
            post("/api/local/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user\",\"password\":\"demo123\"}"))
        .andReturn()
        .getResponse()
        .getCookie("FITNESS_SESSION");
  }

  private static Path testSecret(String name, String value) {
    try {
      Path secret = Files.createTempFile("happy-agent-fitness-", "-" + name);
      Files.writeString(secret, value, StandardCharsets.UTF_8);
      secret.toFile().setReadable(true, false);
      secret.toFile().deleteOnExit();
      return secret;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to create Testcontainers secret file", exception);
    }
  }

  private static Path projectRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      if (Files.isRegularFile(directory.resolve("deploy/docker-compose.yml"))) {
        return directory;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Unable to locate repository root");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RacingMediaConfiguration {
    @Bean
    @Primary
    RacingMediaUploadPort racingMediaUploadPort(
        @Qualifier("fitnessDataSource") DataSource dataSource) {
      return new RacingMediaUploadPort(dataSource);
    }
  }

  static final class RacingMediaUploadPort implements MediaUploadPort {
    private final JdbcTemplate jdbc;
    private final CyclicBarrier simultaneousTickets = new CyclicBarrier(2);

    RacingMediaUploadPort(DataSource dataSource) {
      this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public MediaUploadTicket createTicket(
        UUID userId, String contentType, long contentLength, String sha256) {
      try {
        simultaneousTickets.await(10, TimeUnit.SECONDS);
      } catch (Exception exception) {
        throw new IllegalStateException("Test requests did not race", exception);
      }
      UUID mediaId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO"
              + " media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status,expires_at)"
              + " VALUES (?,?,?,?,?,?,'PENDING',?)",
          mediaId,
          userId,
          "race/" + mediaId,
          contentType,
          contentLength,
          sha256,
          java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
      return new MediaUploadTicket(
          mediaId,
          "PUT",
          "https://uploads.example.test/race/" + mediaId,
          List.of(),
          Instant.now().plusSeconds(600),
          10_485_760);
    }
  }
}
