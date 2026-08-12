package happy.jayden.yang.fitness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FitnessAgentRunServiceTest {

  @Mock private FitnessApplicationService fitness;
  @Mock private PublishedAgentPlaygroundRuntime runtime;
  @Mock private JdbcRunTraceRepository traces;

  @Test
  void startsThePublishedFitnessAgentThroughTheFrameworkRuntime() {
    UUID userId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Instant now = Instant.now();
    Executor executor = Runnable::run;
    when(fitness.authenticateSession("session-token")).thenReturn(userId);
    when(runtime.startStreaming("fitness.coach", userId, "帮我制定计划", executor))
        .thenReturn(
            new PublishedAgentPlaygroundRuntime.StreamingRun(
                runId, conversationId, "fitness.coach", 3, "RUNNING", now));
    var service = new FitnessAgentRunService(fitness, runtime, traces, executor);

    var accepted = service.startUser("session-token", "帮我制定计划");

    assertEquals(runId, accepted.runId());
    assertEquals(conversationId, accepted.sessionId());
    verify(runtime).startStreaming("fitness.coach", userId, "帮我制定计划", executor);
  }

  @Test
  void createsTheSessionOnTheBackendAndSendsMessagesThroughItsId() {
    UUID userId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Instant now = Instant.now();
    Executor executor = Runnable::run;
    when(fitness.authenticateSession("session-token")).thenReturn(userId);
    when(runtime.createConversation("fitness.coach", userId))
        .thenReturn(new PublishedAgentPlaygroundRuntime.CreatedConversation(conversationId, now));
    when(runtime.startStreaming("fitness.coach", userId, conversationId, "从头开始", executor))
        .thenReturn(
            new PublishedAgentPlaygroundRuntime.StreamingRun(
                runId, conversationId, "fitness.coach", 3, "RUNNING", now));
    var service = new FitnessAgentRunService(fitness, runtime, traces, executor);

    var session = service.createUserSession("session-token");
    var accepted = service.startUser("session-token", session.sessionId(), "从头开始");

    assertEquals(conversationId, session.sessionId());
    assertEquals(conversationId, accepted.sessionId());
    verify(runtime).startStreaming("fitness.coach", userId, conversationId, "从头开始", executor);
  }

  @Test
  void projectsInternalRuntimeEventsToFixedUserFacingProgress() {
    Instant now = Instant.now();
    var thinkingStarted =
        new JdbcRunTraceRepository.StreamEvent(
            1,
            "RUN_EVENT",
            Map.of(
                "eventType", "BLOCK_STARTED",
                "blockId", "thinking-1",
                "type", "THINKING",
                "toolKey", "fitness.exercise.catalog.search"),
            now);
    var thinkingDelta =
        new JdbcRunTraceRepository.StreamEvent(
            2,
            "RUN_EVENT",
            Map.of(
                "eventType", "BLOCK_DELTA",
                "blockId", "thinking-1",
                "delta", "I should inspect tool arguments"),
            now);
    var toolStarted =
        new JdbcRunTraceRepository.StreamEvent(
            3,
            "RUN_EVENT",
            Map.of("eventType", "TOOL_STARTED", "toolKey", "fitness.exercise.catalog.search"),
            now);

    var projectedThinking = FitnessAgentRunService.projectUserEvent(thinkingStarted).orElseThrow();
    var projectedTool = FitnessAgentRunService.projectUserEvent(toolStarted).orElseThrow();

    assertEquals("RUN_STATE", projectedThinking.type());
    assertEquals("正在整理建议", projectedThinking.data().get("summary"));
    assertFalse(projectedThinking.data().containsKey("blockId"));
    assertFalse(projectedThinking.data().containsKey("toolKey"));
    assertTrue(FitnessAgentRunService.projectUserEvent(thinkingDelta).isEmpty());
    assertEquals("正在查看相关记录", projectedTool.data().get("summary"));
    assertFalse(projectedTool.data().containsKey("toolKey"));
  }

  @Test
  void sanitizesUserFacingTextApprovalFailureAndCompletionEvents() {
    Instant now = Instant.now();
    UUID approvalId = UUID.randomUUID();
    var text =
        new JdbcRunTraceRepository.StreamEvent(
            1, "TEXT_DELTA", Map.of("eventType", "MODEL_DELTA", "text", "你的计划已准备好"), now);
    var approval =
        new JdbcRunTraceRepository.StreamEvent(
            2,
            "APPROVAL",
            Map.of(
                "approvalId", approvalId.toString(),
                "toolCallId", UUID.randomUUID().toString(),
                "status", "REQUESTED",
                "title", "保存训练计划",
                "proposal", Map.of("scope", "DAY", "days", java.util.List.of())),
            now);
    var error =
        new JdbcRunTraceRepository.StreamEvent(
            3,
            "ERROR",
            Map.of("message", "PSQLException: relation agent_runs does not exist"),
            now);
    var completed =
        new JdbcRunTraceRepository.StreamEvent(4, "COMPLETED", Map.of("status", "FAILED"), now);

    var projectedText = FitnessAgentRunService.projectUserEvent(text).orElseThrow();
    var projectedApproval = FitnessAgentRunService.projectUserEvent(approval).orElseThrow();
    var projectedError = FitnessAgentRunService.projectUserEvent(error).orElseThrow();
    var projectedCompleted = FitnessAgentRunService.projectUserEvent(completed).orElseThrow();

    assertEquals(Map.of("delta", "你的计划已准备好"), projectedText.data());
    assertEquals(approvalId.toString(), projectedApproval.data().get("approvalId"));
    assertFalse(projectedApproval.data().containsKey("toolCallId"));
    assertEquals("这次处理没有成功，请稍后重试。", projectedError.data().get("message"));
    assertEquals(Map.of(), projectedCompleted.data());
  }
}
