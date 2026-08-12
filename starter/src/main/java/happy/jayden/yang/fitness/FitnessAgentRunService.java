package happy.jayden.yang.fitness;

import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessExceptions.ConflictException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Routes app chat through the published Agent runtime and exposes its durable stream. */
@Service
public class FitnessAgentRunService {

  private static final String AGENT_KEY = "fitness.coach";

  private final FitnessApplicationService fitness;
  private final PublishedAgentPlaygroundRuntime runtime;
  private final JdbcRunTraceRepository traces;
  private final Executor executor;

  public FitnessAgentRunService(
      FitnessApplicationService fitness,
      PublishedAgentPlaygroundRuntime runtime,
      JdbcRunTraceRepository traces,
      @Qualifier("applicationTaskExecutor") Executor executor) {
    this.fitness = fitness;
    this.runtime = runtime;
    this.traces = traces;
    this.executor = executor;
  }

  public RunAccepted startUser(String sessionToken, String message) {
    return start(fitness.authenticateSession(sessionToken), message);
  }

  public AiSession createUserSession(String sessionToken) {
    var created = runtime.createConversation(AGENT_KEY, fitness.authenticateSession(sessionToken));
    return new AiSession(created.conversationId(), "ACTIVE", AGENT_KEY, created.createdAt());
  }

  public RunAccepted startUser(String sessionToken, UUID sessionId, String message) {
    UUID userId = fitness.authenticateSession(sessionToken);
    try {
      var started =
          runtime.startStreaming(AGENT_KEY, userId, sessionId, requireMessage(message), executor);
      return accepted(started.runId(), started.conversationId(), started.status());
    } catch (IllegalArgumentException exception) {
      if (exception.getMessage() != null && exception.getMessage().contains("会话")) {
        throw new NotFoundException("会话不存在或已结束");
      }
      throw exception;
    }
  }

  public RunAccepted startDeveloper(String message) {
    return start(fitness.developerUserId(), message);
  }

  public RunAccepted decideUser(
      String sessionToken, UUID runId, UUID approvalId, String decision, String idempotencyKey) {
    UUID userId = fitness.authenticateSession(sessionToken);
    requireOwner(runId, userId);
    return decide(runId, approvalId, userId, decision, idempotencyKey);
  }

  public RunAccepted decideDeveloper(
      UUID runId, UUID approvalId, String decision, String idempotencyKey) {
    UUID userId = traces.findRunOwner(runId).orElseThrow(() -> new NotFoundException("运行记录不存在"));
    return decide(runId, approvalId, userId, decision, idempotencyKey);
  }

  public SseEmitter streamUser(String sessionToken, UUID runId, String lastEventId) {
    requireOwner(runId, fitness.authenticateSession(sessionToken));
    return stream(runId, lastEventId, true);
  }

  public SseEmitter streamDeveloper(UUID runId, String lastEventId) {
    if (traces.findRunOwner(runId).isEmpty()) throw new NotFoundException("运行记录不存在");
    return stream(runId, lastEventId, false);
  }

  private RunAccepted start(UUID userId, String message) {
    var started = runtime.startStreaming(AGENT_KEY, userId, requireMessage(message), executor);
    return accepted(started.runId(), started.conversationId(), started.status());
  }

  private RunAccepted decide(
      UUID runId, UUID approvalId, UUID userId, String decision, String idempotencyKey) {
    try {
      var accepted = runtime.decide(runId, userId, approvalId, decision, idempotencyKey);
      return accepted(runId, accepted.conversationId(), accepted.status());
    } catch (IllegalStateException exception) {
      if (exception.getMessage().contains("not found")) throw new NotFoundException("确认请求不存在");
      throw new ConflictException("该确认请求已处理或不属于当前用户");
    } catch (IllegalArgumentException exception) {
      if (exception.getMessage().contains("Idempotency-Key"))
        throw new InvalidRequestException(exception.getMessage());
      throw new InvalidRequestException("decision 必须是 APPROVE 或 REJECT");
    }
  }

  private SseEmitter stream(UUID runId, String lastEventId, boolean userFacing) {
    long initial = parseEventId(lastEventId);
    SseEmitter emitter = new SseEmitter(130_000L);
    executor.execute(
        () -> {
          long cursor = initial;
          try {
            boolean terminal = false;
            while (!terminal) {
              var events = traces.streamEventsAfter(runId, cursor);
              for (var event : events) {
                cursor = event.sequence();
                terminal = "COMPLETED".equals(event.type());
                var projected = userFacing ? projectUserEvent(event) : Optional.of(event);
                if (projected.isEmpty()) continue;
                var outbound = projected.get();
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", Long.toString(outbound.sequence()));
                envelope.put("runId", runId.toString());
                envelope.put("type", outbound.type());
                envelope.put("occurredAt", outbound.occurredAt().toString());
                envelope.put("data", outbound.data());
                emitter.send(
                    SseEmitter.event()
                        .id(Long.toString(outbound.sequence()))
                        .name(outbound.type())
                        .data(envelope));
              }
              if (!terminal) Thread.sleep(150L);
            }
            emitter.complete();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(exception);
          } catch (Exception exception) {
            emitter.completeWithError(exception);
          }
        });
    return emitter;
  }

