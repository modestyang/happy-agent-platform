package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.StarterApplication;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("happy.datasource.fitness.url", POSTGRES::getJdbcUrl);
    registry.add("happy.datasource.agent.url", POSTGRES::getJdbcUrl);
    registry.add("happy.datasource.fitness.password", () -> "fitness-test-password");
    registry.add("happy.datasource.agent.password", () -> "agent-test-password");
    registry.add("happy.fitness.local-seed.enabled", () -> "true");
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
            .andExpect(jsonPath("$.plan.exercises.length()").value(4))
            .andExpect(jsonPath("$.exercises[0].illustrationMode").value("FOUR_STEP_IMAGES"))
            .andExpect(jsonPath("$.exercises[0].imageUrls.length()").value(4))
            .andExpect(jsonPath("$.report.status").value("READY"))
            .andExpect(jsonPath("$.report.conclusion").isString())
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
        .andExpect(jsonPath("$.plan.status").value("COMPLETED"));
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

  private Cookie login() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/local/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"user\",\"password\":\"demo123\"}"))
            .andExpect(status().isOk())
            .andExpect(cookie().httpOnly("FITNESS_SESSION", true))
            .andExpect(jsonPath("$.user.id").isString())
            .andExpect(jsonPath("$.user.nickname").value("小秦"))
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
}
