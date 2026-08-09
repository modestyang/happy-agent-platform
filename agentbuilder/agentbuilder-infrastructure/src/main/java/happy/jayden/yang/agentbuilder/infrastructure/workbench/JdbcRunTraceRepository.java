package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunPage;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunQuery;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunSummary;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunTrace;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.TraceEvent;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.ConversationDetail;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.ConversationMessage;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.ConversationSummary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;

/**
 * Run listing, trace detail, and bulk upsert for trace state.
 *
 * <p>The repository is the only place that knows the SQL for the existing {@code agent_runs} /
 * {@code agent_run_events} shadow tables. Repositories never reach into the network and never
 * invoke services — they only do parameterized queries.
 */
public final class JdbcRunTraceRepository {

  private final JdbcTemplate jdbc;

  public JdbcRunTraceRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
  }

  public RunPage list(RunQuery query) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> args = new java.util.ArrayList<>();
    if (!query.agentKey().isBlank()) {
      where.append(" AND agent_key = ?");
      args.add(query.agentKey());
    }
    if (!query.status().isBlank()) {
      where.append(" AND status = ?");
      args.add(query.status());
    }
    if (query.fromTime() != null) {
      where.append(" AND started_at >= ?");
      args.add(Timestamp.from(query.fromTime()));
    }
    if (query.toTime() != null) {
      where.append(" AND started_at < ?");
      args.add(Timestamp.from(query.toTime()));
    }
    String orderBy = orderBy(query.sort());

    long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs" + where, Long.class, args.toArray());
    int totalPages = (int) Math.ceil(total / (double) query.size());
    int offset = Math.min(query.page(), Math.max(0, totalPages - 1)) * query.size();

    List<RunSummary> items =
        jdbc.query(
            "SELECT run_id, agent_key, agent_version, status, started_at, completed_at,"
                + " duration_ms, tool_calls, prompt_tokens, completion_tokens, cost_usd,"
                + " model_key, error_code"
                + " FROM agent_runs"
                + where
                + orderBy
                + " LIMIT ? OFFSET ?",
            (rs, row) -> mapSummary(rs),
            concat(args, query.size(), offset));
    return new RunPage(items, total, totalPages, query.page(), query.size());
  }

  /** Resolves the user's active 24-hour conversation, or starts a fresh one. */
  public ConversationSummary resolveConversation(UUID userId, String agentKey, Instant now) {
    Instant expiry = now.minus(Duration.ofHours(24));
    jdbc.update(
        "UPDATE agent_conversations SET status='CLOSED', closed_at=? WHERE user_id=? AND agent_key=?"
            + " AND status='ACTIVE' AND last_message_at < ?",
        Timestamp.from(now),
        userId,
        agentKey,
        Timestamp.from(expiry));
    var existing =
        jdbc.query(
                "SELECT conversation_id, user_id, agent_key, title, status, started_at, last_message_at,"
                    + " (SELECT count(*) FROM agent_conversation_messages m WHERE m.conversation_id=c.conversation_id) AS message_count,"
                    + " (SELECT count(*) FROM agent_runs r WHERE r.conversation_id=c.conversation_id) AS run_count"
                    + " FROM agent_conversations c WHERE user_id=? AND agent_key=? AND status='ACTIVE'"
                    + " AND last_message_at >= ? ORDER BY last_message_at DESC LIMIT 1",
                (rs, row) -> mapConversationSummary(rs),
                userId,
                agentKey,
                Timestamp.from(expiry))
            .stream()
            .findFirst();
    if (existing.isPresent()) return existing.get();

    UUID conversationId = UUID.randomUUID();
    try {
      jdbc.update(
          "INSERT INTO agent_conversations (conversation_id, user_id, agent_key, title, status, started_at, last_message_at)"
              + " VALUES (?, ?, ?, '', 'ACTIVE', ?, ?)",
          conversationId,
          userId,
          agentKey,
          Timestamp.from(now),
          Timestamp.from(now));
    } catch (DuplicateKeyException concurrentRequest) {
      return resolveConversation(userId, agentKey, now);
    }
    return new ConversationSummary(conversationId, userId, agentKey, "", "ACTIVE", now, now, 0, 0);
  }

  public void appendConversationMessage(
      UUID conversationId, UUID runId, String role, String content, Instant createdAt) {
    jdbc.update(
        "INSERT INTO agent_conversation_messages (message_id, conversation_id, run_id, role, content, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        conversationId,
        runId,
        role,
        content == null ? "" : content,
        Timestamp.from(createdAt));
    jdbc.update(
        "UPDATE agent_conversations SET last_message_at=?, title=CASE WHEN title='' AND ?='USER' THEN ? ELSE title END"
            + " WHERE conversation_id=?",
        Timestamp.from(createdAt),
        role,
        truncate(content, 48),
        conversationId);
  }

  /** Returns history in chronological order, with a bounded tail query. */
  public List<ConversationMessage> recentConversationMessages(UUID conversationId, int limit) {
    int bounded = Math.max(1, Math.min(limit, 100));
    return jdbc.query(
        "SELECT message_id, conversation_id, run_id, role, content, created_at FROM ("
            + " SELECT message_id, conversation_id, run_id, role, content, created_at"
            + " FROM agent_conversation_messages WHERE conversation_id=?"
            + " ORDER BY created_at DESC, message_id DESC LIMIT ?) recent"
            + " ORDER BY created_at ASC, message_id ASC",
        (rs, row) -> mapConversationMessage(rs),
        conversationId,
        bounded);
  }

  public List<ConversationSummary> listConversationSummaries(UUID userId, int page, int size) {
    int boundedSize = size <= 0 ? 20 : Math.min(size, 100);
    int offset = Math.max(page, 0) * boundedSize;
    return jdbc.query(
        "SELECT c.conversation_id, c.user_id, c.agent_key, c.title, c.status, c.started_at, c.last_message_at,"
            + " (SELECT count(*) FROM agent_conversation_messages m WHERE m.conversation_id=c.conversation_id) AS message_count,"
            + " (SELECT count(*) FROM agent_runs r WHERE r.conversation_id=c.conversation_id) AS run_count"
            + " FROM agent_conversations c WHERE c.user_id=? ORDER BY c.last_message_at DESC LIMIT ? OFFSET ?",
        (rs, row) -> mapConversationSummary(rs),
        userId,
        boundedSize,
        offset);
  }

  public Optional<ConversationDetail> findConversation(UUID conversationId) {
    return jdbc.query(
            "SELECT c.conversation_id, c.user_id, c.agent_key, c.title, c.status, c.started_at, c.last_message_at,"
                + " (SELECT count(*) FROM agent_conversation_messages m WHERE m.conversation_id=c.conversation_id) AS message_count,"
                + " (SELECT count(*) FROM agent_runs r WHERE r.conversation_id=c.conversation_id) AS run_count"
                + " FROM agent_conversations c WHERE c.conversation_id=?",
            (rs, row) -> mapConversationSummary(rs),
            conversationId)
        .stream()
        .findFirst()
        .map(
            conversation ->
                new ConversationDetail(
                    conversation,
                    recentConversationMessages(conversationId, 100),
                    jdbc.query(
                        "SELECT run_id, agent_key, agent_version, status, started_at, completed_at,"
                            + " duration_ms, tool_calls, prompt_tokens, completion_tokens, cost_usd, model_key, error_code"
                            + " FROM agent_runs WHERE conversation_id=? ORDER BY started_at ASC",
                        (rs, row) -> mapSummary(rs),
                        conversationId)));
  }

  public Optional<RunTrace> findTrace(UUID runId) {
    return jdbc
        .query(
            "SELECT run_id, agent_key, agent_version, status, started_at, completed_at,"
                + " duration_ms, tool_calls, prompt_tokens, completion_tokens, cost_usd,"
                + " model_key, framework_key, error_code, error_message,"
                + " input_summary, output_summary"
                + " FROM agent_runs WHERE run_id = ?",
            (rs, row) -> {
              UUID id = (UUID) rs.getObject("run_id");
              Timestamp completed = rs.getTimestamp("completed_at");
              List<TraceEvent> events =
                  jdbc.query(
                      "SELECT sequence, event_type, title, detail, occurred_at"
                          + " FROM agent_run_events WHERE run_id = ? ORDER BY sequence",
                      (eventRs, eventRow) -> mapEvent(eventRs),
                      id);
              return new RunTrace(
                  id,
                  rs.getString("agent_key"),
                  rs.getInt("agent_version"),
                  rs.getString("status"),
                  rs.getTimestamp("started_at").toInstant(),
                  completed == null ? null : completed.toInstant(),
                  rs.getLong("duration_ms"),
                  rs.getInt("tool_calls"),
                  rs.getInt("prompt_tokens"),
                  rs.getInt("completion_tokens"),
                  rs.getDouble("cost_usd"),
                  rs.getString("model_key"),
                  rs.getString("framework_key"),
                  rs.getString("error_code"),
                  rs.getString("error_message"),
                  rs.getString("input_summary"),
                  rs.getString("output_summary"),
                  events);
            },
            runId)
        .stream()
        .findFirst();
  }

  public void insertRun(
      UUID runId,
      UUID userId,
      UUID conversationId,
      String agentKey,
      int agentVersion,
      String frameworkKey,
      String modelKey,
      String inputSummary) {
    jdbc.update(
        "INSERT INTO agent_runs (run_id, user_id, conversation_id, agent_key, agent_version, status, model_key,"
            + " framework_key, input_summary, started_at)"
            + " VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?)",
        runId,
        userId,
        conversationId,
        agentKey,
        agentVersion,
        modelKey,
        frameworkKey,
        inputSummary,
        Timestamp.from(Instant.now()));
  }

  public void markCompleted(
      UUID runId,
      String status,
      Instant completedAt,
      long durationMs,
      int toolCalls,
      int promptTokens,
      int completionTokens,
      double costUsd,
      String modelKey,
      String errorCode,
      String errorMessage,
      String outputSummary) {
    jdbc.update(
        "UPDATE agent_runs SET status = ?, completed_at = ?, duration_ms = ?, tool_calls = ?,"
            + " prompt_tokens = ?, completion_tokens = ?, cost_usd = ?, model_key = ?,"
            + " error_code = ?, error_message = ?, output_summary = ? WHERE run_id = ?",
        status,
        Timestamp.from(completedAt),
        durationMs,
        toolCalls,
        promptTokens,
        completionTokens,
        costUsd,
        modelKey,
        errorCode,
        errorMessage,
        outputSummary,
        runId);
  }

  public void appendEvent(UUID runId, long sequence, String type, String title, String detail) {
    jdbc.update(
        "INSERT INTO agent_run_events (run_id, sequence, event_type, title, detail, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        runId,
        sequence,
        type,
        title,
        detail,
        Timestamp.from(Instant.now()));
  }

  public Optional<Instant> latestEventAt(UUID runId) {
    return jdbc
        .query(
            "SELECT MAX(occurred_at) FROM agent_run_events WHERE run_id = ?",
            (rs, row) -> {
              Timestamp t = rs.getTimestamp(1);
              return t == null ? null : t.toInstant();
            },
            runId)
        .stream()
        .findFirst()
        .flatMap(Optional::ofNullable);
  }

  private static String orderBy(String sort) {
    if (sort == null || sort.isBlank()) return " ORDER BY started_at DESC";
    String[] parts = sort.split(",");
    String column = parts[0].trim();
    String direction = parts.length > 1 ? parts[1].trim().toLowerCase() : "desc";
    if (!direction.equals("asc") && !direction.equals("desc")) {
      throw new IllegalArgumentException("sort direction must be asc or desc");
    }
    return switch (column) {
      case "started_at",
              "duration_ms",
              "prompt_tokens",
              "completion_tokens",
              "cost_usd",
              "tool_calls" ->
          " ORDER BY " + column + " " + direction.toUpperCase();
      default -> throw new IllegalArgumentException("unsupported sort column: " + column);
    };
  }

  private static Object[] concat(List<Object> args, int size, int offset) {
    Object[] extended = new Object[args.size() + 2];
    for (int i = 0; i < args.size(); i++) extended[i] = args.get(i);
    extended[args.size()] = size;
    extended[args.size() + 1] = offset;
    return extended;
  }

  private static RunSummary mapSummary(ResultSet rs) throws SQLException {
    Timestamp completed = rs.getTimestamp("completed_at");
    return new RunSummary(
        (UUID) rs.getObject("run_id"),
        rs.getString("agent_key"),
        rs.getInt("agent_version"),
        rs.getString("status"),
        rs.getTimestamp("started_at").toInstant(),
        completed == null ? null : completed.toInstant(),
        rs.getLong("duration_ms"),
        rs.getInt("tool_calls"),
        rs.getInt("prompt_tokens"),
        rs.getInt("completion_tokens"),
        rs.getDouble("cost_usd"),
        rs.getString("model_key"),
        rs.getString("error_code"));
  }

  private static TraceEvent mapEvent(ResultSet rs) throws SQLException {
    return new TraceEvent(
        rs.getLong("sequence"),
        rs.getString("event_type"),
        rs.getString("title"),
        rs.getString("detail"),
        rs.getTimestamp("occurred_at").toInstant());
  }

  private static ConversationSummary mapConversationSummary(ResultSet rs) throws SQLException {
    return new ConversationSummary(
        (UUID) rs.getObject("conversation_id"),
        (UUID) rs.getObject("user_id"),
        rs.getString("agent_key"),
        rs.getString("title"),
        rs.getString("status"),
        rs.getTimestamp("started_at").toInstant(),
        rs.getTimestamp("last_message_at").toInstant(),
        rs.getInt("message_count"),
        rs.getInt("run_count"));
  }

  private static ConversationMessage mapConversationMessage(ResultSet rs) throws SQLException {
    return new ConversationMessage(
        (UUID) rs.getObject("message_id"),
        (UUID) rs.getObject("conversation_id"),
        (UUID) rs.getObject("run_id"),
        rs.getString("role"),
        rs.getString("content"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static String truncate(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }
}
