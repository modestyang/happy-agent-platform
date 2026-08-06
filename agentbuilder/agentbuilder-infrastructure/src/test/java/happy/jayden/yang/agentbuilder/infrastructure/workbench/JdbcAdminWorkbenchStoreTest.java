package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
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
          connection, new ClassPathResource("db/agent/V1__agent_baseline.sql"));
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V4__agent_workbench.sql"));
    }
    mapper = new ObjectMapper().findAndRegisterModules();
    masterKey = Files.createTempFile("happy-agent-workbench", ".key");
    Files.writeString(
        masterKey, Base64.getEncoder().encodeToString(new byte[32]), StandardCharsets.US_ASCII);
    store = new JdbcAdminWorkbenchStore(dataSource, mapper, masterKey);
    new AdminWorkbenchLocalSeed(store).seed();
  }

  @Test
  void localSeedIsIdempotentAndSnapshotComesFromPostgres() {
    new AdminWorkbenchLocalSeed(store).seed();

    var snapshot = store.snapshot();

    assertEquals("fitness.coach", snapshot.agents().get(0).agentKey());
    assertTrue(snapshot.components().size() >= 10);
    assertEquals(1, snapshot.providers().size());
    assertFalse(snapshot.providers().get(0).configured());
    assertEquals(
        1,
        new JdbcTemplate(dataSource)
            .queryForObject("SELECT count(*) FROM agent_drafts", Integer.class));
  }

  @Test
  void credentialIsEncryptedAndNeverReturned() throws Exception {
    var plaintext = "sk-secret-value";
    store.saveCredential("bailian", plaintext.toCharArray());

    var snapshotJson = mapper.writeValueAsString(store.snapshot());
    var stored =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT encode(credential_ciphertext,'base64') FROM agent_provider_credentials WHERE provider_key='bailian'",
                String.class);

    assertFalse(snapshotJson.contains(plaintext));
    assertFalse(stored.contains(plaintext));
    assertEquals("••••••••", store.snapshot().providers().get(0).maskedCredential());
  }

  @Test
  void staleRevisionCannotOverwriteDraft() {
    var original = store.findDraft("fitness.coach").orElseThrow();
    var update =
        new DraftUpdate(
            "瘦瘦教练",
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
  void snapshotToleratesNonCanonicalJsonPayloads() {
    new JdbcTemplate(dataSource)
        .update("UPDATE agent_drafts SET tool_keys='[1,2,3]' WHERE agent_key='fitness.coach'");

    var snapshot = store.snapshot();

    assertEquals(List.of("1", "2", "3"), snapshot.agents().get(0).toolKeys());
  }
}
