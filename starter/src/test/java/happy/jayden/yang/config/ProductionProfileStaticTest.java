package happy.jayden.yang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

class ProductionProfileStaticTest {

  private static final Path PRODUCTION_DIRECTORY = projectRoot().resolve("deploy/production");
  private static final Path PRODUCTION_PROFILE =
      projectRoot().resolve("starter/src/main/resources/application-prod.yml");
  private static final Path APP_DOCKERFILE = PRODUCTION_DIRECTORY.resolve("app.Dockerfile");
  private static final Path ENTRYPOINT = PRODUCTION_DIRECTORY.resolve("app-entrypoint.sh");
  private static final Path WEB_DOCKERFILE = PRODUCTION_DIRECTORY.resolve("web.Dockerfile");
  private static final Path ENV_EXAMPLE = PRODUCTION_DIRECTORY.resolve(".env.example");
  private static final Path BASE_IMAGES_LOCK = PRODUCTION_DIRECTORY.resolve("base-images.lock");

  @Test
  void productionFilesExist() {
    assertThat(
            List.of(
                PRODUCTION_PROFILE,
                APP_DOCKERFILE,
                ENTRYPOINT,
                WEB_DOCKERFILE,
                ENV_EXAMPLE,
                BASE_IMAGES_LOCK))
        .allMatch(Files::isRegularFile);
  }

  @Test
  void productionProfileUsesProductionRuntimeDefaults() {
    assumeProductionFilesExist();

    Properties properties = productionProperties();

    assertThat(properties)
        .containsEntry(
            "happy.datasource.fitness.url",
            "${HAPPY_DB_URL:jdbc:postgresql://postgres:5432/happy_agent}")
        .containsEntry("happy.datasource.fitness.username", "fitness_app")
        .containsEntry("happy.datasource.fitness.password", "${FITNESS_DB_PASSWORD}")
        .containsEntry(
            "happy.datasource.agent.url",
            "${HAPPY_DB_URL:jdbc:postgresql://postgres:5432/happy_agent}")
        .containsEntry("happy.datasource.agent.username", "agent_app")
        .containsEntry("happy.datasource.agent.password", "${AGENT_DB_PASSWORD}")
        .containsEntry("happy.security.secure-cookies", true)
        .containsEntry("happy.fitness.local-seed.enabled", false)
        .containsEntry("happy.fitness.local-media.enabled", true)
        .containsEntry("happy.fitness.meal-plan.concurrency", 2)
        .containsEntry("happy.agent.workbench.local-seed.enabled", false)
        .containsEntry(
            "happy.agent.workbench.master-key-file",
            "${HAPPY_AGENT_MASTER_KEY_FILE:/run/secrets/agent-master-key}")
        .containsEntry("server.port", 8080)
        .containsEntry("server.forward-headers-strategy", "framework");
  }

  @Test
  void runtimeImagesArePinnedAndContainOnlyRuntimeContent() throws IOException {
    assumeProductionFilesExist();

    String appDockerfile = Files.readString(APP_DOCKERFILE);
    String webDockerfile = Files.readString(WEB_DOCKERFILE);

    assertThat(appDockerfile)
        .contains(
            "FROM eclipse-temurin:17-jre-jammy@sha256:89e68b9bb83713510b63e2059a415792a7fc77e14b739a7d7ede97f6d9ca2c38")
        .contains("WORKDIR /app")
        .contains("starter/target/starter-*-exec.jar")
        .contains("deploy/production/app-entrypoint.sh")
        .contains("-Xms256m")
        .contains("-Xmx1200m")
        .contains("-XX:MaxMetaspaceSize=256m")
        .contains("-Xss512k")
        .doesNotContainIgnoringCase("maven")
        .doesNotContainIgnoringCase("mvn")
        .doesNotContainIgnoringCase("node")
        .doesNotContainIgnoringCase("npm")
        .doesNotContainIgnoringCase("secret");
    assertThat(webDockerfile)
        .contains(
            "FROM nginx:stable-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46")
        .contains("COPY frontend/dist /usr/share/nginx/html")
        .doesNotContainIgnoringCase("node")
        .doesNotContainIgnoringCase("npm")
        .doesNotContainIgnoringCase("maven")
        .doesNotContainIgnoringCase("mvn");
  }

  @Test
  void entrypointAcceptsTrimmedPasswordsAndPassesOnlyTheirValuesToJava(@TempDir Path tempDir)
      throws IOException, InterruptedException {
    assumeProductionFilesExist();

    EntrypointRun run = runEntrypoint(tempDir, "fitness-password\r\n", "agent-password\n", true);

    assertThat(run.exitCode()).isZero();
    assertThat(Files.exists(run.invocationMarker())).isTrue();
    assertThat(run.output()).doesNotContain("fitness-password", "agent-password");
  }

  @Test
  void entrypointRejectsInvalidPasswordFilesBeforeStartingJava(@TempDir Path tempDir)
      throws IOException, InterruptedException {
    assumeProductionFilesExist();

    EntrypointRun run = runEntrypoint(tempDir, "fitness\npassword", "agent-password", true);

    assertThat(run.exitCode()).isNotZero();
    assertThat(Files.exists(run.invocationMarker())).isFalse();
    assertThat(run.output()).doesNotContain("fitness", "agent-password");
  }

