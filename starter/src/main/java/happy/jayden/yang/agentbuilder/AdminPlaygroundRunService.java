package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime;
import happy.jayden.yang.fitness.FitnessAgentRunService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Routes developer runs without coupling generic Agents to Fitness-specific orchestration. */
@Service
public class AdminPlaygroundRunService {
  private static final String FITNESS_AGENT = "fitness.coach";

  private final FitnessAgentRunService fitness;
  private final PublishedAgentPlaygroundRuntime generic;
  private final JdbcRunTraceRepository traces;
  private final Executor executor;

  public AdminPlaygroundRunService(
      FitnessAgentRunService fitness,
      PublishedAgentPlaygroundRuntime generic,
      JdbcRunTraceRepository traces,
      @Qualifier("applicationTaskExecutor") Executor executor) {
    this.fitness = Objects.requireNonNull(fitness, "fitness");
    this.generic = Objects.requireNonNull(generic, "generic");
    this.traces = Objects.requireNonNull(traces, "traces");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  public StartedRun start(String agentKey, String input) {
    if (FITNESS_AGENT.equals(agentKey)) {
      var accepted = fitness.startDeveloper(input);
      return new StartedRun(
          accepted.runId(),
          accepted.sessionId(),
          FITNESS_AGENT,
          accepted.status(),
          accepted.createdAt(),
          accepted.updatedAt());
    }
    var accepted = generic.startStreaming(agentKey, input, executor);
    return new StartedRun(
        accepted.runId(),
        accepted.conversationId(),
        accepted.agentKey(),
        accepted.status(),
        accepted.createdAt(),
        accepted.createdAt());
  }

  public SseEmitter stream(UUID runId, String lastEventId) {
    var trace = traces.findTrace(runId).orElseThrow(() -> new IllegalArgumentException("运行记录不存在"));
    return FITNESS_AGENT.equals(trace.agentKey())
        ? fitness.streamDeveloper(runId, lastEventId)
        : streamGeneric(runId, lastEventId);
  }

  public StartedRun decide(UUID runId, UUID approvalId, String decision, String idempotencyKey) {
    var trace = traces.findTrace(runId).orElseThrow(() -> new IllegalArgumentException("运行记录不存在"));
    if (FITNESS_AGENT.equals(trace.agentKey())) {
      var accepted = fitness.decideDeveloper(runId, approvalId, decision, idempotencyKey);
      return new StartedRun(
          accepted.runId(),
          accepted.sessionId(),
          FITNESS_AGENT,
          accepted.status(),
          accepted.createdAt(),
          accepted.updatedAt());
    }
    UUID userId =
        traces.findRunOwner(runId).orElseThrow(() -> new IllegalArgumentException("运行记录不存在"));
    var accepted = generic.decide(runId, userId, approvalId, decision, idempotencyKey);
    return new StartedRun(
        accepted.runId(),
        accepted.conversationId(),
        trace.agentKey(),
        accepted.status(),
        accepted.updatedAt(),
        accepted.updatedAt());
  }

  public record StartedRun(
      UUID runId,
      UUID conversationId,
      String agentKey,
      String status,
      Instant createdAt,
      Instant updatedAt) {}

  private SseEmitter streamGeneric(UUID runId, String lastEventId) {
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
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", Long.toString(event.sequence()));
                envelope.put("runId", runId.toString());
                envelope.put("type", event.type());
                envelope.put("occurredAt", event.occurredAt().toString());
                envelope.put("data", event.data());
                emitter.send(
                    SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.type())
                        .data(envelope));
                terminal = "COMPLETED".equals(event.type());
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

  private static long parseEventId(String value) {
    if (value == null || value.isBlank()) return 0;
    try {
      return Math.max(0, Long.parseLong(value.trim()));
    } catch (NumberFormatException exception) {
      return 0;
    }
  }
}
