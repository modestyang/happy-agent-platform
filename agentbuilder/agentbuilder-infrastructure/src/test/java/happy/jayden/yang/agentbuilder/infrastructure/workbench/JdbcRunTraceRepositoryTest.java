package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private UUID conversationId;
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
    conversationId = conversation.conversationId();
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
  void keepsStructuredPayloadOnTheTraceTimeline() {
    repository.appendEvent(runId, 1, "BLOCK_STARTED", "TEXT", "文本块", Map.of("blockId", "text-1"));

    var trace = repository.findTrace(runId).orElseThrow();

    assertEquals(Map.of("blockId", "text-1"), trace.events().get(0).payload());
  }

  @Test
  void startsANewConversationWithoutReusingTheActiveConversation() {
    Instant now = Instant.now();
    var current = repository.resolveConversation(userId, "fitness.coach", now);

    var fresh = repository.startNewConversation(userId, "fitness.coach", now.plusSeconds(1));

    assertNotEquals(current.conversationId(), fresh.conversationId());
    assertEquals(
        fresh.conversationId(),
        repository
            .resolveConversation(userId, "fitness.coach", now.plusSeconds(2))
            .conversationId());
  }

  @Test
  void pagesRecentConversationsWithAStableHasNextSignal() {
    UUID newerConversationId =
        repository
            .startNewConversation(UUID.randomUUID(), "fitness.other", Instant.now().plusSeconds(1))
            .conversationId();

    var first = repository.listRecentConversationSummaries(0, 1);
    var second = repository.listRecentConversationSummaries(1, 1);

    assertEquals(List.of(newerConversationId), conversationIds(first.items()));
    assertTrue(first.hasNext());
    assertEquals(List.of(conversationId), conversationIds(second.items()));
    assertFalse(second.hasNext());
  }

  @Test
  void matchesAnIdentifierAgainstUserIdOrConversationId() {
    assertEquals(
        List.of(conversationId),
        conversationIds(repository.listConversationSummariesByIdentifier(userId, 0, 10).items()));
    assertEquals(
        List.of(conversationId),
        conversationIds(
            repository.listConversationSummariesByIdentifier(conversationId, 0, 10).items()));
  }

  @Test
  void matchesOnlyResolvedUsernameUserIds() {
    repository.startNewConversation(
        UUID.randomUUID(), "fitness.other", Instant.now().plusSeconds(1));

    assertEquals(
        List.of(conversationId),
        conversationIds(
            repository.listConversationSummariesByUserIds(Set.of(userId), 0, 10).items()));
    assertTrue(repository.listConversationSummariesByUserIds(Set.of(), 0, 10).items().isEmpty());
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
            .approval()
            .status());
    assertEquals(
        "APPROVED",
        repository
            .decideApproval(runId, approval.approvalId(), userId, "APPROVE", "decision-1")
            .approval()
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

  private static List<UUID> conversationIds(List<WorkspaceDtos.ConversationSummary> conversations) {
    return conversations.stream().map(WorkspaceDtos.ConversationSummary::conversationId).toList();
  }
}
