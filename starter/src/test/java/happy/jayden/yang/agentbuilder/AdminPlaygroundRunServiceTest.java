package happy.jayden.yang.agentbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.WorkspaceDtos.RunTrace;
import happy.jayden.yang.fitness.FitnessAgentRunService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPlaygroundRunServiceTest {

  @Mock private FitnessAgentRunService fitness;
  @Mock private PublishedAgentPlaygroundRuntime generic;
  @Mock private JdbcRunTraceRepository traces;

  private final Executor executor = Runnable::run;

  @Test
  void dispatchesTheFitnessAgentToItsSpecializedRuntime() {
    UUID runId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Instant now = Instant.now();
    when(fitness.startDeveloper("怎么训练"))
        .thenReturn(
            new FitnessAgentRunService.RunAccepted(
                runId, conversationId, "RUNNING", "/events", List.of(), now, now));
    var service = new AdminPlaygroundRunService(fitness, generic, traces, executor);

    var started = service.start("fitness.coach", "怎么训练");

    assertEquals("fitness.coach", started.agentKey());
    assertEquals(runId, started.runId());
    verify(generic, never()).startStreaming("fitness.coach", "怎么训练", executor);
  }

  @Test
  void dispatchesAnotherPublishedAgentToTheGenericStreamingRuntime() {
    UUID runId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Instant now = Instant.now();
    when(generic.startStreaming("baby.food", "晚饭吃什么", executor))
        .thenReturn(
            new PublishedAgentPlaygroundRuntime.StreamingRun(
                runId, conversationId, "baby.food", 2, "RUNNING", now));
    var service = new AdminPlaygroundRunService(fitness, generic, traces, executor);

    var started = service.start("baby.food", "晚饭吃什么");

    assertEquals("baby.food", started.agentKey());
    assertEquals(runId, started.runId());
    verify(fitness, never()).startDeveloper("晚饭吃什么");
  }

  @Test
  void decidesAnotherAgentsApprovalThroughTheGenericRuntime() {
    UUID runId = UUID.randomUUID();
    UUID approvalId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Instant now = Instant.now();
    when(traces.findTrace(runId))
        .thenReturn(
            java.util.Optional.of(
                new RunTrace(
                    runId,
                    "baby.food",
                    2,
                    "WAITING_APPROVAL",
                    now,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    "model",
                    "agentscope",
                    null,
                    null,
                    "input",
                    "output",
                    List.of())));
    when(traces.findRunOwner(runId)).thenReturn(java.util.Optional.of(userId));
    when(generic.decide(runId, userId, approvalId, "APPROVE", "decision-1"))
        .thenReturn(
            new PublishedAgentPlaygroundRuntime.ApprovalDecision(
                runId, conversationId, "SUCCEEDED", now, Map.of()));
    var service = new AdminPlaygroundRunService(fitness, generic, traces, executor);

    var decided = service.decide(runId, approvalId, "APPROVE", "decision-1");

    assertEquals("baby.food", decided.agentKey());
    assertEquals("SUCCEEDED", decided.status());
    verify(generic).decide(runId, userId, approvalId, "APPROVE", "decision-1");
    verify(fitness, never()).decideDeveloper(runId, approvalId, "APPROVE", "decision-1");
  }
}