  static Optional<JdbcRunTraceRepository.StreamEvent> projectUserEvent(
      JdbcRunTraceRepository.StreamEvent event) {
    return switch (event.type()) {
      case "RUN_STATE" ->
          progressEvent(
              event,
              "WAITING_APPROVAL".equals(text(event.data().get("status")))
                  ? "计划已准备好，请核对是否保存"
                  : "正在理解你的需求");
      case "RUN_EVENT" -> projectRuntimeEvent(event);
      case "TEXT_DELTA" ->
          Optional.of(copy(event, "TEXT_DELTA", Map.of("delta", delta(event.data()))));
      case "APPROVAL" -> Optional.of(copy(event, "APPROVAL", approvalData(event.data())));
      case "ERROR" -> Optional.of(copy(event, "ERROR", Map.of("message", "这次处理没有成功，请稍后重试。")));
      case "COMPLETED" -> Optional.of(copy(event, "COMPLETED", Map.of()));
      default -> Optional.empty();
    };
  }

  private static Optional<JdbcRunTraceRepository.StreamEvent> projectRuntimeEvent(
      JdbcRunTraceRepository.StreamEvent event) {
    String eventType = text(event.data().get("eventType"));
    if ("BLOCK_STARTED".equals(eventType)) {
      return switch (text(event.data().get("type"))) {
        case "THINKING" -> progressEvent(event, "正在整理建议");
        case "TOOL_CALL", "TOOL_RESULT" -> progressEvent(event, "正在查看相关记录");
        default -> Optional.empty();
      };
    }
    if (eventType.startsWith("TOOL_")) return progressEvent(event, "正在查看相关记录");
    if ("MODEL_CALL_COMPLETED".equals(eventType)) return progressEvent(event, "正在整理建议");
    if ("RUN_WAITING_APPROVAL".equals(eventType) || "CONFIRMATION_REQUIRED".equals(eventType)) {
      return progressEvent(event, "计划已准备好，请核对是否保存");
    }
    if (eventType.equals("CONTEXT_ASSEMBLED")
        || eventType.equals("MEMORY_LOADED")
        || eventType.equals("SKILL_DISCOVERED")
        || eventType.equals("SKILL_LOADED")
        || eventType.equals("HOOK_STARTED")
        || eventType.equals("HOOK_COMPLETED")
        || eventType.equals("MODEL_CALL_STARTED")) {
      return progressEvent(event, "正在理解你的需求");
    }
    return Optional.empty();
  }

  private static Optional<JdbcRunTraceRepository.StreamEvent> progressEvent(
      JdbcRunTraceRepository.StreamEvent event, String summary) {
    return Optional.of(copy(event, "RUN_STATE", Map.of("summary", summary)));
  }

  private static JdbcRunTraceRepository.StreamEvent copy(
      JdbcRunTraceRepository.StreamEvent event, String type, Map<String, Object> data) {
    return new JdbcRunTraceRepository.StreamEvent(event.sequence(), type, data, event.occurredAt());
  }

  private static Map<String, Object> approvalData(Map<String, Object> source) {
    var result = new LinkedHashMap<String, Object>();
    for (String key : List.of("approvalId", "status", "title", "proposal")) {
      if (source.containsKey(key)) result.put(key, source.get(key));
    }
    return Map.copyOf(result);
  }

  private static String delta(Map<String, Object> data) {
    String delta = text(data.get("delta"));
    return delta.isEmpty() ? text(data.get("text")) : delta;
  }

  private static String text(Object value) {
    return value instanceof String text ? text : "";
  }

  private void requireOwner(UUID runId, UUID userId) {
    UUID owner = traces.findRunOwner(runId).orElseThrow(() -> new NotFoundException("运行记录不存在"));
    if (!owner.equals(userId)) throw new NotFoundException("运行记录不存在");
  }

  private static String requireMessage(String message) {
    if (message == null || message.isBlank()) throw new InvalidRequestException("message 不能为空");
    return message.trim();
  }

  private static long parseEventId(String value) {
    if (value == null || value.isBlank()) return 0;
    try {
      return Math.max(0L, Long.parseLong(value));
    } catch (NumberFormatException exception) {
      throw new InvalidRequestException("Last-Event-ID 不合法");
    }
  }

  private static RunAccepted accepted(UUID runId, UUID conversationId, String status) {
    Instant now = Instant.now();
    return new RunAccepted(
        runId,
        conversationId,
        status,
        "/api/v1/app/ai/runs/" + runId + "/events",
        List.of(),
        now,
        now);
  }

  public record RunAccepted(
      UUID runId,
      UUID sessionId,
      String status,
      String eventStreamUrl,
      List<Object> result,
      Instant createdAt,
      Instant updatedAt) {}

  public record AiSession(UUID sessionId, String status, String agentKey, Instant createdAt) {}
}
