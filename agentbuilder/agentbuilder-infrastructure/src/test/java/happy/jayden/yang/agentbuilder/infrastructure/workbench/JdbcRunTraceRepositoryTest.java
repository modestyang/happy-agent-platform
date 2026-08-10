package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
class JdbcRunTraceRepositoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private JdbcRunTraceRepository repository;
  private UUID runId;
  private UUID userId;

  @BeforeEach
  void setUp() throws Exception {
    DataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA IF EXISTS public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    try (var connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V1__agent_baseline.sql"));
    }
    repository = new JdbcRunTraceRepository(dataSource);
    userId = UUID.randomUUID();
    var conversation = repository.resolveConversation(userId, "fitness.coach", Instant.now());
    runId = UUID.randomUUID();
    repository.insertRun(
        runId,
        userId,
        conversation.conversationId(),
        "fitness.coach",
        1,
        "agentscope",
        "MiniMax-M3",
        "计划");
  }

  @Test
  void replaysStructuredEventsAfterTheLastSequence() {
    repository.appendStreamEvent(runId, "RUN_STATE", Map.of("status", "RUNNING"));
    repository.appendStreamEvent(runId, "TEXT_DELTA", Map.of("delta", "你好"));

    var replay = repository.streamEventsAfter(runId, 1);

    assertEquals(1, replay.size());
    assertEquals(2, replay.get(0).sequence());
    assertEquals("TEXT_DELTA", replay.get(0).type());
    assertEquals("你好", replay.get(0).data().get("delta"));
  }

  @Test
  void approvalDecisionIsOwnerBoundAndIdempotent() {
    var approval =
        repository.requestApproval(
            runId, userId, "fitness.plan.save", "保存未来 7 天训练计划", Map.of("scope", "WEEK"));

    assertEquals(
        "APPROVED",
        repository
            .decideApproval(runId, approval.approvalId(), userId, "APPROVE", "decision-1")
            .status());
    assertEquals(
        "APPROVED",
        repository
            .decideApproval(runId, approval.approvalId(), userId, "APPROVE", "decision-1")
            .status());
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.decideApproval(
                runId, approval.approvalId(), UUID.randomUUID(), "APPROVE", "decision-2"));
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.decideApproval(
                runId, approval.approvalId(), userId, "REJECT", "decision-3"));
  }
}
