package happy.jayden.yang.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AgentFlywayMigrationCompatibilityTest {
  private static final String SCHEMA = "agent_compatibility";
  private static final String DEFAULTS_SCHEMA = "agent_default_migration";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void cleanPostgresAppliesTheAgentMigrationChainThroughConversationPersistence() {
    var flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .table("agent_schema_history")
            .locations("classpath:db/agent")
            .createSchemas(true)
            .load();

    assertThat(flyway.migrate().targetSchemaVersion).isEqualTo("11");

    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    assertThat(tableExists(jdbc, "agent_conversations")).isTrue();
    assertThat(tableExists(jdbc, "agent_conversation_messages")).isTrue();
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  void normalizesAnExistingNonFitnessDraftWithoutViolatingThePublishedVersionConstraint() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(DEFAULTS_SCHEMA)
        .defaultSchema(DEFAULTS_SCHEMA)
        .table("agent_schema_history")
        .locations("classpath:db/agent")
        .target("10")
        .createSchemas(true)
        .load()
        .migrate();
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.update(
        "INSERT INTO "
            + DEFAULTS_SCHEMA
            + ".agent_drafts(agent_key,name,description,status,framework_key,provider_key,model_key,prompt_key,tool_keys,skill_keys,hook_keys,memory_key,temperature,max_tool_calls,current_published_version,revision) "
            + "VALUES ('baby.food','辅食助手','测试','DRAFT','agentscope','bailian','qwen-plus','fitness.coach.prompt','[\"fitness.profile.query\"]'::jsonb,'[]'::jsonb,'[\"fitness.safety\"]'::jsonb,'fitness.daily-memory',0.5,8,2,1)");

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(DEFAULTS_SCHEMA)
        .defaultSchema(DEFAULTS_SCHEMA)
        .table("agent_schema_history")
        .locations("classpath:db/agent")
        .createSchemas(true)
        .load()
        .migrate();

    var row =
        jdbc.queryForMap(
            "SELECT prompt_key,memory_key,tool_keys::text,hook_keys::text,current_published_version,status FROM "
                + DEFAULTS_SCHEMA
                + ".agent_drafts WHERE agent_key='baby.food'");
    assertThat(row.get("prompt_key")).isEqualTo("agent.default.prompt");
    assertThat(row.get("memory_key")).isEqualTo("agent.default.memory");
    assertThat(row.get("tool_keys")).isEqualTo("[]");
    assertThat(row.get("hook_keys")).isEqualTo("[]");
    assertThat(row.get("current_published_version")).isEqualTo(0);
    assertThat(row.get("status")).isEqualTo("DRAFT");
  }

  private static boolean tableExists(JdbcTemplate jdbc, String table) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=? AND table_name=?)",
            Boolean.class,
            SCHEMA,
            table));
  }
}
