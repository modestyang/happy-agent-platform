package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.StarterApplication;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionCandidate;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
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
@Import(FitnessExperienceIntegrationTest.RecognitionPortConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FitnessExperienceIntegrationTest {

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
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MealRecognitionWorker recognitionWorker;
  @Autowired private ControlledRecognitionPort recognitionPort;
  @Autowired private FitnessStore fitnessStore;

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
    registry.add("happy.fitness.local-media.enabled", () -> "true");
    registry.add("happy.fitness.recognition.initial-delay-ms", () -> "3600000");
  }

  @Test
  @Order(1)
  @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
  void loginBootstrapAndMutationsUseRealPostgres() throws Exception {
    Cookie session = login();

    MvcResult bootstrapResult =
        mvc.perform(get("/api/app/bootstrap").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.nickname").value("小秦"))
            .andExpect(jsonPath("$.goal.progressPercent").isNumber())
            .andExpect(jsonPath("$.bodyRecords.length()").value(8))
            .andExpect(jsonPath("$.meals.length()").value(8))
            .andExpect(jsonPath("$.mealRecommendations.length()").value(3))
            .andExpect(jsonPath("$.mealRecommendations[0].status").value("READY"))
            .andExpect(jsonPath("$.mealRecommendations[0].items[0].name").isString())
            .andExpect(jsonPath("$.mealRecommendations[0].items[0].estimatedKcal").isNumber())
            .andExpect(jsonPath("$.plan.exercises.length()").value(4))
            .andExpect(jsonPath("$.completedWorkoutCount").value(0))
            .andExpect(jsonPath("$.exercises[0].illustrationMode").value("FOUR_STEP_IMAGES"))
            .andExpect(jsonPath("$.exercises[0].imageUrls.length()").value(4))
            .andExpect(jsonPath("$.report.status").value("READY"))
            .andExpect(jsonPath("$.report.conclusion").isString())
            .andExpect(jsonPath("$.report.metrics[0].label").value("目标进度"))
            .andExpect(jsonPath("$.ai.configured").value(false))
            .andExpect(jsonPath("$.ai.reason").value("请在 Agent 工作台配置模型 Provider"))
            .andReturn();
    JsonNode bootstrap = objectMapper.readTree(bootstrapResult.getResponse().getContentAsString());
    String workoutId = bootstrap.path("plan").path("id").asText();

    mvc.perform(
            post("/api/app/body-records")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weightJin\":145.8,\"waistCm\":82.2}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.weightJin").value(145.8));

    mvc.perform(
            post("/api/app/meals")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"mealType":"LUNCH","items":[{"name":"番茄牛肉饭","estimatedKcal":530}]}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.items[0].name").value("番茄牛肉饭"));

    mvc.perform(
            post("/api/app/workouts/{id}/complete", workoutId)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"completionRatio\":0.8}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    mvc.perform(
            post("/api/app/workouts/{id}/complete", workoutId)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"completionRatio\":0.2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completionRatio").value(0.8));

    mvc.perform(
            post("/api/app/goals")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"秋季体能计划\",\"targetWeightJin\":138,\"targetDate\":\"2026-10-01\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("秋季体能计划"));
  }

  @Test
  @Order(2)
  void dataAndDatabaseSessionSurviveANewSpringContext() throws Exception {
    Cookie session = login();

    mvc.perform(get("/api/app/bootstrap").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.goal.name").value("秋季体能计划"))
        .andExpect(jsonPath("$.bodyRecords[0].weightJin").value(145.8))
        .andExpect(jsonPath("$.meals[0].items[0].name").value("番茄牛肉饭"))
        .andExpect(jsonPath("$.plan.status").value("COMPLETED"))
        .andExpect(jsonPath("$.completedWorkoutCount").value(1));
  }

  @Test
  @Order(3)
  void bodyRecordRequiresAtLeastOneMeasurement() throws Exception {
    Cookie session = login();

    mvc.perform(
            post("/api/app/body-records")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  @Order(4)
  void unconfiguredAiReturnsRfc9457DependencyErrorInsteadOfAFakeReply() throws Exception {
    Cookie session = login();

    mvc.perform(
            post("/api/app/ai/messages")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"今天应该怎么练？\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("DEPENDENCY_NOT_CONFIGURED"));
  }

  @Test
  @Order(5)
  void logoutRevokesTheDatabaseSession() throws Exception {
    Cookie session = login();

    mvc.perform(post("/api/local/logout").cookie(session))
        .andExpect(status().isNoContent())
        .andExpect(
            header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

    mvc.perform(get("/api/app/bootstrap").cookie(session)).andExpect(status().isUnauthorized());
  }

  @Test
  @Order(6)
  void mealRecognitionUsesDurableControllerServiceJdbcAndWorkerLifecycle() throws Exception {
    recognitionPort.succeedWith("手工修改前的候选", 610, 0.91);
    byte[] image = "valid-png-image".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(image);
    Cookie owner = login();

    mvc.perform(
            post("/api/v1/app/media-upload-tickets")
                .cookie(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ticketRequest("image/png", image.length, sha256)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    MvcResult ticketResult =
        mvc.perform(
                post("/api/v1/app/media-upload-tickets")
                    .cookie(owner)
                    .header("Idempotency-Key", "ticket-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ticketRequest("image/png", image.length, sha256)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.method").value("PUT"))
            .andExpect(jsonPath("$.uploadUrl").isString())
            .andReturn();
    JsonNode ticket = objectMapper.readTree(ticketResult.getResponse().getContentAsString());
    String mediaId = ticket.path("mediaId").asText();
    assertThat(ticket.path("uploadUrl").asText())
        .isEqualTo("/api/v1/app/media-uploads/" + mediaId);

    mvc.perform(
            post("/api/v1/app/media-upload-tickets")
                .cookie(owner)
                .header("Idempotency-Key", "ticket-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ticketRequest("image/png", image.length, sha256)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.mediaId").value(mediaId));

    mvc.perform(
            post("/api/v1/app/media-upload-tickets")
                .cookie(owner)
                .header("Idempotency-Key", "ticket-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ticketRequest("image/jpeg", image.length, sha256)))
        .andExpect(status().isConflict());

    mvc.perform(
            post("/api/v1/app/media-upload-tickets")
                .cookie(owner)
                .header("Idempotency-Key", "ticket-key-0002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ticketRequest("image/gif", image.length, sha256)))
        .andExpect(status().isBadRequest());

    MvcResult expiredTicket =
        mvc.perform(
                post("/api/v1/app/media-upload-tickets")
                    .cookie(owner)
                    .header("Idempotency-Key", "ticket-key-0004")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ticketRequest("image/png", image.length, sha256)))
            .andExpect(status().isCreated())
            .andReturn();
    String expiredMediaId =
        objectMapper
            .readTree(expiredTicket.getResponse().getContentAsString())
            .path("mediaId")
            .asText();
    new org.springframework.jdbc.core.JdbcTemplate(fitnessDataSource)
        .update(
            "UPDATE media_objects SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE"
                + " media_id=?",
            UUID.fromString(expiredMediaId));
    mvc.perform(
            put("/api/v1/app/media-uploads/{mediaId}", expiredMediaId)
                .cookie(owner)
                .contentType(MediaType.IMAGE_PNG)
                .content(image))
        .andExpect(status().isNotFound());

    Cookie otherUser = createOtherUser();
    mvc.perform(
            put("/api/v1/app/media-uploads/{mediaId}", mediaId)
                .cookie(otherUser)
                .contentType(MediaType.IMAGE_PNG)
                .content(image))
        .andExpect(status().isNotFound());

    mvc.perform(
            put("/api/v1/app/media-uploads/{mediaId}", mediaId)
                .cookie(owner)
                .contentType(MediaType.IMAGE_JPEG)
                .content(image))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/app/media-uploads/{mediaId}", mediaId)
                .cookie(owner)
                .contentType(MediaType.IMAGE_PNG)
                .content("wrong-image".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/app/media-uploads/{mediaId}", mediaId)
                .cookie(owner)
                .contentType(MediaType.IMAGE_PNG)
                .content(image))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/v1/app/media-uploads/{mediaId}/complete", mediaId)
                .cookie(owner)
                .header("Idempotency-Key", "ticket-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmation\":\"DIRECT_UPLOAD_COMPLETED\"}"))
        .andExpect(status().isNoContent());

    String jobRequest =
        """
        {"mediaId":"%s","mealType":"LUNCH","occurredAt":"2026-08-09T08:00:00Z"}
        """
            .formatted(mediaId);
    mvc.perform(
            post("/api/v1/app/meal-recognition-jobs")
                .cookie(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jobRequest))
        .andExpect(status().isBadRequest());

    MvcResult jobResult =
        mvc.perform(
                post("/api/v1/app/meal-recognition-jobs")
                    .cookie(owner)
                    .header("Idempotency-Key", "job-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jobRequest))
            .andExpect(status().isAccepted())
            .andExpect(
                header()
                    .string(
                        "Location",
                        org.hamcrest.Matchers.containsString("/meal-recognition-jobs/")))
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.candidates").isEmpty())
            .andReturn();
    String jobId =
        objectMapper.readTree(jobResult.getResponse().getContentAsString()).path("jobId").asText();
    assertThat(recognitionPort.calls()).isZero();

    mvc.perform(
            post("/api/v1/app/meal-recognition-jobs")
                .cookie(owner)
                .header("Idempotency-Key", "job-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jobRequest))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value(jobId));

    mvc.perform(
            post("/api/v1/app/meal-recognition-jobs")
                .cookie(owner)
                .header("Idempotency-Key", "job-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jobRequest.replace("LUNCH", "DINNER")))
        .andExpect(status().isConflict());

    mvc.perform(get("/api/v1/app/meal-recognition-jobs/{jobId}", jobId).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"));

    recognitionWorker.runOne();

    mvc.perform(get("/api/v1/app/meal-recognition-jobs/{jobId}", jobId).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.candidates[0].name").value("手工修改前的候选"))
        .andExpect(jsonPath("$.failure").doesNotExist());
    assertThat(recognitionPort.calls()).isEqualTo(1);

    String mealRequest =
        """
{"mealType":"LUNCH","occurredAt":"2026-08-09T08:00:00Z","source":"RECOGNITION_CONFIRMED","recognitionJobId":"%s","note":"改成实际份量","items":[{"name":"手工修改后的鸡肉饭","estimatedKcal":560}]}
"""
            .formatted(jobId);
    mvc.perform(
            post("/api/v1/app/meal-records")
                .cookie(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mealRequest))
        .andExpect(status().isBadRequest());

    MvcResult mealResult =
        mvc.perform(
                post("/api/v1/app/meal-records")
                    .cookie(owner)
                    .header("Idempotency-Key", "meal-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mealRequest))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.mealRecordId").isString())
            .andExpect(jsonPath("$.id").doesNotExist())
            .andExpect(jsonPath("$.items[0].name").value("手工修改后的鸡肉饭"))
            .andExpect(jsonPath("$.nutrition.caloriesKcal").value(560))
            .andExpect(jsonPath("$.createdAt").isString())
            .andReturn();
    String mealId =
        objectMapper
            .readTree(mealResult.getResponse().getContentAsString())
            .path("mealRecordId")
            .asText();

    mvc.perform(
            post("/api/v1/app/meal-records")
                .cookie(owner)
                .header("Idempotency-Key", "meal-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mealRequest))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.mealRecordId").value(mealId));

    mvc.perform(
            post("/api/v1/app/meal-records")
                .cookie(owner)
                .header("Idempotency-Key", "meal-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mealRequest.replace("560", "561")))
        .andExpect(status().isConflict());

    MvcResult records =
        mvc.perform(get("/api/v1/app/meal-records").cookie(owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page.hasMore").value(false))
            .andReturn();
    assertThat(
            objectMapper
                .readTree(records.getResponse().getContentAsString())
                .path("items")
                .findValuesAsText("mealRecordId"))
        .contains(mealId);

    recognitionPort.failWith("TIMEOUT", "视觉模型超时");
    String failedMediaId = createAndUpload(owner, image, sha256, "ticket-key-0003");
    MvcResult failedJob =
        mvc.perform(
                post("/api/v1/app/meal-recognition-jobs")
                    .cookie(owner)
                    .header("Idempotency-Key", "job-key-0002")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"mediaId":"%s","mealType":"DINNER","occurredAt":"2026-08-09T12:00:00Z"}
                        """
                            .formatted(failedMediaId)))
            .andExpect(status().isAccepted())
            .andReturn();
    String failedJobId =
        objectMapper.readTree(failedJob.getResponse().getContentAsString()).path("jobId").asText();
    recognitionWorker.runOne();

    mvc.perform(get("/api/v1/app/meal-recognition-jobs/{jobId}", failedJobId).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.failure.code").value("TIMEOUT"))
        .andExpect(jsonPath("$.failure.message").value("视觉模型超时"))
        .andExpect(jsonPath("$.failure.retryable").value(true));
  }

  @Test
  @Order(7)
  void workerReclaimsAnExpiredRunningRecognitionJob() throws Exception {
    Cookie owner = login();
    UUID mediaId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    new org.springframework.jdbc.core.JdbcTemplate(fitnessDataSource)
        .update(
            "INSERT INTO media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status,expires_at)"
                + " VALUES (?,?,'reclaim/media','image/png',1,?,'UPLOADED',CURRENT_TIMESTAMP + INTERVAL '10 minutes')",
            mediaId,
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    new org.springframework.jdbc.core.JdbcTemplate(fitnessDataSource)
        .update(
            "INSERT INTO meal_recognition_jobs(job_id,user_id,media_id,meal_type,occurred_at,status,candidates,created_at,updated_at)"
                + " VALUES (?,?,?,'LUNCH',CURRENT_TIMESTAMP - INTERVAL '10 minutes','RUNNING','[]'::jsonb,CURRENT_TIMESTAMP - INTERVAL '10 minutes',CURRENT_TIMESTAMP - INTERVAL '10 minutes')",
            jobId,
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            mediaId);
    recognitionPort.succeedWith("恢复任务", 300, 0.9);

    recognitionWorker.runOne();

    mvc.perform(get("/api/v1/app/meal-recognition-jobs/{jobId}", jobId).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.candidates[0].name").value("恢复任务"));
  }

  @Test
  @Order(8)
  void supersededRecognitionClaimCannotOverwriteTheReclaimingWorker() {
    UUID mediaId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    org.springframework.jdbc.core.JdbcTemplate jdbc =
        new org.springframework.jdbc.core.JdbcTemplate(fitnessDataSource);
    jdbc.update(
        "INSERT INTO media_objects(media_id,user_id,object_key,content_type,content_length,sha256,status,expires_at)"
            + " VALUES (?,?,'reclaim/fenced-media','image/png',1,?,'UPLOADED',CURRENT_TIMESTAMP + INTERVAL '10 minutes')",
        mediaId,
        userId,
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    jdbc.update(
        "INSERT INTO meal_recognition_jobs(job_id,user_id,media_id,meal_type,occurred_at,status,candidates,created_at,updated_at)"
            + " VALUES (?,?,?,'LUNCH',CURRENT_TIMESTAMP,'QUEUED','[]'::jsonb,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        jobId,
        userId,
        mediaId);

    var abandonedClaim = fitnessStore.claimNextRecognitionJob().orElseThrow();
    jdbc.update(
        "UPDATE meal_recognition_jobs SET updated_at=CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE job_id=?",
        jobId);
    var recoveredClaim = fitnessStore.claimNextRecognitionJob().orElseThrow();

    fitnessStore.updateRecognitionJob(
        abandonedClaim,
        new MealRecognitionResult(
            "SUCCEEDED", List.of(new MealRecognitionCandidate("旧 worker", 1, 1.0)), null, null));
    assertThat(fitnessStore.findRecognitionJob(userId, jobId).orElseThrow().status())
        .isEqualTo("RUNNING");

    fitnessStore.updateRecognitionJob(
        recoveredClaim,
        new MealRecognitionResult(
            "SUCCEEDED", List.of(new MealRecognitionCandidate("恢复 worker", 1, 1.0)), null, null));
    assertThat(fitnessStore.findRecognitionJob(userId, jobId).orElseThrow().candidates())
        .extracting(MealRecognitionCandidate::name)
        .containsExactly("恢复 worker");
  }

  private String createAndUpload(Cookie owner, byte[] image, String sha256, String key)
      throws Exception {
    MvcResult ticket =
        mvc.perform(
                post("/api/v1/app/media-upload-tickets")
                    .cookie(owner)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ticketRequest("image/png", image.length, sha256)))
            .andExpect(status().isCreated())
            .andReturn();
    String mediaId =
        objectMapper.readTree(ticket.getResponse().getContentAsString()).path("mediaId").asText();
    mvc.perform(
            put("/api/v1/app/media-uploads/{mediaId}", mediaId)
                .cookie(owner)
                .contentType(MediaType.IMAGE_PNG)
                .content(image))
        .andExpect(status().isNoContent());
    return mediaId;
  }

  private Cookie createOtherUser() throws Exception {
    new org.springframework.jdbc.core.JdbcTemplate(fitnessDataSource)
        .update(
            "INSERT INTO users(user_id,external_subject,status,username,password_hash,nickname)"
                + " VALUES (?,'test:other','ACTIVE','other',?,'另一位用户') ON CONFLICT"
                + " (external_subject) DO NOTHING",
            UUID.fromString("10000000-0000-0000-0000-000000000002"),
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode("demo123"));
    return login("other", "demo123");
  }

  private static String ticketRequest(String contentType, int contentLength, String sha256) {
    return """
           {"purpose":"MEAL_RECOGNITION","contentType":"%s","contentLength":%d,"sha256":"%s"}
           """
        .formatted(contentType, contentLength, sha256);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private Cookie login() throws Exception {
    return login("user", "demo123");
  }

  private Cookie login(String username, String password) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/local/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
            .andExpect(status().isOk())
            .andExpect(cookie().httpOnly("FITNESS_SESSION", true))
            .andExpect(jsonPath("$.user.id").isString())
            .andReturn();
    Cookie session = result.getResponse().getCookie("FITNESS_SESSION");
    assertThat(session).isNotNull();
    return session;
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
  static class RecognitionPortConfiguration {
    @Bean
    @Primary
    ControlledRecognitionPort controlledRecognitionPort() {
      return new ControlledRecognitionPort();
    }
  }

  static final class ControlledRecognitionPort implements MealRecognitionPort {
    private final AtomicInteger calls = new AtomicInteger();
    private MealRecognitionResult result =
        new MealRecognitionResult(
            "SUCCEEDED", List.of(new MealRecognitionCandidate("默认候选", 500, 0.9)), null, null);

    @Override
    public MealRecognitionResult recognize(
        UUID userId, UUID mediaId, MealType mealType, Instant occurredAt) {
      calls.incrementAndGet();
      return result;
    }

    void succeedWith(String name, int estimatedKcal, double confidence) {
      result =
          new MealRecognitionResult(
              "SUCCEEDED",
              List.of(new MealRecognitionCandidate(name, estimatedKcal, confidence)),
              null,
              null);
    }

    void failWith(String code, String message) {
      result = new MealRecognitionResult("FAILED", List.of(), code, message);
    }

    int calls() {
      return calls.get();
    }
  }
}
