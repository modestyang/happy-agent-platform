package happy.jayden.yang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

@Testcontainers
@SpringJUnitConfig(classes = {FitnessDataSourceConfig.class, AgentDataSourceConfig.class})
class DualSchemaIntegrationTest {

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
  }

  private static Path testSecret(String name, String value) {
    try {
      Path secret = Files.createTempFile("happy-agent-", "-" + name);
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
    throw new IllegalStateException("Unable to locate the repository root");
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
        .hasStackTraceContaining("permission denied");
    assertThatThrownBy(
            () -> agentJdbc.queryForObject("select count(*) from fitness.users", Long.class))
        .hasStackTraceContaining("permission denied");
  }

  @Test
  void eachSchemaHasAnIndependentMigrationHistory() {
    JdbcTemplate fitnessJdbc = new JdbcTemplate(fitnessDataSource);
    JdbcTemplate agentJdbc = new JdbcTemplate(agentDataSource);

    assertThat(
            fitnessJdbc.queryForObject(
                "select count(*) from fitness.fitness_schema_history", Long.class))
        .isEqualTo(3L);
    assertThat(
            agentJdbc.queryForObject("select count(*) from agent.agent_schema_history", Long.class))
        .isEqualTo(4L);
  }
}
