package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** DTOs for persisted, real Agent runs and their ordered execution traces. */
public final class WorkspaceDtos {
  private WorkspaceDtos() {}

  public record RunQuery(
      String agentKey,
      String status,
      Instant fromTime,
      Instant toTime,
      int page,
      int size,
      String sort) {
    public RunQuery {
      agentKey = agentKey == null ? "" : agentKey;
      status = status == null ? "" : status;
      page = Math.max(page, 0);
      size = size <= 0 ? 20 : Math.min(size, 200);
      sort = (sort == null || sort.isBlank()) ? "started_at,desc" : sort;
      String[] parts = sort.split(",");
      String column = parts[0].trim();
      String direction = parts.length > 1 ? parts[1].trim().toLowerCase() : "desc";
      if (!(direction.equals("asc") || direction.equals("desc"))) {
        throw new IllegalArgumentException("sort direction must be asc or desc");
      }
      if (!(column.equals("started_at")
          || column.equals("duration_ms")
          || column.equals("prompt_tokens")
          || column.equals("completion_tokens")
          || column.equals("cost_usd")
          || column.equals("tool_calls"))) {
        throw new IllegalArgumentException("unsupported sort column: " + column);
      }
    }
  }

  public record RunPage(
      List<RunSummary> items, long totalElements, int totalPages, int page, int size) {
    public RunPage {
      items = List.copyOf(Objects.requireNonNull(items, "items"));
      if (totalElements < 0 || totalPages < 0 || page < 0 || size <= 0) {
        throw new IllegalArgumentException("invalid run page");
      }
    }
  }

  public record RunSummary(
      UUID runId,
      String agentKey,
      int agentVersion,
      String status,
      Instant startedAt,
      Instant completedAt,
      long durationMs,
      int toolCalls,
      int promptTokens,
      int completionTokens,
      double costUsd,
      String modelKey,
      String errorCode) {
    public RunSummary {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(agentKey, "agentKey");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(startedAt, "startedAt");
    }
  }

  public record RunTrace(
      UUID runId,
      String agentKey,
      int agentVersion,
      String status,
      Instant startedAt,
      Instant completedAt,
      long durationMs,
      int toolCalls,
      int promptTokens,
      int completionTokens,
      double costUsd,
      String modelKey,
      String frameworkKey,
      String errorCode,
      String errorMessage,
      String inputSummary,
      String outputSummary,
      List<TraceEvent> events) {
    public RunTrace {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(agentKey, "agentKey");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(startedAt, "startedAt");
      events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
  }

  public record TraceEvent(
      long sequence,
      String type,
      String title,
      String detail,
      Map<String, Object> payload,
      Instant occurredAt) {
    public TraceEvent {
      if (sequence < 0) throw new IllegalArgumentException("sequence");
      Objects.requireNonNull(type, "type");
      payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  public record ConversationSummary(
      UUID conversationId,
      UUID userId,
      String agentKey,
      String title,
      String status,
      Instant startedAt,
      Instant lastMessageAt,
      int messageCount,
      int runCount) {
    public ConversationSummary {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(agentKey, "agentKey");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(startedAt, "startedAt");
      Objects.requireNonNull(lastMessageAt, "lastMessageAt");
    }
  }

  public record ConversationPage(
      List<ConversationSummary> items, int page, int size, boolean hasNext) {
    public ConversationPage {
      items = List.copyOf(Objects.requireNonNull(items, "items"));
      if (page < 0) throw new IllegalArgumentException("page must be at least 0");
      if (size < 1 || size > 100) {
        throw new IllegalArgumentException("size must be between 1 and 100");
      }
    }
  }

  public record ConversationMessage(
      UUID messageId,
      UUID conversationId,
      UUID runId,
      String role,
      String content,
      Instant createdAt) {
    public ConversationMessage {
      Objects.requireNonNull(messageId, "messageId");
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(content, "content");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  public record ConversationDetail(
      ConversationSummary conversation, List<ConversationMessage> messages, List<RunSummary> runs) {
    public ConversationDetail {
      Objects.requireNonNull(conversation, "conversation");
      messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
      runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
    }
  }
}
