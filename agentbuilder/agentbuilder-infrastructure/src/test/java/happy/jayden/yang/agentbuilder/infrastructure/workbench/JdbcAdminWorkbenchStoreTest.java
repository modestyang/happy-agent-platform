package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.runtime.RuntimeCapabilityRegistry;
import happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.SkillCreate;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.CreateAgentRequest;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAdminWorkbenchStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private DataSource dataSource;
  private ObjectMapper mapper;
  private Path masterKey;
  private JdbcAdminWorkbenchStore store;
  private JdbcAdminResourceStore resources;

  @BeforeEach
  void setUp() throws Exception {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA IF EXISTS public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    try (var connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection,
          new EncodedResource(
              new ClassPathResource("db/agent/V1__agent_baseline.sql"), StandardCharsets.UTF_8));
    }
    mapper = new ObjectMapper().findAndRegisterModules();
    masterKey = Files.createTempFile("happy-agent-workbench", ".key");
    Files.writeString(
        masterKey, Base64.getEncoder().encodeToString(new byte[32]), StandardCharsets.US_ASCII);
    store = new JdbcAdminWorkbenchStore(dataSource, mapper, masterKey);
    resources = new JdbcAdminResourceStore(dataSource, mapper);
  }

  @Test
  void baselineDraftAndProvidersComeFromPostgres() throws Exception {
    var draft = store.findDraft("fitness.coach").orElseThrow();

    assertEquals("花爷健身教练", draft.name());
    assertEquals("minimax", draft.providerKey());
    assertEquals("minimax-m3", draft.modelKey());
    assertEquals(16, draft.maxToolCalls());
    assertEquals(
        Set.of(
            "fitness.user.profile.query",
            "fitness.goal.current.query",
            "fitness.training.constraints.query",
            "fitness.nutrition.preferences.query",
            "fitness.body.latest.query",
            "fitness.body.trend.query",
            "fitness.workout.schedule.query",
            "fitness.workout.history.query",
            "fitness.workout.summary.query",
            "fitness.exercise.candidates.query",
            "fitness.exercise.catalog.search",
            "fitness.exercise.details.query",
            "fitness.meal.history.query",
            "fitness.meal.summary.query",
            "fitness.meal.recommendations.query",
            "fitness.meal.feedback.query",
            "fitness.nutrition.targets.estimate",
            "fitness.plan.save"),
        Set.copyOf(draft.toolKeys()));
    assertEquals(2, resources.listProviders().size());
    assertTrue(resources.listProviders().stream().noneMatch(item -> item.configured()));
    assertEquals(
        "https://api.minimax.io/v1",
        resources.listProviders().stream()
            .filter(provider -> provider.providerKey().equals("minimax"))
            .findFirst()
            .orElseThrow()
            .endpoint());
    assertEquals(
        1,
        new JdbcTemplate(dataSource)
            .queryForObject("SELECT count(*) FROM agent_drafts", Integer.class));
    assertEquals(
        "花爷系统提示词",
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT display_name FROM agent_prompts WHERE prompt_key='fitness.coach.prompt'",
                String.class));
    assertTrue(
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT template FROM agent_prompts WHERE prompt_key='fitness.coach.prompt'",
                String.class)
            .startsWith("你是“花爷”"));
    var planRequiredTools =
        mapper.readTree(
            new JdbcTemplate(dataSource)
                .queryForObject(
                    "SELECT required_tool_keys::text FROM agent_skills WHERE skill_key='fitness.plan.skill'",
                    String.class));
    assertTrue(planRequiredTools.toString().contains("fitness.exercise.candidates.query"));
    assertTrue(planRequiredTools.toString().contains("fitness.exercise.details.query"));
    assertFalse(planRequiredTools.toString().contains("fitness.exercise.catalog.search"));
    var mealRequiredTools =
        mapper.readTree(
            new JdbcTemplate(dataSource)
                .queryForObject(
                    "SELECT required_tool_keys::text FROM agent_skills WHERE skill_key='fitness.meal.skill'",
                    String.class));
    assertEquals(
        mapper.valueToTree(
            List.of(
                "fitness.user.profile.query",
                "fitness.goal.current.query",
                "fitness.body.latest.query",
                "fitness.nutrition.preferences.query",
                "fitness.workout.summary.query",
                "fitness.meal.summary.query",
                "fitness.meal.history.query",
                "fitness.meal.recommendations.query",
                "fitness.meal.feedback.query",
                "fitness.nutrition.targets.estimate")),
        mealRequiredTools);
  }

  @Test
  void credentialIsEncryptedAndNeverReturned() throws Exception {
    var plaintext = "sk-secret-value";
    store.saveCredential("bailian", plaintext.toCharArray());

    var stored =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT encode(credential_ciphertext,'base64') FROM agent_provider_credentials WHERE provider_key='bailian'",
                String.class);

    assertFalse(stored.contains(plaintext));
    assertEquals(
        "••••••••",
        resources.listProviders().stream()
            .filter(provider -> provider.providerKey().equals("bailian"))
            .findFirst()
            .orElseThrow()
            .maskedCredential());
  }

  @Test
  void staleRevisionCannotOverwriteDraft() {
    var original = store.findDraft("fitness.coach").orElseThrow();
    var update =
        new DraftUpdate(
            "花爷教练",
            "更新后的说明",
            original.frameworkKey(),
            original.providerKey(),
            original.modelKey(),
            original.promptKey(),
            original.toolKeys(),
            original.skillKeys(),
            original.hookKeys(),
            original.memoryKey(),
            original.temperature(),
            original.maxToolCalls());

    assertThrows(
        AdminWorkbenchPort.Conflict.class, () -> store.updateDraft("fitness.coach", update, 99));
  }

  @Test
  void publishingCreatesImmutableVersionAndAdvancesDraftPointer() {
    var draft = store.findDraft("fitness.coach").orElseThrow();

    var publication = store.publish(draft);

    assertEquals(1, publication.publishedVersion());
    assertEquals(1, store.findDraft("fitness.coach").orElseThrow().publishedVersion());
    assertEquals(
        1,
        new JdbcTemplate(dataSource)
            .queryForObject("SELECT count(*) FROM agent_versions", Integer.class));
  }

  @Test
  void newAgentStartsFromPlatformDefaultsInsteadOfFitnessBusinessBindings() {
    var created = store.createDraft(new CreateAgentRequest("baby.food", "辅食助手", "为家庭提供辅食安排建议"));

    assertEquals("agent.default.prompt", created.promptKey());
    assertEquals("agent.default.memory", created.memoryKey());
    assertEquals(List.of(), created.toolKeys());
    assertEquals(List.of(), created.skillKeys());
    assertEquals(List.of(), created.hookKeys());
    assertEquals(
        1,
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT count(*) FROM agent_prompts WHERE prompt_key='agent.default.prompt'",
                Integer.class));
  }

  @Test
  void startupReconciliationKeepsDeclarativeSkillsAvailableAndDisablesMissingHooks() {
    resources.createSkill(
        new SkillCreate(
            "fruit-only-acceptance",
            "水果推荐验收技能",
            "只推荐水果",
            "用户需要食物推荐时",
            "用户需要训练建议时",
            "只推荐水果，不推荐其他食物。",
            List.of()));

    store.reconcileRuntimeCapabilities(
        (RuntimeCapabilityRegistry)
            (type, key) -> type.equals("SKILL") && key.equals("fitness.meal.skill"));

    var jdbc = new JdbcTemplate(dataSource);
    assertTrue(
        jdbc.queryForObject(
            "SELECT runtime_ready FROM agent_skills WHERE skill_key='fitness.meal.skill'",
            Boolean.class));
    assertTrue(
        jdbc.queryForObject(
            "SELECT runtime_ready FROM agent_skills WHERE skill_key='fitness.plan.skill'",
            Boolean.class));
    assertTrue(
        jdbc.queryForObject(
            "SELECT runtime_ready FROM agent_skills WHERE skill_key='fruit-only-acceptance'",
            Boolean.class));
    assertFalse(
        jdbc.queryForObject(
            "SELECT runtime_ready FROM agent_hooks WHERE hook_key='fitness.safety'",
            Boolean.class));
  }

  @Test
  void publishingEmbedsTheUnifiedMiniMaxRuntimeSnapshotWithoutLeakingPlaintext() throws Exception {
    String secret = "published-report-key";
    store.saveCredential("minimax", secret.toCharArray());

    store.publish(store.findDraft("fitness.coach").orElseThrow());

    String configuration =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT configuration::text FROM agent_versions WHERE agent_key='fitness.coach' AND version=1",
                String.class);
    JsonNode snapshot = mapper.readTree(configuration).path("currentGoalReportRuntime");
    assertEquals("minimax", snapshot.path("provider").path("key").asText());
    assertEquals(
        "https://api.minimax.io/v1",
        snapshot.path("provider").path("config").path("endpoint").asText());
    assertEquals("minimax-m3", snapshot.path("model").path("key").asText());
    assertEquals("MiniMax-M3", snapshot.path("model").path("config").path("model").asText());
    assertTrue(snapshot.path("model").path("config").path("vision").asBoolean());
    assertEquals("fitness.meal.skill", snapshot.path("skills").get(0).path("key").asText());
    assertEquals("fitness.safety", snapshot.path("hooks").get(0).path("key").asText());
    assertEquals("fitness.daily-memory", snapshot.path("memory").path("key").asText());
    assertFalse(snapshot.path("credential").path("ciphertext").asText().isBlank());
    assertFalse(snapshot.path("credential").path("iv").asText().isBlank());
    assertFalse(configuration.contains(secret));
    assertFalse(mapper.writeValueAsString(resources.listProviders()).contains(secret));
  }

  @Test
  void snapshotToleratesNonCanonicalJsonPayloads() {
    new JdbcTemplate(dataSource)
        .update("UPDATE agent_drafts SET tool_keys='[1,2,3]' WHERE agent_key='fitness.coach'");

    assertEquals(List.of("1", "2", "3"), store.findDraft("fitness.coach").orElseThrow().toolKeys());
  }
}
