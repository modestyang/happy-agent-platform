package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime;
import happy.jayden.yang.fitness.FitnessAgentRunService;
import java.time.Instant;
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
  private final Executor executor;

  public AdminPlaygroundRunService(
      FitnessAgentRunService fitness,
      PublishedAgentPlaygroundRuntime generic,
      @Qualifier("applicationTaskExecutor") Executor executor) {
    this.fitness = Objects.requireNonNull(fitness, "fitness");
    this.generic = Objects.requireNonNull(generic, "generic");
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
    return fitness.streamDeveloper(runId, lastEventId);
  }

  public StartedRun decide(UUID runId, UUID approvalId, String decision, String idempotencyKey) {
    var accepted = fitness.decideDeveloper(runId, approvalId, decision, idempotencyKey);
    return new StartedRun(
        accepted.runId(),
        accepted.sessionId(),
        FITNESS_AGENT,
        accepted.status(),
        accepted.createdAt(),
        accepted.updatedAt());
  }

  public record StartedRun(
      UUID runId,
      UUID conversationId,
      String agentKey,
      String status,
      Instant createdAt,
      Instant updatedAt) {}
}
