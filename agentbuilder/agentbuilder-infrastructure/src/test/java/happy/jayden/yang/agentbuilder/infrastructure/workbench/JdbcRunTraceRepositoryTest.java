package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
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

  @BeforeEach
  void setUp() throws Exception {
    DataSource source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (var connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/agent/V4__agent_workbench.sql"));
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/agent/V5__observability_rollup.sql"));
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/agent/V10__agent_conversations.sql"));
    }
    repository = new JdbcRunTraceRepository(source);
  }

  @Test
  void reusesRecentConversationAndReturnsChronologicalMessageHistory() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-10T08:00:00Z");

    var first = repository.resolveConversation(userId, "fitness.coach", now);
    repository.appendConversationMessage(first.conversationId(), null, "USER", "晚饭吃什么？", now);
    repository.appendConversationMessage(
        first.conversationId(), null, "ASSISTANT", "今晚可以吃虾仁蔬菜。", now.plusSeconds(1));
    var reused =
        repository.resolveConversation(userId, "fitness.coach", now.plus(Duration.ofHours(23)));

    assertEquals(first.conversationId(), reused.conversationId());
    assertEquals(
        java.util.List.of("晚饭吃什么？", "今晚可以吃虾仁蔬菜。"),
        repository.recentConversationMessages(first.conversationId(), 20).stream()
            .map(WorkspaceDtos.ConversationMessage::content)
            .toList());
  }
}
