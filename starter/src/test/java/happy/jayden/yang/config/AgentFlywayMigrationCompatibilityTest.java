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

    assertThat(flyway.migrate().targetSchemaVersion).isEqualTo("1");

    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    assertThat(tableExists(jdbc, "agent_conversations")).isTrue();
    assertThat(tableExists(jdbc, "agent_conversation_messages")).isTrue();
    assertThat(tableExists(jdbc, "agent_run_stream_events")).isTrue();
    assertThat(tableExists(jdbc, "agent_run_approvals")).isTrue();
    assertThat(tableExists(jdbc, "agent_providers")).isTrue();
    assertThat(tableExists(jdbc, "agent_models")).isTrue();
    assertThat(tableExists(jdbc, "agent_prompts")).isTrue();
    assertThat(tableExists(jdbc, "agent_skills")).isTrue();
    assertThat(tableExists(jdbc, "agent_hooks")).isTrue();
    assertThat(tableExists(jdbc, "agent_frameworks")).isTrue();
    assertThat(tableExists(jdbc, "agent_memories")).isTrue();
    assertThat(tableExists(jdbc, "agent_component_projection")).isFalse();
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  void cleanBaselineSeedsModelsUnderTheirProvider() {
    var flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(DEFAULTS_SCHEMA)
            .defaultSchema(DEFAULTS_SCHEMA)
            .table("agent_schema_history")
            .locations("classpath:db/agent")
            .createSchemas(true)
            .load();
    flyway.migrate();
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + DEFAULTS_SCHEMA
                    + ".agent_models m JOIN "
                    + DEFAULTS_SCHEMA
                    + ".agent_providers p ON p.provider_key=m.provider_key",
                Integer.class))
        .isEqualTo(3);
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
