package happy.jayden.yang.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;

class DatabaseInfrastructureStaticTest {

  private static final Path RESTORE_SCRIPT =
      projectRoot().resolve("deploy/scripts/restore-database.sh");

  @Test
  void agentFlywayWaitsForFitnessMigration() throws NoSuchMethodException {
    Method agentFlyway =
        AgentDataSourceConfig.class.getDeclaredMethod("agentFlyway", javax.sql.DataSource.class);

    DependsOn dependsOn = agentFlyway.getAnnotation(DependsOn.class);

    assertThat(dependsOn).isNotNull();
    assertThat(dependsOn.value()).containsExactly("fitnessFlyway");
  }

  @Test
  void restoreScriptExistsAndPassesShellSyntax() throws IOException, InterruptedException {
    assertThat(Files.isRegularFile(RESTORE_SCRIPT)).isTrue();

    Process process = new ProcessBuilder("bash", "-n", RESTORE_SCRIPT.toString()).start();

    assertThat(process.waitFor()).isZero();
  }

  @Test
  void restoreScriptRejectsMissingArchiveArgument() throws IOException, InterruptedException {
    assertThat(Files.isRegularFile(RESTORE_SCRIPT)).isTrue();

    Process process = new ProcessBuilder("bash", RESTORE_SCRIPT.toString()).start();

    assertThat(process.waitFor()).isEqualTo(2);
  }

  @Test
  void onlyInitShellIsInThePostgresEntrypointScanDirectory() throws IOException {
    String compose = Files.readString(projectRoot().resolve("deploy/docker-compose.yml"));
    String initScript = Files.readString(projectRoot().resolve("deploy/postgres/init.sh"));

    assertThat(compose).contains("target: /docker-entrypoint-initdb.d/00-init.sh");
    assertThat(compose).containsOnlyOnce("/docker-entrypoint-initdb.d/");
    assertThat(compose).doesNotContain("target: /docker-entrypoint-initdb.d/01-init.sql");
    assertThat(compose).contains("target: /usr/local/share/happy-agent-init.sql");
    assertThat(initScript).containsOnlyOnce("--file=/usr/local/share/happy-agent-init.sql");
  }

  @Test
  void testcontainerCopiesVariableSqlOutsideTheEntrypointScanDirectory() throws IOException {
    String integrationTest =
        Files.readString(
            projectRoot()
                .resolve(
                    "starter/src/test/java/happy/jayden/yang/config/DualSchemaIntegrationTest.java"));

    assertThat(integrationTest).contains("/usr/local/share/happy-agent-init.sql");
    assertThat(integrationTest).doesNotContain("/docker-entrypoint-initdb.d/01-init.sql");
  }

  @Test
  void productionRolesOnlyInitDoesNotCreateApplicationSchemas() throws IOException {
    String initScript =
        Files.readString(projectRoot().resolve("deploy/production/postgres/init-roles.sh"));
    String initSql =
        Files.readString(projectRoot().resolve("deploy/production/postgres/init-roles.sql"));

    assertThat(initScript).contains("set -eu");
    assertThat(initScript).contains("--set=ON_ERROR_STOP=1");
    assertThat(initScript).contains("FITNESS_DB_PASSWORD_FILE");
    assertThat(initScript).contains("AGENT_DB_PASSWORD_FILE");
    assertThat(initSql).contains("CREATE ROLE fitness_app");
    assertThat(initSql).contains("CREATE ROLE agent_app");
    assertThat(initSql).contains("NOINHERIT");
    assertThat(initSql).contains("REVOKE ALL ON DATABASE happy_agent FROM PUBLIC");
    assertThat(initSql).doesNotContain("CREATE SCHEMA fitness");
    assertThat(initSql).doesNotContain("CREATE SCHEMA agent");
    assertThat(initSql).doesNotContain("CREATE EXTENSION");
    assertThat(initSql).doesNotContain("CREATE TABLE");
  }

  @Test
  void postRestoreIsolationRevokesCrossSchemaAccessAndSetsDefaults() throws IOException {
    String isolationSql =
        Files.readString(projectRoot().resolve("deploy/production/postgres/enforce-isolation.sql"));

    assertThat(isolationSql).contains("ALTER SCHEMA fitness OWNER TO fitness_app");
    assertThat(isolationSql).contains("ALTER SCHEMA agent OWNER TO agent_app");
    assertThat(isolationSql).contains("REVOKE ALL ON SCHEMA fitness FROM agent_app");
    assertThat(isolationSql).contains("REVOKE ALL ON SCHEMA agent FROM fitness_app");
    assertThat(isolationSql)
        .contains(
            "ALTER ROLE fitness_app IN DATABASE happy_agent SET search_path TO fitness, public");
    assertThat(isolationSql)
        .contains("ALTER ROLE agent_app IN DATABASE happy_agent SET search_path TO agent, public");
    assertThat(isolationSql)
        .contains("ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness");
    assertThat(isolationSql)
        .contains("ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent");
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
}
