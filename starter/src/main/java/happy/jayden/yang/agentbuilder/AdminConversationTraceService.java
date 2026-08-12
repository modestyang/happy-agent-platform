package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.ConversationPage;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.ConversationSummary;
import happy.jayden.yang.fitness.infrastructure.JdbcFitnessUserDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Coordinates read-only conversation search across the independently owned schemas. */
public final class AdminConversationTraceService {
  private static final int MAX_QUERY_LENGTH = 160;
  private static final String UNAVAILABLE_USERNAME = "用户名不可用";
  private final JdbcRunTraceRepository traces;
  private final JdbcFitnessUserDirectory users;

  public AdminConversationTraceService(
      JdbcRunTraceRepository traces, JdbcFitnessUserDirectory users) {
    this.traces = Objects.requireNonNull(traces, "traces");
    this.users = Objects.requireNonNull(users, "users");
  }

  public ConversationPageView conversations(String rawQuery, int page, int size) {
    String query = rawQuery == null ? "" : rawQuery.trim();
    validate(query, page, size);
    UUID identifier = parseUuid(query);
    ConversationPage result;
    if (query.isEmpty()) {
      result = traces.listRecentConversationSummaries(page, size);
    } else if (identifier != null) {
      result = traces.listConversationSummariesByIdentifier(identifier, page, size);
    } else {
      result =
          traces.listConversationSummariesByUserIds(
              Set.copyOf(users.searchUserIds(query)), page, size);
    }
    Set<UUID> userIds =
        result.items().stream().map(ConversationSummary::userId).collect(Collectors.toSet());
    Map<UUID, String> usernames = users.findUsernames(userIds);
    List<ConversationView> items =
        result.items().stream()
            .map(
                item ->
                    ConversationView.from(
                        item, usernames.getOrDefault(item.userId(), UNAVAILABLE_USERNAME)))
            .toList();
    return new ConversationPageView(items, result.page(), result.size(), result.hasNext());
  }

  private static void validate(String query, int page, int size) {
    if (query.length() > MAX_QUERY_LENGTH) {
      throw new IllegalArgumentException("query 最多 160 个字符");
    }
    if (page < 0) throw new IllegalArgumentException("page 必须大于或等于 0");
    if (size < 1 || size > 100) throw new IllegalArgumentException("size 必须在 1 到 100 之间");
  }

  private static UUID parseUuid(String value) {
    if (value.isEmpty()) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  public record ConversationPageView(
      List<ConversationView> items, int page, int size, boolean hasNext) {
    public ConversationPageView {
      items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
  }

  public record ConversationView(
      UUID conversationId,
      UUID userId,
      String username,
      String agentKey,
      String title,
      String status,
      Instant startedAt,
      Instant lastMessageAt,
      int messageCount,
      int runCount) {
    private static ConversationView from(ConversationSummary source, String username) {
      return new ConversationView(
          source.conversationId(),
          source.userId(),
          username,
          source.agentKey(),
          source.title(),
          source.status(),
          source.startedAt(),
          source.lastMessageAt(),
          source.messageCount(),
          source.runCount());
    }
  }
}
