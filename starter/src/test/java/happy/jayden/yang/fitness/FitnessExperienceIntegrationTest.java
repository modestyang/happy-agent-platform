package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.sun.net.httpserver.HttpServer;
import happy.jayden.yang.StarterApplication;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcAdminWorkbenchStore;
import happy.jayden.yang.fitness.infrastructure.agent.FitnessTools;
import happy.jayden.yang.fitness.infrastructure.agent.FitnessTools.SavePlanToolRequest;
import happy.jayden.yang.fitness.infrastructure.agent.FitnessTools.ToolPlanDay;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportConclusion;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportGenerationResult;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportNarrative;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportNextAction;
import happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanGenerationResult;
import happy.jayden.yang.fitness.service.FitnessDtos.GeneratedMealRecommendation;
import happy.jayden.yang.fitness.service.FitnessDtos.MealItemDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionCandidate;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessPorts.CurrentGoalReportGenerationPort;
import happy.jayden.yang.fitness.service.FitnessPorts.DailyMealPlanGenerationPort;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

  @Test
  void localProfileEnablesLocalMediaForEndToEndMealRecognition() throws Exception {
    var properties =
        new YamlPropertySourceLoader()
            .load("application-local", new ClassPathResource("application-local.yml"));

    assertThat(properties.get(0).getProperty("happy.fitness.local-media.enabled")).isEqualTo(true);
  }

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
  @Autowired private FitnessTools fitnessTools;
  @Autowired private ControlledDailyMealPlanPort dailyMealPlanPort;
  @Autowired private DailyMealPlanGenerationWorker dailyMealPlanWorker;
  @Autowired private DailyMealPlanScheduler dailyMealPlanScheduler;
  @Autowired private ControlledCurrentGoalReportPort currentGoalReportPort;
  @Autowired private CurrentGoalReportGenerationWorker currentGoalReportWorker;

  @Test
  void confirmedPlanToolSavesDayAndWeekIdempotentlyWithoutOverwritingCompletedHistory()
      throws Exception {
    Cookie owner = login();
    UUID userId = UUID.fromString(bootstrap(owner).path("user").path("id").asText());
    var jdbc = new JdbcTemplate(fitnessDataSource);
    var exercises =
        fitnessStore.loadForAi(userId).exercises().stream()
            .limit(4)
            .map(item -> item.id())
            .toList();
    LocalDate start = LocalDate.now().plusDays(40);
    jdbc.update(
        "DELETE FROM workout_plans WHERE user_id=? AND scheduled_for BETWEEN ? AND ?",
        userId,
        start,
        start.plusDays(6));
    UUID completedPlanId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workout_plans(workout_plan_id,user_id,title,estimated_minutes,status,scheduled_for,completion_ratio,completed_at)"
            + " VALUES (?,?,?,30,'COMPLETED',?,1,CURRENT_TIMESTAMP)",
        completedPlanId,
        userId,
        "已完成历史",
        start.plusDays(2));
    var days =
        java.util.stream.IntStream.range(0, 7)
            .mapToObj(
                offset ->
                    new ToolPlanDay(
                        start.plusDays(offset), "第" + (offset + 1) + "天训练", 28, exercises))
            .toList();
    UUID approvalId = UUID.randomUUID();
    var request = new SavePlanToolRequest(approvalId, "WEEK", days);
    var context =
        new ToolExecutionContext(
            userId.toString(),
            UUID.randomUUID().toString(),
            Set.of("fitness.write"),
            "fitness.chat");

    var saved = fitnessTools.savePlan(request, context);
    var replayed = fitnessTools.savePlan(request, context);

    assertThat(saved.planIds()).hasSize(7);
    assertThat(replayed.planIds()).containsExactlyElementsOf(saved.planIds());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM workout_plans WHERE user_id=? AND scheduled_for BETWEEN ? AND ? AND status='PLANNED'",
                Integer.class,
                userId,
                start,
                start.plusDays(6)))
        .isEqualTo(7);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM workout_plans WHERE workout_plan_id=? AND status='COMPLETED'",
                Integer.class,
                completedPlanId))
        .isEqualTo(1);
  }

  @Autowired
  @Qualifier("fitnessDataSource")
  private DataSource fitnessDataSource;

  @Autowired
  @Qualifier("agentDataSource")
  private DataSource agentDataSource;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("happy.datasource.fitness.url", POSTGRES::getJdbcUrl);
    registry.add("happy.datasource.agent.url", POSTGRES::getJdbcUrl);
    registry.add("happy.datasource.fitness.password", () -> "fitness-test-password");
    registry.add("happy.datasource.agent.password", () -> "agent-test-password");
    registry.add("happy.fitness.local-seed.enabled", () -> "true");
    registry.add("happy.fitness.local-media.enabled", () -> "true");
    registry.add("happy.fitness.recognition.initial-delay-ms", () -> "3600000");
    registry.add("happy.fitness.meal-plan.initial-delay-ms", () -> "3600000");
    registry.add("happy.fitness.current-goal-report.initial-delay-ms", () -> "3600000");
  }

  @Test
  @Order(1)
  void registerCreatesAnAuthenticatedAccount() throws Exception {
    MvcResult registration =
        mvc.perform(
                post("/api/local/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"new-user\",\"nickname\":\"新用户\",\"password\":\"strong-password\"}"))
            .andExpect(status().isOk())
            .andExpect(cookie().httpOnly("FITNESS_SESSION", true))
            .andExpect(jsonPath("$.user.nickname").value("新用户"))
            .andReturn();

    Cookie session = registration.getResponse().getCookie("FITNESS_SESSION");
    assertThat(session).isNotNull();

    mvc.perform(get("/api/app/bootstrap").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.nickname").value("新用户"))
        .andExpect(jsonPath("$.onboarding.state").value("REQUIRED"))
        .andExpect(jsonPath("$.goal").doesNotExist());
  }

  @Test
  @Order(2)
  void firstSetupCreatesTheInitialBodyRecordAndGoal() throws Exception {
    MvcResult registration =
        mvc.perform(
                post("/api/local/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"first-setup-user\",\"nickname\":\"首次设置用户\",\"password\":\"strong-password\"}"))
            .andExpect(status().isOk())
            .andReturn();
    Cookie session = registration.getResponse().getCookie("FITNESS_SESSION");
    assertThat(session).isNotNull();

    mvc.perform(
            post("/api/app/first-setup")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"weightJin\":128.6,\"waistCm\":72.5,\"targetWeightJin\":118.0,\"targetDate\":\"2026-12-31\",\"trainingProfile\":{\"biologicalSex\":\"FEMALE\",\"experienceLevel\":\"BEGINNER\",\"trainingVenues\":[\"HOME\"],\"availableEquipment\":[\"瑜伽垫\"],\"trainingWeekdays\":[1,3,5],\"sessionMinutes\":30,\"trainingRestrictions\":[\"避免跳跃\"]}}"))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/app/bootstrap").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onboarding.state").value("COMPLETE"))
        .andExpect(jsonPath("$.bodyRecords.length()").value(1))
        .andExpect(jsonPath("$.bodyRecords[0].weightJin").value(128.6))
        .andExpect(jsonPath("$.bodyRecords[0].waistCm").value(72.5))
        .andExpect(jsonPath("$.goal.startWeightJin").value(128.6))
        .andExpect(jsonPath("$.goal.targetWeightJin").value(118.0))
        .andExpect(jsonPath("$.trainingProfile.biologicalSex").value("FEMALE"))
        .andExpect(jsonPath("$.trainingProfile.availableEquipment[0]").value("瑜伽垫"));

    mvc.perform(
            put("/api/app/training-profile")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"biologicalSex\":\"FEMALE\",\"heightCm\":165.5,\"experienceLevel\":\"BEGINNER\",\"trainingVenues\":[\"HOME\",\"OUTDOOR\"],\"availableEquipment\":[\"弹力带\"],\"trainingWeekdays\":[2,4],\"sessionMinutes\":40,\"trainingRestrictions\":[\"避免跳跃\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionMinutes").value(40))
        .andExpect(jsonPath("$.trainingVenues.length()").value(2));

    mvc.perform(
            post("/api/app/first-setup")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"weightJin\":128.6,\"targetWeightJin\":118.0,\"targetDate\":\"2026-12-31\",\"trainingProfile\":{\"biologicalSex\":\"FEMALE\",\"experienceLevel\":\"BEGINNER\",\"trainingVenues\":[\"HOME\"],\"availableEquipment\":[],\"trainingWeekdays\":[],\"sessionMinutes\":30,\"trainingRestrictions\":[]}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(3)
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
  @Order(4)
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
  @Order(5)
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
  @Order(7)
  void logoutRevokesTheDatabaseSession() throws Exception {
    Cookie session = login();

    mvc.perform(post("/api/local/logout").cookie(session))
        .andExpect(status().isNoContent())
        .andExpect(
            header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

    mvc.perform(get("/api/app/bootstrap").cookie(session)).andExpect(status().isUnauthorized());
  }

  @Test
  void feedbackIsOwnerScopedUpsertedAndReturnedByBootstrap() throws Exception {
    Cookie owner = login();
    String recommendationId =
        objectMapper
            .readTree(
                mvc.perform(get("/api/app/bootstrap").cookie(owner))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("mealRecommendations")
            .get(0)
            .path("id")
            .asText();

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"LIKE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sentiment").value("LIKE"));

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-key-0002")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"DISLIKE\",\"reason\":\"OTHER\",\"note\":\"不喜欢香菜\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("OTHER"));

    mvc.perform(get("/api/app/bootstrap").cookie(owner))
        .andExpect(jsonPath("$.mealRecommendations[0].feedback.sentiment").value("DISLIKE"))
        .andExpect(jsonPath("$.mealRecommendations[0].feedback.note").value("不喜欢香菜"));

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(createOtherUser())
                .header("Idempotency-Key", "feedback-key-other")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"LIKE\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void feedbackRejectsEveryNoteLongerThanTheContractLimitBeforePersistence() throws Exception {
    Cookie owner = login();
    String recommendationId =
        objectMapper
            .readTree(
                mvc.perform(get("/api/app/bootstrap").cookie(owner))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("mealRecommendations")
            .get(0)
            .path("id")
            .asText();

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-note-too-long")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sentiment\":\"DISLIKE\",\"reason\":\"TASTE\",\"note\":\""
                        + "x".repeat(301)
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void feedbackHttpContractAcceptsOnlyTheThreeDocumentedBranches() throws Exception {
    Cookie owner = login();
    String recommendationId = firstRecommendationId(owner);

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-like-branch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"LIKE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sentiment").value("LIKE"));

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-like-extra")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"LIKE\",\"reason\":\"TASTE\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-dislike-missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"DISLIKE\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-dislike-branch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"DISLIKE\",\"reason\":\"TASTE\",\"note\":\"太甜\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("TASTE"));

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-other-blank")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"DISLIKE\",\"reason\":\"OTHER\",\"note\":\"   \"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-other-tab-newline")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"DISLIKE\",\"reason\":\"OTHER\",\"note\":\"\\t\\n\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-other-branch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sentiment\":\"DISLIKE\",\"reason\":\"OTHER\",\"note\":\" \\t不喜欢香菜\\n \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("OTHER"));
  }

  @Test
  void feedbackHttpRejectsEveryConfiguredWhitespaceOnlyOtherNote() throws Exception {
    Cookie owner = login();
    String recommendationId = firstRecommendationId(owner);

    for (String whitespaceOnlyNote :
        List.of(
            " ", "\t", "\r", "\n", "\u0085", "\u00a0", "\u1680", "\u2000", "\u200a", "\u2028",
            "\u2029", "\u202f", "\u205f", "\u3000", "\ufeff")) {
      mvc.perform(
              put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                  .cookie(owner)
                  .header(
                      "Idempotency-Key",
                      "feedback-unicode-http-"
                          + Integer.toHexString(whitespaceOnlyNote.codePointAt(0)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapper.writeValueAsString(
                          java.util.Map.of(
                              "sentiment",
                              "DISLIKE",
                              "reason",
                              "OTHER",
                              "note",
                              whitespaceOnlyNote))))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void feedbackHttpAcceptsMixedConfiguredWhitespaceAndVisibleOtherNote() throws Exception {
    Cookie owner = login();
    String recommendationId = firstRecommendationId(owner);
    String note = "\u00a0\u2003\ufeff 不喜欢香菜！\t\n";

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-unicode-visible-http")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        java.util.Map.of("sentiment", "DISLIKE", "reason", "OTHER", "note", note))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("OTHER"));
  }

  @Test
  void feedbackHttpRejectsNulButAcceptsVisibleOtherNote() throws Exception {
    Cookie owner = login();
    String recommendationId = firstRecommendationId(owner);

    for (String note : List.of("\u0000", "a\u0000")) {
      mvc.perform(
              put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                  .cookie(owner)
                  .header(
                      "Idempotency-Key",
                      "feedback-nul-http-" + Integer.toHexString(note.hashCode()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapper.writeValueAsString(
                          java.util.Map.of(
                              "sentiment", "DISLIKE", "reason", "OTHER", "note", note))))
          .andExpect(status().isBadRequest());
    }

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-visible-http")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        java.util.Map.of(
                            "sentiment", "DISLIKE", "reason", "OTHER", "note", "正常可见说明"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("OTHER"));
  }

  @Test
  void feedbackHttpUsesRawCodePointLimitsForOtherNotes() throws Exception {
    Cookie owner = login();
    String recommendationId = firstRecommendationId(owner);

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-max-codepoints-http")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        java.util.Map.of(
                            "sentiment", "DISLIKE", "reason", "OTHER", "note", "🚀".repeat(300)))))
        .andExpect(status().isOk());

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-over-codepoints-http")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        java.util.Map.of(
                            "sentiment", "DISLIKE", "reason", "OTHER", "note", "🚀".repeat(301)))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void feedbackDatabaseConstraintsRejectInvalidRows() throws Exception {
    Cookie owner = login();
    JsonNode ownerBootstrap = bootstrap(owner);
    UUID ownerId = UUID.fromString(ownerBootstrap.path("user").path("id").asText());
    createOtherUser();
    UUID otherUserId = UUID.fromString("10000000-0000-0000-0000-000000000002");
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment) VALUES (?,?, 'LIKE')",
                    otherUserId,
                    ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 1))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason) VALUES (?,?, 'LIKE','TASTE')",
                    ownerId,
                    ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 2))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment) VALUES (?,?, 'DISLIKE')",
                    ownerId,
                    ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 3))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason) VALUES (?,?, 'DISLIKE','OTHER')",
                    ownerId,
                    ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 4))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','OTHER','   ')",
                    ownerId,
                    ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 5))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','OTHER',?)",
                    ownerId,
                    ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 6)),
                    "\t\n"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','TASTE',?)",
                    ownerId,
                    ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 7)),
                    "x".repeat(301)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void feedbackDatabaseConstraintsRejectEveryConfiguredWhitespaceOnlyOtherNote() throws Exception {
    Cookie owner = login();
    UUID ownerId = UUID.fromString(bootstrap(owner).path("user").path("id").asText());
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);
    LocalDate date = LocalDate.of(2026, 10, 1);

    for (String whitespaceOnlyNote :
        List.of(
            " ", "\t", "\r", "\n", "\u0085", "\u00a0", "\u1680", "\u2000", "\u200a", "\u2028",
            "\u2029", "\u202f", "\u205f", "\u3000", "\ufeff")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','OTHER',?)",
                      ownerId,
                      ownedRecommendation(
                          jdbc, ownerId, date.plusDays(whitespaceOnlyNote.codePointAt(0))),
                      whitespaceOnlyNote))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void feedbackDatabaseConstraintsAcceptDocumentedBranches() throws Exception {
    Cookie owner = login();
    JsonNode ownerBootstrap = bootstrap(owner);
    UUID ownerId = UUID.fromString(ownerBootstrap.path("user").path("id").asText());
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);

    assertThat(
            jdbc.update(
                "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment) VALUES (?,?, 'LIKE')",
                ownerId,
                ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 8))))
        .isEqualTo(1);
    assertThat(
            jdbc.update(
                "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','OTHER',?)",
                ownerId,
                ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 9)),
                "🚀".repeat(300)))
        .isEqualTo(1);
    assertThat(
            jdbc.update(
                "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','OTHER',?)",
                ownerId,
                ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 10)),
                "\u00a0\u2003\ufeff 不喜欢香菜！\t\n"))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?, 'DISLIKE','OTHER',?)",
                    ownerId,
                    ownedRecommendation(jdbc, ownerId, LocalDate.of(2026, 9, 11)),
                    "🚀".repeat(301)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void feedbackQueryTreatsFreeTextAsNonExecutableReferenceData() throws Exception {
    Cookie owner = login();
    JsonNode bootstrap =
        objectMapper.readTree(
            mvc.perform(get("/api/app/bootstrap").cookie(owner))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String recommendationId = bootstrap.path("mealRecommendations").get(0).path("id").asText();
    UUID userId = UUID.fromString(bootstrap.path("user").path("id").asText());

    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "feedback-context-bounded")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sentiment\":\"DISLIKE\",\"reason\":\"OTHER\",\"note\":\""
                        + "x".repeat(300)
                        + "\"}"))
        .andExpect(status().isOk());

    var context =
        fitnessTools.agentMealFeedbackContext(
            new ToolExecutionContext(
                userId.toString(),
                "meal-feedback-context-run",
                Set.of("fitness.read"),
                "daily-or-manual-meal-generation"));
    assertThat(context.dislikedFoods()).isNotEmpty();
    assertThat(context.noteReferences())
        .allSatisfy(
            reference -> {
              assertThat(reference.executable()).isFalse();
              assertThat(reference.text().codePointCount(0, reference.text().length()))
                  .isLessThanOrEqualTo(300);
            });
  }

  @Test
  void dailyMealPlanReadEndpointReturnsThePersistedThreeMealPlan() throws Exception {
    Cookie owner = login();

    mvc.perform(get("/api/v1/app/meal-plans/daily").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.breakfast.mealType").value("BREAKFAST"))
        .andExpect(jsonPath("$.lunch.mealType").value("LUNCH"))
        .andExpect(jsonPath("$.dinner.mealType").value("DINNER"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void manualDailyMealGenerationOnlyEnqueuesBeforeTheRuntimeIsCalled() throws Exception {
    drainDailyMealPlanQueue();
    dailyMealPlanPort.succeed();
    Cookie owner = login();
    String date = "2031-04-17";
    JsonNode bootstrap =
        objectMapper.readTree(
            mvc.perform(get("/api/app/bootstrap").cookie(owner))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String recommendationId = bootstrap.path("mealRecommendations").get(0).path("id").asText();
    mvc.perform(
            put("/api/v1/app/meal-recommendations/{recommendationId}/feedback", recommendationId)
                .cookie(owner)
                .header("Idempotency-Key", "daily-plan-like-context")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sentiment\":\"LIKE\"}"))
        .andExpect(status().isOk());

    MvcResult generated =
        mvc.perform(
                post("/api/v1/app/meal-plans/daily/generate")
                    .cookie(owner)
                    .header("Idempotency-Key", "daily-plan-generate-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"date\":\"%s\"}".formatted(date)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("GENERATING"))
            .andReturn();
    JsonNode acceptedJson = objectMapper.readTree(generated.getResponse().getContentAsString());
    assertThat(acceptedJson.has("breakfast")).isFalse();
    assertThat(acceptedJson.has("lunch")).isFalse();
    assertThat(acceptedJson.has("dinner")).isFalse();
    assertThat(acceptedJson.has("dailyNutrition")).isFalse();
    assertThat(acceptedJson.has("failure")).isFalse();
    String planId =
        objectMapper
            .readTree(generated.getResponse().getContentAsString())
            .path("mealPlanId")
            .asText();
    assertThat(dailyMealPlanPort.calls()).isZero();

    mvc.perform(get("/api/v1/app/meal-plans/daily?date=" + date).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mealPlanId").value(planId))
        .andExpect(jsonPath("$.status").value("GENERATING"));

    mvc.perform(
            post("/api/v1/app/meal-plans/daily/generate")
                .cookie(owner)
                .header("Idempotency-Key", "daily-plan-generate-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"%s\"}".formatted(date)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.mealPlanId").value(planId));
    assertThat(dailyMealPlanPort.calls()).isZero();

    dailyMealPlanWorker.runOne();
    assertThat(dailyMealPlanPort.calls()).isEqualTo(1);

    MvcResult ready =
        mvc.perform(get("/api/v1/app/meal-plans/daily?date=" + date).cookie(owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mealPlanId").value(planId))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.breakfast.mealType").value("BREAKFAST"))
            .andReturn();
    JsonNode readyJson = objectMapper.readTree(ready.getResponse().getContentAsString());
    assertThat(readyJson.has("failure")).isFalse();
    assertThat(readyJson.has("breakfast")).isTrue();
    assertThat(readyJson.has("lunch")).isTrue();
    assertThat(readyJson.has("dinner")).isTrue();
    assertThat(readyJson.has("dailyNutrition")).isTrue();
  }

  @Test
  void manualDailyMealGenerationReplacesAPersistedEnglishReadyPlan() throws Exception {
    dailyMealPlanPort.succeed();
    Cookie owner = login();
    String date = "2031-04-18";

    mvc.perform(
            post("/api/v1/app/meal-plans/daily/generate")
                .cookie(owner)
                .header("Idempotency-Key", "daily-plan-generate-english-seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"%s\"}".formatted(date)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("GENERATING"));
    dailyMealPlanWorker.runOne();

    new JdbcTemplate(fitnessDataSource)
        .update(
            "UPDATE daily_meal_recommendations SET items='[{\"name\":\"Greek yogurt\",\"estimatedKcal\":320}]'::jsonb,"
                + " reason='High protein meal' WHERE recommendation_date=?",
            LocalDate.parse(date));

    dailyMealPlanPort.succeed();
    mvc.perform(
            post("/api/v1/app/meal-plans/daily/generate")
                .cookie(owner)
                .header("Idempotency-Key", "daily-plan-regenerate-english")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"%s\"}".formatted(date)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("GENERATING"));

    dailyMealPlanWorker.runOne();
    mvc.perform(get("/api/v1/app/meal-plans/daily?date=" + date).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.breakfast.items[0].name").value("反馈早餐"));
  }

  @Test
  void dailyMealGenerationEnqueuesFailureProneWorkWithoutCallingTheRuntime() throws Exception {
    Cookie owner = login();
    dailyMealPlanPort.failWith("DEPENDENCY_UNAVAILABLE", "三餐模型暂时不可达");

    mvc.perform(
            post("/api/v1/app/meal-plans/daily/generate")
                .cookie(owner)
                .header("Idempotency-Key", "daily-plan-generate-failed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-08-11\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("GENERATING"))
        .andExpect(jsonPath("$.breakfast").doesNotExist())
        .andExpect(jsonPath("$.failure").doesNotExist());

    mvc.perform(get("/api/v1/app/meal-plans/daily?date=2026-08-11").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("GENERATING"))
        .andExpect(jsonPath("$.breakfast").doesNotExist());
    assertThat(dailyMealPlanPort.calls()).isZero();

    dailyMealPlanWorker.runOne();
    assertThat(dailyMealPlanPort.calls()).isEqualTo(1);
    MvcResult failed =
        mvc.perform(get("/api/v1/app/meal-plans/daily?date=2026-08-11").cookie(owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failure.code").value("TASK_FAILED"))
            .andReturn();
    JsonNode failedJson = objectMapper.readTree(failed.getResponse().getContentAsString());
    assertThat(failedJson.has("breakfast")).isFalse();
    assertThat(failedJson.has("lunch")).isFalse();
    assertThat(failedJson.has("dinner")).isFalse();
    assertThat(failedJson.has("dailyNutrition")).isFalse();

    dailyMealPlanPort.succeed();
    mvc.perform(
            post("/api/v1/app/meal-plans/daily/generate")
                .cookie(owner)
                .header("Idempotency-Key", "daily-plan-generate-retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-08-11\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("GENERATING"));
    dailyMealPlanWorker.runOne();
    mvc.perform(get("/api/v1/app/meal-plans/daily?date=2026-08-11").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"));
  }

  @Test
  void manualReentrySharesOneRunAndTheWorkerInvokesTheRuntimeOnce() throws Exception {
    drainDailyMealPlanQueue();
    dailyMealPlanPort.succeed();
    Cookie owner = login();
    String date = LocalDate.now().plusYears(10).toString();

    MvcResult first =
        mvc.perform(
                post("/api/v1/app/meal-plans/daily/generate")
                    .cookie(owner)
                    .header("Idempotency-Key", "daily-plan-manual-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"date\":\"%s\"}".formatted(date)))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Retry-After", "1"))
            .andReturn();
    MvcResult second =
        mvc.perform(
                post("/api/v1/app/meal-plans/daily/generate")
                    .cookie(owner)
                    .header("Idempotency-Key", "daily-plan-manual-b")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"date\":\"%s\"}".formatted(date)))
            .andExpect(status().isAccepted())
            .andReturn();
    assertThat(first.getResponse().getHeader("Location")).isEqualTo("/api/v1/app/meal-plans/daily");
    assertThat(
            objectMapper
                .readTree(first.getResponse().getContentAsString())
                .path("mealPlanId")
                .asText())
        .isEqualTo(
            objectMapper
                .readTree(second.getResponse().getContentAsString())
                .path("mealPlanId")
                .asText());
    assertThat(dailyMealPlanPort.calls()).isZero();

    dailyMealPlanWorker.runOne();
    dailyMealPlanWorker.runOne();
    assertThat(dailyMealPlanPort.calls()).isEqualTo(1);
    mvc.perform(get("/api/v1/app/meal-plans/daily?date=" + date).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"));
  }

  @Test
  void concurrentManualRequestsAtomicallyReuseOneDailyPlanRun() throws Exception {
    dailyMealPlanPort.succeed();
    Cookie owner = login();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CyclicBarrier start = new CyclicBarrier(2);
    try {
      var first =
          executor.submit(() -> concurrentPlanRequest(owner, start, "daily-plan-concurrent-a"));
      var second =
          executor.submit(() -> concurrentPlanRequest(owner, start, "daily-plan-concurrent-b"));

      assertThat(first.get()).isEqualTo(second.get());
      assertThat(dailyMealPlanPort.calls()).isZero();
      drainDailyMealPlanQueue();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void schedulerReentryOnlyEnqueuesOneRunPerActiveUserWithoutInvokingTheRuntime() {
    dailyMealPlanPort.succeed();
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);

    dailyMealPlanScheduler.generateAtFiveThirty();
    dailyMealPlanScheduler.generateAtFiveThirty();

    assertThat(dailyMealPlanPort.calls()).isZero();
    Integer runCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM daily_meal_plan_runs WHERE plan_date=CURRENT_DATE",
            Integer.class);
    Integer distinctUserCount =
        jdbc.queryForObject(
            "SELECT count(DISTINCT user_id) FROM daily_meal_plan_runs WHERE plan_date=CURRENT_DATE",
            Integer.class);
    assertThat(runCount).isEqualTo(distinctUserCount);
    drainDailyMealPlanQueue();
  }

  @Test
  void schedulerSkipsUsersInactiveForFourteenDays() {
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);
    UUID activeUser = UUID.randomUUID();
    UUID inactiveUser = UUID.randomUUID();
    LocalDate today = LocalDate.now();
    try {
      jdbc.update(
          "INSERT INTO users(user_id,external_subject,status,updated_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP)",
          activeUser,
          "test:meal-active:" + activeUser);
      jdbc.update(
          "INSERT INTO users(user_id,external_subject,status,updated_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP - INTERVAL '15 days')",
          inactiveUser,
          "test:meal-inactive:" + inactiveUser);
      jdbc.update(
          "INSERT INTO goals(goal_id,user_id,name,start_weight_jin,target_weight_jin,status) VALUES (?,?, '测试目标',140,130,'ACTIVE')",
          UUID.randomUUID(),
          activeUser);
      jdbc.update(
          "INSERT INTO goals(goal_id,user_id,name,start_weight_jin,target_weight_jin,status) VALUES (?,?, '测试目标',140,130,'ACTIVE')",
          UUID.randomUUID(),
          inactiveUser);

      var eligible =
          fitnessStore.dailyMealPlanEligibleUserIds(
              Instant.now().minus(Duration.ofDays(14)), today);

      assertThat(eligible).contains(activeUser).doesNotContain(inactiveUser);
    } finally {
      jdbc.update("DELETE FROM users WHERE user_id IN (?,?)", activeUser, inactiveUser);
    }
  }

  @Test
  void schedulerEligibilityTreatsLegacyReadyRecommendationsAsAnExistingPlan() {
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);
    UUID userId = UUID.randomUUID();
    LocalDate date = LocalDate.now().plusDays(1);
    try {
      jdbc.update(
          "INSERT INTO users(user_id,external_subject,status,updated_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP)",
          userId,
          "test:meal-ready:" + userId);
      jdbc.update(
          "INSERT INTO goals(goal_id,user_id,name,start_weight_jin,target_weight_jin,status) VALUES (?,?, '测试目标',140,130,'ACTIVE')",
          UUID.randomUUID(),
          userId);
      for (String mealType : List.of("BREAKFAST", "LUNCH", "DINNER")) {
        jdbc.update(
            "INSERT INTO daily_meal_recommendations(recommendation_id,user_id,recommendation_date,meal_type,items,reason,status)"
                + " VALUES (?,?,?,?,?::jsonb,?,'READY')",
            UUID.randomUUID(),
            userId,
            date,
            mealType,
            "[{\"name\":\"已有推荐\",\"estimatedKcal\":400}]",
            "已有完整计划");
      }

      assertThat(
              fitnessStore.dailyMealPlanEligibleUserIds(
                  Instant.now().minus(Duration.ofDays(14)), date))
          .doesNotContain(userId);
    } finally {
      jdbc.update("DELETE FROM users WHERE user_id=?", userId);
    }
  }

  @Test
  void returningUserIsReactivatedAndReceivesOneMissingDailyPlan() throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    MvcResult registration =
        mvc.perform(
                post("/api/local/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"return-%s\",\"nickname\":\"回访用户\",\"password\":\"strong-password\"}"
                            .formatted(suffix)))
            .andExpect(status().isOk())
            .andReturn();
    Cookie session = registration.getResponse().getCookie("FITNESS_SESSION");
    UUID userId =
        UUID.fromString(
            objectMapper
                .readTree(registration.getResponse().getContentAsString())
                .path("user")
                .path("id")
                .asText());
    try {
      mvc.perform(
              post("/api/app/first-setup")
                  .cookie(session)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"weightJin\":140,\"targetWeightJin\":130,\"targetDate\":\"2026-12-31\",\"trainingProfile\":{\"biologicalSex\":\"NOT_DISCLOSED\",\"experienceLevel\":\"BEGINNER\",\"trainingVenues\":[\"HOME\"],\"availableEquipment\":[],\"trainingWeekdays\":[],\"sessionMinutes\":30,\"trainingRestrictions\":[]}}"))
          .andExpect(status().isCreated());
      jdbc.update(
          "UPDATE users SET updated_at=CURRENT_TIMESTAMP - INTERVAL '15 days' WHERE user_id=?",
          userId);

      mvc.perform(get("/api/app/bootstrap").cookie(session)).andExpect(status().isOk());
      mvc.perform(get("/api/app/bootstrap").cookie(session)).andExpect(status().isOk());

      Integer planCount =
          jdbc.queryForObject(
              "SELECT count(*) FROM daily_meal_plan_runs WHERE user_id=? AND plan_date=CURRENT_DATE",
              Integer.class,
              userId);
      Boolean activeRecently =
          jdbc.queryForObject(
              "SELECT updated_at > CURRENT_TIMESTAMP - INTERVAL '1 minute' FROM users WHERE user_id=?",
              Boolean.class,
              userId);
      assertThat(planCount).isEqualTo(1);
      assertThat(activeRecently).isTrue();
    } finally {
      jdbc.update("DELETE FROM users WHERE user_id=?", userId);
    }
  }

  @Test
  void supersededDailyMealPlanClaimCannotCompleteOrFailAfterAReclaim() {
    UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    LocalDate date = LocalDate.of(2026, 8, 13);
    var run = fitnessStore.enqueueDailyMealPlanGeneration(userId, date);
    var abandonedClaim = fitnessStore.claimNextDailyMealPlanGeneration().orElseThrow();
    assertThat(abandonedClaim.run().mealPlanId()).isEqualTo(run.mealPlanId());

    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);
    jdbc.update(
        "UPDATE daily_meal_plan_runs SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE meal_plan_id=?",
        run.mealPlanId());
    assertThat(
            fitnessStore.completeDailyMealPlanGeneration(
                abandonedClaim, dailyPlanResult("过期 worker")))
        .isFalse();
    assertThat(
            fitnessStore.failDailyMealPlanGeneration(abandonedClaim, "RUNTIME_ERROR", "过期 worker"))
        .isFalse();
    assertThat(fitnessStore.findDailyMealPlan(userId, date).orElseThrow().run().status())
        .isEqualTo("GENERATING");

    var reclaimedClaim = fitnessStore.claimNextDailyMealPlanGeneration().orElseThrow();
    assertThat(reclaimedClaim.run().mealPlanId()).isEqualTo(run.mealPlanId());
    assertThat(reclaimedClaim.run().version()).isGreaterThan(abandonedClaim.run().version());
    assertThat(fitnessStore.claimNextDailyMealPlanGeneration()).isEmpty();

    assertThat(
            fitnessStore.completeDailyMealPlanGeneration(
                abandonedClaim, dailyPlanResult("旧 worker")))
        .isFalse();
    assertThat(
            fitnessStore.failDailyMealPlanGeneration(abandonedClaim, "RUNTIME_ERROR", "旧 worker"))
        .isFalse();
    assertThat(
            fitnessStore.completeDailyMealPlanGeneration(
                reclaimedClaim, dailyPlanResult("恢复 worker")))
        .isTrue();
    assertThat(fitnessStore.findDailyMealPlan(userId, date).orElseThrow().run().status())
        .isEqualTo("READY");
    assertThat(fitnessStore.findDailyMealPlan(userId, date).orElseThrow().recommendations())
        .flatExtracting(recommendation -> recommendation.items())
        .extracting(MealItemDto::name)
        .containsOnly("恢复 worker");
  }

  @Test
  void recognitionResolvesTheImmutablePublishedAgentRuntime() throws Exception {
    JdbcTemplate agentJdbc = new JdbcTemplate(agentDataSource);
    agentJdbc.update("DELETE FROM agent_versions WHERE agent_key='fitness.coach'");
    upsertMealRuntimeComponents(
        agentJdbc,
        "published-provider",
        "published-model",
        "{\"providerKey\":\"published-provider\",\"model\":\"published-model\",\"vision\":true}");
    publishMealRuntimeSnapshot(
        agentJdbc, 81, publishedUnifiedRuntimeSnapshot("published-provider", "published-model"));

    upsertMealRuntimeComponents(
        agentJdbc,
        "mutable-draft-provider",
        "mutable-draft-model",
        "{\"providerKey\":\"mutable-draft-provider\",\"model\":\"mutable-draft-model\",\"vision\":false}");
    agentJdbc.update(
        "UPDATE agent_drafts SET provider_key='mutable-draft-provider',model_key='mutable-draft-model' WHERE agent_key='fitness.coach'");
    agentJdbc.update(
        "UPDATE agent_providers SET endpoint='https://mutable.example/v1' WHERE provider_key='published-provider'");
    agentJdbc.update(
        "UPDATE agent_models SET model_id='mutable-model',supports_vision=false WHERE model_key='published-model'");

    var recognition =
        new MealRecognitionRuntime(
                agentDataSource, agentDataSource, objectMapper, "build/missing-agent-master-key")
            .config();

    assertThat(recognition.providerKey()).isEqualTo("published-provider");
    assertThat(recognition.model()).isEqualTo("published-model");
    assertThat(recognition.endpoint()).isEqualTo("https://example.test/v1");
    assertThat(recognition.credentialKeyVersion()).isEqualTo(1);
  }

  @Test
  void currentGoalReportRuntimeUsesPublishedBindingsAndRejectsNonSchemaNarrative()
      throws Exception {
    JdbcTemplate agentJdbc = new JdbcTemplate(agentDataSource);
    agentJdbc.update("DELETE FROM agent_versions WHERE agent_key='fitness.coach'");
    upsertMealRuntimeComponents(
        agentJdbc,
        "mutable-draft-provider",
        "mutable-draft-model",
        "{\"providerKey\":\"mutable-draft-provider\",\"model\":\"mutable-draft-model\",\"vision\":false}");
    agentJdbc.update(
        "UPDATE agent_drafts SET provider_key='mutable-draft-provider',model_key='mutable-draft-model' WHERE agent_key='fitness.coach'");
    upsertMealRuntimeComponents(
        agentJdbc,
        "published-provider",
        "published-model",
        "{\"providerKey\":\"published-provider\",\"model\":\"published-model\"}");
    publishMealRuntimeSnapshot(
        agentJdbc,
        91,
        publishedCurrentGoalRuntimeSnapshot("published-provider", "published-model"));
    CurrentGoalReportRuntime runtime =
        new CurrentGoalReportRuntime(
            agentDataSource, objectMapper, "build/missing-agent-master-key");

    var publishedConfig = runtime.config();
    assertThat(publishedConfig.providerKey()).isEqualTo("published-provider");
    assertThat(publishedConfig.model()).isEqualTo("published-model");
    JsonNode request =
        objectMapper.valueToTree(runtime.requestBody(publishedConfig, currentGoalReportFacts()));
    assertThat(request.path("max_tokens").asInt()).isEqualTo(2000);
    assertThat(request.path("messages").get(0).path("content").asText())
        .contains("conclusion", "highlights", "weaknesses", "nextActions", "OPEN_RECORD");
    assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_schema");
    assertThat(request.path("response_format").path("json_schema").path("strict").asBoolean())
        .isTrue();
    assertThat(
            request
                .path("response_format")
                .path("json_schema")
                .path("schema")
                .path("properties")
                .size())
        .isEqualTo(4);

    var narrative =
        runtime.narrative(
            objectMapper.readTree(
                """
                {"conclusion":{"summary":"节奏稳定","score":82,"grade":"B"},"highlights":["完成本周记录","体重趋势清晰"],"weaknesses":["训练量还可增加"],"nextActions":[{"title":"补齐记录","rationale":"让报告保持最新","action":"OPEN_RECORD"}]}
                """));
    assertThat(narrative.conclusion().score()).isEqualTo(82);
    assertThatThrownBy(
            () ->
                runtime.narrative(
                    objectMapper.readTree(
                        """
                        {"conclusion":{"summary":"<b>不安全</b>","score":"82","grade":"B"},"highlights":["一","二"],"weaknesses":["三"],"nextActions":[{"title":"四","rationale":"五","action":"OPEN_RECORD"}]}
                        """)))
        .isInstanceOf(IllegalArgumentException.class);

    agentJdbc.update("DELETE FROM agent_versions WHERE agent_key='fitness.coach'");
    publishMealRuntimeSnapshot(agentJdbc, 92, "{\"modelKey\":\"published-model\"}");
    assertThat(runtime.generate(currentGoalReportFacts()).failureMessage())
        .isEqualTo("已发布报告运行时快照缺失");
  }

  @Test
  void currentGoalReportRuntimeKeepsPublishedComponentAndCredentialSnapshotsUntilRepublished()
      throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> model = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          model.set(objectMapper.readTree(exchange.getRequestBody()).path("model").asText());
          byte[] response =
              "{\"choices\":[{\"message\":{\"content\":\"{\\\"conclusion\\\":{\\\"summary\\\":\\\"稳定执行\\\",\\\"score\\\":80,\\\"grade\\\":\\\"B\\\"},\\\"highlights\\\":[\\\"一\\\",\\\"二\\\"],\\\"weaknesses\\\":[\\\"三\\\"],\\\"nextActions\\\":[{\\\"title\\\":\\\"记录\\\",\\\"rationale\\\":\\\"保持更新\\\",\\\"action\\\":\\\"OPEN_RECORD\\\"}]}\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      JdbcTemplate agentJdbc = new JdbcTemplate(agentDataSource);
      agentJdbc.update("DELETE FROM agent_versions WHERE agent_key='fitness.coach'");
      Path masterKey =
          testSecret("published-report-runtime", Base64.getEncoder().encodeToString(new byte[32]));
      JdbcAdminWorkbenchStore workbench =
          new JdbcAdminWorkbenchStore(agentDataSource, objectMapper, masterKey);
      resetChatAgentDraft(agentJdbc);
      String publishedEndpoint = "http://localhost:" + server.getAddress().getPort() + "/v1";
      agentJdbc.update(
          "UPDATE agent_providers SET endpoint=? WHERE provider_key='bailian'", publishedEndpoint);
      agentJdbc.update(
          "UPDATE agent_models SET model_id='published-model' WHERE model_key='qwen-plus'");
      workbench.saveCredential("bailian", "published-key".toCharArray());
      workbench.publish(workbench.findDraft("fitness.coach").orElseThrow());

      CurrentGoalReportRuntime runtime =
          new CurrentGoalReportRuntime(agentDataSource, objectMapper, masterKey.toString());
      assertThat(runtime.generate(currentGoalReportFacts()).status()).isEqualTo("SUCCEEDED");
      assertThat(model.get()).isEqualTo("published-model");
      assertThat(authorization.get()).isEqualTo("Bearer published-key");

      agentJdbc.update(
          "UPDATE agent_providers SET endpoint=? WHERE provider_key='bailian'",
          publishedEndpoint + "/changed");
      agentJdbc.update(
          "UPDATE agent_models SET model_id='mutable-model' WHERE model_key='qwen-plus'");
      workbench.saveCredential("bailian", "mutable-key".toCharArray());

      assertThat(runtime.generate(currentGoalReportFacts()).status()).isEqualTo("SUCCEEDED");
      assertThat(model.get()).isEqualTo("published-model");
      assertThat(authorization.get()).isEqualTo("Bearer published-key");

      workbench.publish(workbench.findDraft("fitness.coach").orElseThrow());
      assertThat(runtime.generate(currentGoalReportFacts()).status()).isEqualTo("SUCCEEDED");
      assertThat(model.get()).isEqualTo("mutable-model");
      assertThat(authorization.get()).isEqualTo("Bearer mutable-key");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void mealRecordCreatedAtComesFromTheDatabaseInsteadOfOccurredAt() throws Exception {
    Cookie owner = login();
    mvc.perform(
            post("/api/v1/app/meal-records")
                .cookie(owner)
                .header("Idempotency-Key", "record-created-at-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"mealType\":\"LUNCH\",\"occurredAt\":\"2020-01-01T00:00:00Z\",\"source\":\"MANUAL\",\"items\":[{\"name\":\"午饭\",\"estimatedKcal\":400}]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(
            jsonPath("$.createdAt").value(org.hamcrest.Matchers.not("2020-01-01T00:00:00Z")));
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
    assertThat(ticket.path("uploadUrl").asText()).isEqualTo("/api/v1/app/media-uploads/" + mediaId);

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

    recognitionPort.succeedWith("重试后的食物", 410, 0.9);
    String retriedMediaId = createAndUpload(owner, image, sha256, "ticket-key-0005");
    MvcResult retriedJob =
        mvc.perform(
                post("/api/v1/app/meal-recognition-jobs")
                    .cookie(owner)
                    .header("Idempotency-Key", "job-key-0003")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"mediaId":"%s","mealType":"DINNER","occurredAt":"2026-08-09T12:00:00Z"}
                        """
                            .formatted(retriedMediaId)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andReturn();
    String retriedJobId =
        objectMapper.readTree(retriedJob.getResponse().getContentAsString()).path("jobId").asText();
    assertThat(retriedJobId).isNotEqualTo(failedJobId);
    recognitionWorker.runOne();
    mvc.perform(get("/api/v1/app/meal-recognition-jobs/{jobId}", retriedJobId).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.mediaId").value(retriedMediaId));
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

  private String firstRecommendationId(Cookie session) throws Exception {
    return bootstrap(session).path("mealRecommendations").get(0).path("id").asText();
  }

  private String concurrentPlanRequest(Cookie owner, CyclicBarrier start, String idempotencyKey)
      throws Exception {
    start.await();
    MvcResult result =
        mvc.perform(
                post("/api/v1/app/meal-plans/daily/generate")
                    .cookie(owner)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"date\":\"2026-08-15\"}"))
            .andExpect(status().isAccepted())
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .path("mealPlanId")
        .asText();
  }

  private void drainDailyMealPlanQueue() {
    for (int ignored = 0; ignored < 8; ignored++) {
      dailyMealPlanWorker.runOne();
    }
  }

  private void drainCurrentGoalReportQueue() {
    for (int ignored = 0; ignored < 8; ignored++) {
      currentGoalReportWorker.runOne();
    }
  }

  private static happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportFacts
      currentGoalReportFacts() {
    LocalDate week = LocalDate.now().minusWeeks(3).with(java.time.DayOfWeek.MONDAY);
    return new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportFacts(
        "当前减脂目标",
        week,
        LocalDate.now(),
        List.of(
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportMetric(
                "GOAL_PROGRESS",
                "目标进度",
                new java.math.BigDecimal("50.0"),
                "%",
                new java.math.BigDecimal("1.0"),
                "UP")),
        List.of(
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalWeightTrendPoint(
                week, new java.math.BigDecimal("140.0")),
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalWeightTrendPoint(
                week.plusWeeks(1), new java.math.BigDecimal("139.5")),
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalWeightTrendPoint(
                week.plusWeeks(2), null),
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalWeightTrendPoint(
                week.plusWeeks(3), null)),
        List.of(
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalTrainingVolumePoint(
                week, 30, 1),
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalTrainingVolumePoint(
                week.plusWeeks(1), 0, 0),
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalTrainingVolumePoint(
                week.plusWeeks(2), 0, 0),
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalTrainingVolumePoint(
                week.plusWeeks(3), 0, 0)),
        List.of(
            new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalTrainingStructureItem(
                "全身", new java.math.BigDecimal("100"))),
        new java.math.BigDecimal("20"),
        new java.math.BigDecimal("80"));
  }

  private static CurrentGoalReportGenerationResult currentGoalReportSuccess() {
    return new CurrentGoalReportGenerationResult(
        "SUCCEEDED",
        new CurrentGoalReportNarrative(
            new CurrentGoalReportConclusion("当前目标处于稳定执行阶段", 82, "B"),
            List.of("已持续记录训练", "体重趋势可追踪"),
            List.of("本周训练密度仍可提高"),
            List.of(new CurrentGoalReportNextAction("补齐今天记录", "便于报告保持最新", "OPEN_RECORD"))),
        null,
        null);
  }

  private static UUID ownedRecommendation(JdbcTemplate jdbc, UUID userId, LocalDate date) {
    UUID recommendationId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO daily_meal_recommendations(recommendation_id,user_id,recommendation_date,meal_type,items,reason,status) VALUES (?,?,?,'BREAKFAST','[]'::jsonb,'约束测试','READY')",
        recommendationId,
        userId,
        date);
    return recommendationId;
  }

  private JsonNode bootstrap(Cookie session) throws Exception {
    return objectMapper.readTree(
        mvc.perform(get("/api/app/bootstrap").cookie(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
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

  private static void publishMealRuntimeSnapshot(
      JdbcTemplate jdbc, int version, String configuration) {
    jdbc.update(
        "INSERT INTO agent_versions(agent_version_id,agent_key,version,status,configuration,published_at) VALUES (?, 'fitness.coach', ?, 'PUBLISHED', ?::jsonb, CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        version,
        configuration);
  }

  private static void resetChatAgentDraft(JdbcTemplate jdbc) {
    jdbc.update(
        "UPDATE agent_drafts SET name='花爷健身教练',description='结合用户的训练、饮食与身体记录，提供可执行的日常陪伴。',status='DRAFT',framework_key='agentscope',provider_key='bailian',model_key='qwen-plus',prompt_key='fitness.coach.prompt',tool_keys='[\"fitness.user.profile.query\",\"fitness.goal.current.query\",\"fitness.training.constraints.query\",\"fitness.nutrition.preferences.query\",\"fitness.body.latest.query\",\"fitness.body.trend.query\",\"fitness.workout.schedule.query\",\"fitness.workout.history.query\",\"fitness.workout.summary.query\",\"fitness.exercise.candidates.query\",\"fitness.exercise.catalog.search\",\"fitness.exercise.details.query\",\"fitness.meal.history.query\",\"fitness.meal.summary.query\",\"fitness.meal.recommendations.query\",\"fitness.meal.feedback.query\",\"fitness.nutrition.targets.estimate\",\"fitness.plan.save\"]'::jsonb,skill_keys='[\"fitness.meal.skill\",\"fitness.plan.skill\"]'::jsonb,hook_keys='[\"fitness.safety\"]'::jsonb,memory_key='fitness.daily-memory',temperature=0.5,max_tool_calls=18,updated_at=CURRENT_TIMESTAMP WHERE agent_key='fitness.coach'");
    jdbc.update(
        "UPDATE agent_skills SET status='ACTIVE',runtime_ready=true WHERE skill_key IN ('fitness.meal.skill','fitness.plan.skill')");
    jdbc.update(
        "UPDATE agent_hooks SET status='ACTIVE',runtime_ready=true WHERE hook_key='fitness.safety'");
  }

  private static String publishedCurrentGoalRuntimeSnapshot(String providerKey, String modelKey) {
    return """
        {"currentGoalReportRuntime":{"provider":{"key":"%s","version":1,"status":"AVAILABLE","config":{"endpoint":"https://example.test/v1/"}},"model":{"key":"%s","version":1,"status":"AVAILABLE","config":{"providerKey":"%s","model":"%s"}},"credential":{"keyVersion":1,"ciphertext":"AA==","iv":"AAAAAAAAAAAAAAAA","aad":""}}}
        """
        .formatted(providerKey, modelKey, providerKey, modelKey);
  }

  private static String publishedUnifiedRuntimeSnapshot(String providerKey, String modelKey) {
    return """
        {"providerKey":"%s","modelKey":"%s","currentGoalReportRuntime":{"provider":{"key":"%s","version":1,"status":"AVAILABLE","config":{"endpoint":"https://example.test/v1/"}},"model":{"key":"%s","version":1,"status":"AVAILABLE","config":{"providerKey":"%s","model":"%s","vision":true}},"credential":{"keyVersion":1,"ciphertext":"AA==","iv":"AAAAAAAAAAAAAAAA","aad":""}}}
        """
        .formatted(providerKey, modelKey, providerKey, modelKey, providerKey, modelKey);
  }

  private static void upsertMealRuntimeComponents(
      JdbcTemplate jdbc, String providerKey, String modelKey, String modelConfiguration) {
    jdbc.update(
        "INSERT INTO agent_providers(provider_key,display_name,endpoint,status) VALUES (?,'test provider','https://example.test/v1/','ACTIVE') ON CONFLICT(provider_key) DO UPDATE SET status='ACTIVE',endpoint=EXCLUDED.endpoint",
        providerKey);
    com.fasterxml.jackson.databind.JsonNode model;
    try {
      model = new com.fasterxml.jackson.databind.ObjectMapper().readTree(modelConfiguration);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalArgumentException(exception);
    }
    jdbc.update(
        "INSERT INTO agent_models(model_key,provider_key,model_id,display_name,description,supports_streaming,supports_tool_calling,supports_vision,status) VALUES (?,?,?,'test model','test model',true,true,?,'ACTIVE') ON CONFLICT(model_key) DO UPDATE SET provider_key=EXCLUDED.provider_key,model_id=EXCLUDED.model_id,supports_vision=EXCLUDED.supports_vision,status='ACTIVE'",
        modelKey,
        providerKey,
        model.path("model").asText(modelKey),
        model.path("vision").asBoolean(false));
  }

  private static DailyMealPlanGenerationResult dailyPlanResult(String foodName) {
    return new DailyMealPlanGenerationResult(
        "SUCCEEDED",
        List.of(
            new GeneratedMealRecommendation(
                MealType.BREAKFAST, List.of(new MealItemDto(foodName, 300)), "早餐理由"),
            new GeneratedMealRecommendation(
                MealType.LUNCH, List.of(new MealItemDto(foodName, 500)), "午餐理由"),
            new GeneratedMealRecommendation(
                MealType.DINNER, List.of(new MealItemDto(foodName, 400)), "晚餐理由")),
        null,
        null);
  }

  @Test
  void currentGoalReportPostQueuesASeparateDurableReportWithPollingHeaders() throws Exception {
    currentGoalReportPort.succeed();
    drainCurrentGoalReportQueue();
    currentGoalReportPort.succeed();
    Cookie owner = login();

    mvc.perform(
            post("/api/v1/app/reports/current-goal")
                .cookie(owner)
                .header("Idempotency-Key", "current-goal-report-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"USER_REFRESH\"}"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/api/v1/app/reports/current-goal"))
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.state").value("QUEUED"));
    assertThat(currentGoalReportPort.calls()).isZero();
  }

  @Test
  void currentGoalReportWorkerPersistsFactsAndMarksNewObjectiveDataStale() throws Exception {
    currentGoalReportPort.succeed();
    drainCurrentGoalReportQueue();
    currentGoalReportPort.succeed();
    Cookie owner = login();

    mvc.perform(
            post("/api/v1/app/reports/current-goal")
                .cookie(owner)
                .header("Idempotency-Key", "current-goal-report-ready-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"USER_REFRESH\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("QUEUED"));
    assertThat(currentGoalReportPort.calls()).isZero();

    currentGoalReportWorker.runOne();
    assertThat(currentGoalReportPort.calls()).isEqualTo(1);
    mvc.perform(get("/api/v1/app/reports/current-goal").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"))
        .andExpect(jsonPath("$.metrics[0].key").value("GOAL_PROGRESS"))
        .andExpect(jsonPath("$.metrics[0].comparison").doesNotExist())
        .andExpect(
            jsonPath("$.weightTrend.length()", org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
        .andExpect(
            jsonPath("$.trainingVolume.length()", org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
        .andExpect(jsonPath("$.conclusion.summary").value("当前目标处于稳定执行阶段"))
        .andExpect(jsonPath("$.highlights.length()").value(2))
        .andExpect(jsonPath("$.nextActions[0].action").value("OPEN_RECORD"));

    UUID ownerId = UUID.fromString(bootstrap(owner).path("user").path("id").asText());
    fitnessStore.createBodyRecord(
        ownerId,
        new happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest(
            new java.math.BigDecimal("139.8"), new java.math.BigDecimal("80.0"), Instant.now()));

    mvc.perform(get("/api/v1/app/reports/current-goal").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("STALE"))
        .andExpect(jsonPath("$.conclusion.summary").value("当前目标处于稳定执行阶段"));
  }

  @Test
  void currentGoalReportUsesWriteWatermarksForLateInWindowDataOnly() throws Exception {
    currentGoalReportPort.succeed();
    drainCurrentGoalReportQueue();
    Cookie owner = login();
    UUID ownerId = UUID.fromString(bootstrap(owner).path("user").path("id").asText());
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);

    mvc.perform(
            post("/api/v1/app/reports/current-goal")
                .cookie(owner)
                .header("Idempotency-Key", "current-goal-watermark-ready")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"USER_REFRESH\"}"))
        .andExpect(status().isAccepted());
    currentGoalReportWorker.runOne();
    mvc.perform(get("/api/v1/app/reports/current-goal").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"));

    Instant goalStartedAt =
        jdbc.queryForObject(
                "SELECT created_at FROM goals WHERE user_id=? AND status='ACTIVE' ORDER BY created_at DESC LIMIT 1",
                java.sql.Timestamp.class,
                ownerId)
            .toInstant();
    fitnessStore.createBodyRecord(
        ownerId,
        new happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest(
            new java.math.BigDecimal("139.7"), null, goalStartedAt.plusMillis(1)));
    mvc.perform(get("/api/v1/app/reports/current-goal").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("STALE"));

    mvc.perform(
            post("/api/v1/app/reports/current-goal")
                .cookie(owner)
                .header("Idempotency-Key", "current-goal-watermark-refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"USER_REFRESH\"}"))
        .andExpect(status().isAccepted());
    currentGoalReportWorker.runOne();
    fitnessStore.createBodyRecord(
        ownerId,
        new happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest(
            new java.math.BigDecimal("140.2"), null, goalStartedAt.minus(Duration.ofDays(1))));

    mvc.perform(get("/api/v1/app/reports/current-goal").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"));
  }

  @Test
  void objectiveRecordEndpointsRejectFutureBusinessTimes() throws Exception {
    Cookie owner = login();
    String future = Instant.now().plus(Duration.ofDays(1)).toString();

    mvc.perform(
            post("/api/app/body-records")
                .cookie(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weightJin\":140,\"recordedAt\":\"%s\"}".formatted(future)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/app/meals")
                .cookie(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"mealType\":\"LUNCH\",\"occurredAt\":\"%s\",\"items\":[{\"name\":\"午饭\",\"estimatedKcal\":400}]}"
                        .formatted(future)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/app/meal-records")
                .cookie(owner)
                .header("Idempotency-Key", "future-meal-record-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"mealType\":\"LUNCH\",\"occurredAt\":\"%s\",\"source\":\"MANUAL\",\"items\":[{\"name\":\"午饭\",\"estimatedKcal\":400}]}"
                        .formatted(future)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void currentGoalFactsKeepEmptyWeeksAndUseCountBasedAreaCoverage() {
    Instant observedThrough = Instant.parse("2026-08-09T12:00:00Z");
    var source =
        new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportSourceData(
            new happy.jayden.yang.fitness.service.FitnessDtos.GoalState(
                UUID.randomUUID(),
                "八月减脂",
                new java.math.BigDecimal("140"),
                new java.math.BigDecimal("120"),
                "ACTIVE",
                1,
                Instant.parse("2026-07-20T00:00:00Z")),
            observedThrough,
            List.of(
                new happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto(
                    UUID.randomUUID(),
                    Instant.parse("2026-07-21T00:00:00Z"),
                    new java.math.BigDecimal("138"),
                    null),
                new happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto(
                    UUID.randomUUID(),
                    Instant.parse("2026-08-08T00:00:00Z"),
                    new java.math.BigDecimal("136"),
                    null)),
            List.of(),
            List.of(
                new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalWorkoutRecord(
                    Instant.parse("2026-08-03T00:00:00Z"), 60, List.of("下肢", "核心"), "下肢力量"),
                new happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalWorkoutRecord(
                    Instant.parse("2026-08-08T00:00:00Z"), 30, List.of("心肺"), "室内骑行")));

    var facts = FitnessApplicationService.currentGoalReportFacts(source);

    assertThat(facts.weightTrend()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(facts.weightTrend()).anySatisfy(point -> assertThat(point.valueJin()).isNull());
    assertThat(facts.metrics())
        .filteredOn(metric -> metric.key().equals("WEIGHT"))
        .singleElement()
        .extracting(metric -> metric.comparison())
        .isEqualTo(new java.math.BigDecimal("-2"));
    assertThat(facts.trainingStructure())
        .extracting(item -> item.area() + ":" + item.percent())
        .containsExactlyInAnyOrder("下肢:33", "核心:33", "心肺:33");
    assertThat(facts.strengthPercent()).isEqualByComparingTo("67");
    assertThat(facts.cardioPercent()).isEqualByComparingTo("33");
  }

  @Test
  void currentGoalReportFailureCanBeExplicitlyRetriedWithoutModelWorkOnHttpThread()
      throws Exception {
    currentGoalReportPort.succeed();
    drainCurrentGoalReportQueue();
    currentGoalReportPort.failWith("DEPENDENCY_NOT_CONFIGURED", "报告 Agent 尚未发布");
    Cookie owner = login();

    mvc.perform(
            post("/api/v1/app/reports/current-goal")
                .cookie(owner)
                .header("Idempotency-Key", "current-goal-report-fail-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"USER_REFRESH\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("QUEUED"));
    assertThat(currentGoalReportPort.calls()).isZero();

    currentGoalReportWorker.runOne();
    mvc.perform(get("/api/v1/app/reports/current-goal").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failure.code").value("DEPENDENCY_NOT_CONFIGURED"))
        .andExpect(jsonPath("$.failure.message").value("报告 Agent 尚未发布"))
        .andExpect(jsonPath("$.failure.retryable").value(false));

    currentGoalReportPort.succeed();
    mvc.perform(
            post("/api/v1/app/reports/current-goal")
                .cookie(owner)
                .header("Idempotency-Key", "current-goal-report-retry-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"RETRY_FAILED\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("QUEUED"));
    currentGoalReportWorker.runOne();
    mvc.perform(get("/api/v1/app/reports/current-goal").cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"));
  }

  @Test
  void currentGoalReportIsOwnedByTheAuthenticatedActiveGoalAndFencesLateWorkers() throws Exception {
    currentGoalReportPort.succeed();
    drainCurrentGoalReportQueue();
    currentGoalReportPort.succeed();
    Cookie other = createOtherUser();
    mvc.perform(get("/api/v1/app/reports/current-goal").cookie(other))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/app/reports/current-goal")
                .cookie(other)
                .header("Idempotency-Key", "other-current-goal-report-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"USER_REFRESH\"}"))
        .andExpect(status().isNotFound());

    UUID ownerId = UUID.fromString(bootstrap(login()).path("user").path("id").asText());
    var run = fitnessStore.enqueueCurrentGoalReport(ownerId);
    var abandonedClaim = fitnessStore.claimNextCurrentGoalReportGeneration().orElseThrow();
    assertThat(abandonedClaim.run().reportId()).isEqualTo(run.reportId());
    JdbcTemplate jdbc = new JdbcTemplate(fitnessDataSource);
    jdbc.update(
        "UPDATE current_goal_reports SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE report_id=?",
        run.reportId());
    var facts = currentGoalReportFacts();
    var result = currentGoalReportSuccess();
    assertThat(
            fitnessStore.completeCurrentGoalReportGeneration(
                abandonedClaim, facts, result, Instant.now()))
        .isFalse();
    assertThat(
            fitnessStore.failCurrentGoalReportGeneration(abandonedClaim, "TASK_FAILED", "旧 worker"))
        .isFalse();

    var reclaimedClaim = fitnessStore.claimNextCurrentGoalReportGeneration().orElseThrow();
    assertThat(reclaimedClaim.run().reportId()).isEqualTo(run.reportId());
    assertThat(reclaimedClaim.run().version()).isGreaterThan(abandonedClaim.run().version());
    assertThat(
            fitnessStore.completeCurrentGoalReportGeneration(
                reclaimedClaim, facts, result, Instant.now()))
        .isTrue();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RecognitionPortConfiguration {
    @Bean
    @Primary
    ControlledRecognitionPort controlledRecognitionPort() {
      return new ControlledRecognitionPort();
    }

    @Bean
    @Primary
    ControlledDailyMealPlanPort controlledDailyMealPlanPort() {
      return new ControlledDailyMealPlanPort();
    }

    @Bean
    @Primary
    ControlledCurrentGoalReportPort controlledCurrentGoalReportPort() {
      return new ControlledCurrentGoalReportPort();
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

  static final class ControlledDailyMealPlanPort implements DailyMealPlanGenerationPort {
    private final AtomicInteger calls = new AtomicInteger();
    private DailyMealPlanGenerationResult result = success();

    @Override
    public DailyMealPlanGenerationResult generate(UUID userId, java.time.LocalDate date) {
      calls.incrementAndGet();
      return result;
    }

    void failWith(String code, String message) {
      calls.set(0);
      result = new DailyMealPlanGenerationResult("FAILED", List.of(), code, message);
    }

    void succeed() {
      calls.set(0);
      result = success();
    }

    private static DailyMealPlanGenerationResult success() {
      return new DailyMealPlanGenerationResult(
          "SUCCEEDED",
          List.of(
              new GeneratedMealRecommendation(
                  MealType.BREAKFAST, List.of(new MealItemDto("反馈早餐", 320)), "由安全偏好摘要调整"),
              new GeneratedMealRecommendation(
                  MealType.LUNCH, List.of(new MealItemDto("反馈午餐", 520)), "均衡午餐"),
              new GeneratedMealRecommendation(
                  MealType.DINNER, List.of(new MealItemDto("反馈晚餐", 420)), "清淡晚餐")),
          null,
          null);
    }

    int calls() {
      return calls.get();
    }
  }

  static final class ControlledCurrentGoalReportPort implements CurrentGoalReportGenerationPort {
    private final AtomicInteger calls = new AtomicInteger();
    private CurrentGoalReportGenerationResult result = currentGoalReportSuccess();

    @Override
    public CurrentGoalReportGenerationResult generate(
        happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportFacts facts) {
      calls.incrementAndGet();
      return result;
    }

    void succeed() {
      calls.set(0);
      result = currentGoalReportSuccess();
    }

    void failWith(String code, String message) {
      calls.set(0);
      result = new CurrentGoalReportGenerationResult("FAILED", null, code, message);
    }

    int calls() {
      return calls.get();
    }
  }
}
