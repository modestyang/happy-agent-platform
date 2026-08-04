package happy.jayden.yang.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;

class DatabaseInfrastructureStaticTest {

  private static final Path RESTORE_SCRIPT = projectRoot().resolve("deploy/scripts/restore-database.sh");

  @Test
  void agentFlywayWaitsForFitnessMigration() throws NoSuchMethodException {
    Method agentFlyway = AgentDataSourceConfig.class.getDeclaredMethod("agentFlyway", javax.sql.DataSource.class);

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
        Files.readString(projectRoot().resolve("starter/src/test/java/happy/jayden/yang/config/DualSchemaIntegrationTest.java"));

    assertThat(integrationTest).contains("/usr/local/share/happy-agent-init.sql");
    assertThat(integrationTest).doesNotContain("/docker-entrypoint-initdb.d/01-init.sql");
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
