package happy.jayden.yang.agentbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import happy.jayden.yang.StarterApplication;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@SpringBootTest(classes = StarterApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminWorkbenchIntegrationTest {
  private static final Path PROJECT_ROOT = projectRoot();
  private static final Path MASTER_KEY = testMasterKey();

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

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("happy.datasource.fitness.url", POSTGRES::getJdbcUrl);
    registry.add("happy.datasource.agent.url", POSTGRES::getJdbcUrl);
    registry.add("happy.datasource.fitness.password", () -> "fitness-test-password");
    registry.add("happy.datasource.agent.password", () -> "agent-test-password");
    registry.add("happy.fitness.local-seed.enabled", () -> "true");
    registry.add("happy.agent.workbench.local-seed.enabled", () -> "true");
    registry.add("happy.agent.workbench.master-key-file", MASTER_KEY::toString);
  }

  @Test
  @Order(1)
  void workbenchRequiresSessionAndReturnsDatabaseSeed() throws Exception {
    mvc.perform(get("/api/admin/workbench")).andExpect(status().isUnauthorized());

    mvc.perform(get("/api/admin/workbench").cookie(login()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agents[0].agentKey").value("fitness.coach"))
        .andExpect(jsonPath("$.providers[0].configured").value(false))
        .andExpect(jsonPath("$.components.length()").value(org.hamcrest.Matchers.greaterThan(9)));
  }

  @Test
  @Order(2)
  void draftCredentialValidationAndPublicationUseRealDatabase() throws Exception {
    Cookie session = login();
    String update =
        """
        {
          "name":"瘦瘦健身教练",
          "description":"由管理台保存的真实草稿",
          "frameworkKey":"agentscope",
          "providerKey":"bailian",
          "modelKey":"qwen-plus",
          "promptKey":"fitness.coach.prompt",
          "toolKeys":[],
          "skillKeys":[],
          "hookKeys":["fitness.safety"],
          "memoryKey":"fitness.daily-memory",
          "temperature":0.45,
          "maxToolCalls":6
        }
        """;
    mvc.perform(
            patch("/api/admin/agents/fitness.coach/draft")
                .cookie(session)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(update))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(2))
        .andExpect(jsonPath("$.description").value("由管理台保存的真实草稿"));

    mvc.perform(post("/api/admin/agents/fitness.coach/validate").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.errors[0]").value("Provider 尚未配置 API Key"));

    String secret = "sk-test-secret-value";
    String credentialBody = "{\"apiKey\":\"" + secret + "\"}";
    String response =
        mvc.perform(
                put("/api/admin/providers/bailian/credential")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(credentialBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.maskedCredential").value("••••••••"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain(secret).doesNotContain("ciphertext").doesNotContain("iv");

    mvc.perform(post("/api/admin/agents/fitness.coach/validate").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true));

    mvc.perform(post("/api/admin/agents/fitness.coach/publish").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishedVersion").value(1));
  }

  @Test
  @Order(3)
  void staleDraftRevisionReturnsConflict() throws Exception {
    mvc.perform(
            patch("/api/admin/agents/fitness.coach/draft")
                .cookie(login())
                .header("If-Match", "\"99\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"瘦瘦","description":"冲突测试","frameworkKey":"agentscope","providerKey":"bailian","modelKey":"qwen-plus","promptKey":"fitness.coach.prompt","toolKeys":[],"skillKeys":[],"hookKeys":[],"memoryKey":"fitness.daily-memory","temperature":0.5,"maxToolCalls":8}
                    """))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
  }

  private Cookie login() throws Exception {
    var result =
        mvc.perform(
                post("/api/local/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"user\",\"password\":\"demo123\"}"))
            .andExpect(status().isOk())
            .andReturn();
    var cookie = result.getResponse().getCookie("FITNESS_SESSION");
    assertThat(cookie).isNotNull();
    return cookie;
  }

  private static Path testMasterKey() {
    try {
      var path = Files.createTempFile("happy-agent-master-", ".key");
      Files.writeString(
          path, Base64.getEncoder().encodeToString(new byte[32]), StandardCharsets.US_ASCII);
      path.toFile().deleteOnExit();
      return path;
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static Path testSecret(String name, String value) {
    try {
      Path secret = Files.createTempFile("happy-agent-admin-", "-" + name);
      Files.writeString(secret, value, StandardCharsets.UTF_8);
      secret.toFile().setReadable(true, false);
      secret.toFile().deleteOnExit();
      return secret;
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static Path projectRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      if (Files.isRegularFile(directory.resolve("deploy/docker-compose.yml"))) return directory;
      directory = directory.getParent();
    }
    throw new IllegalStateException("Unable to locate repository root");
  }
}
