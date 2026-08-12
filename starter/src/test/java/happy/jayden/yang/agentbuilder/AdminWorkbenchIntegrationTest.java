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
import happy.jayden.yang.fitness.infrastructure.JdbcFitnessUserDirectory;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

  @Autowired
  @Qualifier("agentDataSource")
  private DataSource agentDataSource;

  @Autowired
  @Qualifier("fitnessDataSource")
  private DataSource fitnessDataSource;

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
    mvc.perform(get("/api/admin/agents")).andExpect(status().isUnauthorized());

    Cookie session = adminLogin("admin", "admin123");
    mvc.perform(get("/api/admin/agents").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agentKey").value("fitness.coach"));
    mvc.perform(get("/api/admin/providers").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].configured").value(false));
    mvc.perform(get("/api/admin/tools").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(5)));
  }

  @Test
  @Order(2)
  void adminSessionIsIndependentFromFitnessSession() throws Exception {
    mvc.perform(get("/api/admin/agents")).andExpect(status().isUnauthorized());

    mvc.perform(get("/api/admin/agents").cookie(login())).andExpect(status().isUnauthorized());

    Cookie adminSession = adminLogin("admin", "admin123");
    mvc.perform(get("/api/admin/agents").cookie(adminSession)).andExpect(status().isOk());

    mvc.perform(
            post("/api/local/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user\",\"password\":\"demo123\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/agents").cookie(adminSession)).andExpect(status().isOk());
  }

  @Test
  @Order(3)
  void draftCredentialValidationAndPublicationUseRealDatabase() throws Exception {
    Cookie session = adminLogin("admin", "admin123");
    String update =
        """
        {
          "name":"花爷健身教练",
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
  @Order(4)
  void staleDraftRevisionReturnsConflict() throws Exception {
    mvc.perform(
            patch("/api/admin/agents/fitness.coach/draft")
                .cookie(adminLogin("admin", "admin123"))
                .header("If-Match", "\"99\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"花爷","description":"冲突测试","frameworkKey":"agentscope","providerKey":"bailian","modelKey":"qwen-plus","promptKey":"fitness.coach.prompt","toolKeys":[],"skillKeys":[],"hookKeys":[],"memoryKey":"fitness.daily-memory","temperature":0.5,"maxToolCalls":8}
                    """))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
  }

  @Test
  @Order(5)
  void fitnessUserDirectorySearchesUsernamesAndTreatsWildcardsLiterally() {
    var jdbc = new JdbcTemplate(fitnessDataSource);
    UUID alice = UUID.randomUUID();
    UUID literal = UUID.randomUUID();
    UUID wildcardLookalike = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(user_id,external_subject,status,username,password_hash,nickname)"
            + " VALUES (?,?, 'ACTIVE', ?, 'unused', ?)",
        alice,
        "local:trace-alice",
        "TraceAlice",
        "Trace Alice");
    jdbc.update(
        "INSERT INTO users(user_id,external_subject,status,username,password_hash,nickname)"
            + " VALUES (?,?, 'ACTIVE', ?, 'unused', ?)",
        literal,
        "local:literal-percent-underscore",
        "literal%_trace",
        "Literal");
    jdbc.update(
        "INSERT INTO users(user_id,external_subject,status,username,password_hash,nickname)"
            + " VALUES (?,?, 'ACTIVE', ?, 'unused', ?)",
        wildcardLookalike,
        "local:literal-lookalike",
        "literalXXtrace",
        "Lookalike");
    var directory = new JdbcFitnessUserDirectory(fitnessDataSource);

    assertThat(directory.searchUserIds("alice")).containsExactly(alice);
    assertThat(directory.searchUserIds("%_")).containsExactly(literal);
    assertThat(directory.findUsernames(Set.of(alice, literal)))
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of(alice, "TraceAlice", literal, "literal%_trace"));
    assertThat(directory.findUsernames(Set.of())).isEmpty();
  }

  @Test
  @Order(6)
  void developerCanInspectAUsersConversationAndItsLinkedRunTrace() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    new JdbcTemplate(fitnessDataSource)
        .update(
            "INSERT INTO users(user_id,external_subject,status,username,password_hash,nickname)"
                + " VALUES (?,?, 'ACTIVE', ?, 'unused', ?)",
            userId,
            "local:trace-search-alice",
            "trace-search-alice",
            "Trace Search Alice");
    var jdbc = new JdbcTemplate(agentDataSource);
    jdbc.update(
        "INSERT INTO agent_conversations(conversation_id,user_id,agent_key,title,status,started_at,last_message_at) VALUES (?,?,?,'晚饭怎么安排','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        conversationId,
        userId,
        "fitness.coach");
    jdbc.update(
        "INSERT INTO agent_runs(run_id,user_id,conversation_id,agent_key,agent_version,status,started_at) VALUES (?,?,?,'fitness.coach',1,'SUCCEEDED',CURRENT_TIMESTAMP)",
        runId,
        userId,
        conversationId);
    jdbc.update(
        "INSERT INTO agent_conversation_messages(message_id,conversation_id,run_id,role,content,created_at) VALUES (?,?,?,'USER','晚饭怎么安排？',CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        conversationId,
        runId);

    Cookie session = adminLogin("admin", "admin123");
    mvc.perform(
            get("/api/admin/traces/conversations")
                .queryParam("query", "search-alice")
                .queryParam("page", "0")
                .queryParam("size", "10")
                .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].conversationId").value(conversationId.toString()))
        .andExpect(jsonPath("$.items[0].userId").value(userId.toString()))
        .andExpect(jsonPath("$.items[0].username").value("trace-search-alice"))
        .andExpect(jsonPath("$.items[0].messageCount").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(10))
        .andExpect(jsonPath("$.hasNext").value(false));
    mvc.perform(
            get("/api/admin/traces/conversations")
                .queryParam("query", userId.toString())
                .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].conversationId").value(conversationId.toString()));
    mvc.perform(
            get("/api/admin/traces/conversations")
                .queryParam("query", conversationId.toString())
                .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].conversationId").value(conversationId.toString()));
    mvc.perform(
            get("/api/admin/traces/conversations")
                .queryParam("query", "x".repeat(161))
                .cookie(session))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/admin/traces/conversations/{conversationId}", conversationId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages[0].content").value("晚饭怎么安排？"))
        .andExpect(jsonPath("$.runs[0].runId").value(runId.toString()));
  }

  @Test
  @Order(7)
  void developerCanCreateAnAgentDraftAndSeeItInTheWorkbench() throws Exception {
    Cookie session = adminLogin("admin", "admin123");

    mvc.perform(
            post("/api/admin/agents")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"agentKey":"baby.food.coach","name":"辅食助手","description":"为家庭提供辅食安排建议"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.agentKey").value("baby.food.coach"))
        .andExpect(jsonPath("$.name").value("辅食助手"))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.promptKey").value("agent.default.prompt"))
        .andExpect(jsonPath("$.memoryKey").value("agent.default.memory"))
        .andExpect(jsonPath("$.toolKeys.length()").value(0))
        .andExpect(jsonPath("$.skillKeys.length()").value(0))
        .andExpect(jsonPath("$.hookKeys.length()").value(0))
        .andExpect(jsonPath("$.revision").value(1));

    mvc.perform(get("/api/admin/agents").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.agentKey == 'baby.food.coach')].name").value("辅食助手"));

    mvc.perform(
            post("/api/admin/agents")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentKey\":\"baby.food.coach\",\"name\":\"重复\",\"description\":\"重复\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  @Order(8)
  void developerCanCreatePromptAndSkillWithoutPublishingCode() throws Exception {
    Cookie session = adminLogin("admin", "admin123");

    mvc.perform(
            post("/api/admin/prompts")
                .cookie(session)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"promptKey":"acceptance.prompt","displayName":"验收提示词","description":"页面新增提示词","template":"你好 {{name}}"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.promptKey").value("acceptance.prompt"))
        .andExpect(jsonPath("$.revision").value(1));

    mvc.perform(
            post("/api/admin/skills")
                .cookie(session)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"skillKey":"acceptance.skill","displayName":"验收技能","description":"页面新增技能","whenToUse":"验收时","whenNotToUse":"无","content":"读取档案后回答。","requiredToolKeys":["fitness.profile.query"]}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.skillKey").value("acceptance.skill"))
        .andExpect(jsonPath("$.runtimeReady").value(true))
        .andExpect(jsonPath("$.revision").value(1));

    mvc.perform(
            post("/api/admin/skills")
                .cookie(session)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"skillKey":"missing.tool.skill","displayName":"错误技能","description":"依赖不存在工具","whenToUse":"测试","whenNotToUse":"无","content":"测试","requiredToolKeys":["missing.tool"]}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(9)
  void developerCanUpdateSkillFromTheAdminPage() throws Exception {
    Cookie session = adminLogin("admin", "admin123");

    mvc.perform(
            patch("/api/admin/skills/acceptance.skill")
                .cookie(session)
                .header("If-Match", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"displayName":"验收技能（已修改）","description":"页面修改技能","whenToUse":"需要验收时","whenNotToUse":"无需验收时","content":"读取资料后回答。","requiredToolKeys":["fitness.profile.query"],"status":"ACTIVE"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skillKey").value("acceptance.skill"))
        .andExpect(jsonPath("$.displayName").value("验收技能（已修改）"))
        .andExpect(jsonPath("$.revision").value(2));
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

  private Cookie adminLogin(String username, String password) throws Exception {
    var result =
        mvc.perform(
                post("/api/admin/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    var cookie = result.getResponse().getCookie("AGENT_ADMIN_SESSION");
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