  @Test
  void entrypointRejectsMissingMasterKeyBeforeStartingJava(@TempDir Path tempDir)
      throws IOException, InterruptedException {
    assumeProductionFilesExist();

    EntrypointRun run = runEntrypoint(tempDir, "fitness-password", "agent-password", false);

    assertThat(run.exitCode()).isNotZero();
    assertThat(Files.exists(run.invocationMarker())).isFalse();
  }

  @Test
  void entrypointDoesNotReadOrLogTheMasterKey() throws IOException {
    assumeProductionFilesExist();

    String entrypoint = Files.readString(ENTRYPOINT);

    assertThat(entrypoint)
        .contains("HAPPY_AGENT_MASTER_KEY_FILE")
        .contains("exec java")
        .doesNotContain("cat \"$HAPPY_AGENT_MASTER_KEY_FILE\"")
        .doesNotContain("read HAPPY_AGENT_MASTER_KEY_FILE")
        .doesNotContain("echo \"$HAPPY_AGENT_MASTER_KEY_FILE\"");
  }

  @Test
  void baseImageLockMatchesTheApprovedDigests() throws IOException {
    assumeProductionFilesExist();

    assertThat(Files.readAllLines(BASE_IMAGES_LOCK, StandardCharsets.UTF_8))
        .containsExactly(
            "eclipse-temurin:17-jre-jammy@sha256:89e68b9bb83713510b63e2059a415792a7fc77e14b739a7d7ede97f6d9ca2c38",
            "nginx:stable-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46",
            "certbot/certbot:v5.7.0@sha256:34ee91d2f43008eb78a007d22f23ed4b2eaa9a454cb27ca2c042b49527a695b4",
            "postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777");
  }

  private static Properties productionProperties() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new FileSystemResource(PRODUCTION_PROFILE));
    return yaml.getObject();
  }

  private static void assumeProductionFilesExist() {
    assumeThat(
            List.of(
                PRODUCTION_PROFILE,
                APP_DOCKERFILE,
                ENTRYPOINT,
                WEB_DOCKERFILE,
                ENV_EXAMPLE,
                BASE_IMAGES_LOCK))
        .allMatch(Files::isRegularFile);
  }

  private static EntrypointRun runEntrypoint(
      Path tempDir, String fitnessPassword, String agentPassword, boolean createMasterKey)
      throws IOException, InterruptedException {
    Path fitnessPasswordFile = tempDir.resolve("fitness-password");
    Path agentPasswordFile = tempDir.resolve("agent-password");
    Path masterKeyFile = tempDir.resolve("agent-master-key");
    Path binDirectory = Files.createDirectory(tempDir.resolve("bin"));
    Path java = binDirectory.resolve("java");
    Path invocationMarker = tempDir.resolve("java-invoked");

    Files.writeString(fitnessPasswordFile, fitnessPassword, StandardCharsets.UTF_8);
    Files.writeString(agentPasswordFile, agentPassword, StandardCharsets.UTF_8);
    if (createMasterKey) {
      Files.writeString(masterKeyFile, "test-master-key", StandardCharsets.UTF_8);
    }
    Files.writeString(
        java,
        "#!/bin/sh\n"
            + "[ \"$FITNESS_DB_PASSWORD\" = \"fitness-password\" ] || exit 11\n"
            + "[ \"$AGENT_DB_PASSWORD\" = \"agent-password\" ] || exit 12\n"
            + "[ \"$HAPPY_AGENT_MASTER_KEY_FILE\" = \"$EXPECTED_MASTER_KEY_FILE\" ] || exit 13\n"
            + "touch \"$JAVA_INVOKED_FILE\"\n",
        StandardCharsets.UTF_8);
    assertThat(java.toFile().setExecutable(true)).isTrue();

    ProcessBuilder processBuilder = new ProcessBuilder("sh", ENTRYPOINT.toString());
    processBuilder.environment().put("FITNESS_DB_PASSWORD_FILE", fitnessPasswordFile.toString());
    processBuilder.environment().put("AGENT_DB_PASSWORD_FILE", agentPasswordFile.toString());
    processBuilder.environment().put("HAPPY_AGENT_MASTER_KEY_FILE", masterKeyFile.toString());
    processBuilder.environment().put("EXPECTED_MASTER_KEY_FILE", masterKeyFile.toString());
    processBuilder.environment().put("JAVA_INVOKED_FILE", invocationMarker.toString());
    processBuilder
        .environment()
        .put("PATH", binDirectory + ":" + System.getenv().getOrDefault("PATH", ""));
    processBuilder.redirectErrorStream(true);

    Process process = processBuilder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new EntrypointRun(process.waitFor(), output, invocationMarker);
  }

  private static Path projectRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      if (Files.isRegularFile(directory.resolve("pom.xml"))
          && Files.isDirectory(directory.resolve("starter"))) {
        return directory;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Unable to locate the repository root");
  }

  private record EntrypointRun(int exitCode, String output, Path invocationMarker) {}
}
