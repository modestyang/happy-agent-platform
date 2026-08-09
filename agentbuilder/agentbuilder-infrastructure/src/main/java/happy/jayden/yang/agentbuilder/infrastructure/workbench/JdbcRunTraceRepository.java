package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunPage;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunQuery;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunSummary;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunTrace;
import static happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.TraceEvent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

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
      String agentKey,
      int agentVersion,
      String frameworkKey,
      String modelKey,
      String inputSummary) {
    jdbc.update(
        "INSERT INTO agent_runs (run_id, agent_key, agent_version, status, model_key,"
            + " framework_key, input_summary, started_at)"
            + " VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?)",
        runId,
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
}
