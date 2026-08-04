package happy.jayden.yang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(classes = {FitnessDataSourceConfig.class, AgentDataSourceConfig.class})
class DualSchemaIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("happy_agent")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("deploy/postgres/init.sql").toAbsolutePath()),
              "/docker-entrypoint-initdb.d/00-init.sql");

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
  }

  @Test
  void rolesCannotReadTheOtherSchema() {
    JdbcTemplate fitnessJdbc = new JdbcTemplate(fitnessDataSource);
    JdbcTemplate agentJdbc = new JdbcTemplate(agentDataSource);

    assertThat(fitnessJdbc.queryForObject("select count(*) from fitness.users", Long.class))
        .isZero();
    assertThat(agentJdbc.queryForObject("select count(*) from agent.agent_versions", Long.class))
        .isZero();
    assertThatThrownBy(
            () ->
                fitnessJdbc.queryForObject("select count(*) from agent.agent_versions", Long.class))
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () -> agentJdbc.queryForObject("select count(*) from fitness.users", Long.class))
        .hasMessageContaining("permission denied");
  }

  @Test
  void eachSchemaHasAnIndependentMigrationHistory() {
    JdbcTemplate fitnessJdbc = new JdbcTemplate(fitnessDataSource);
    JdbcTemplate agentJdbc = new JdbcTemplate(agentDataSource);

    assertThat(
            fitnessJdbc.queryForObject(
                "select count(*) from fitness.fitness_schema_history", Long.class))
        .isEqualTo(1L);
    assertThat(
            agentJdbc.queryForObject("select count(*) from agent.agent_schema_history", Long.class))
        .isEqualTo(1L);
  }
}
