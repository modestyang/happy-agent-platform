package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.service.auth.AdminAuthService;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Versioned developer playground endpoints for specialized Fitness and generic Agent runs. */
@RestController
@RequestMapping("/api/v1/admin/playground/runs")
public class AdminPlaygroundV1Controller {
  private final AdminAuthService auth;
  private final AdminPlaygroundRunService runs;

  public AdminPlaygroundV1Controller(AdminAuthService auth, AdminPlaygroundRunService runs) {
    this.auth = auth;
    this.runs = runs;
  }

  @PostMapping
  ResponseEntity<RunDetailResponse> create(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CreatePlaygroundRunBody request) {
    auth.authenticate(sessionToken);
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new InvalidRequestException("Idempotency-Key 必填");
    }
    if (request == null || request.agentKey() == null || request.agentKey().isBlank()) {
      throw new InvalidRequestException("agentKey 必填");
    }
    var accepted = runs.start(request.agentKey().trim(), request.input());
    var response = detail(accepted);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/admin/playground/runs/" + accepted.runId())
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(response);
  }

  @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter events(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("runId") UUID runId,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    auth.authenticate(sessionToken);
    return runs.stream(runId, lastEventId);
  }

  @PostMapping("/{runId}/approvals/{approvalId}")
  RunDetailResponse decide(
      @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("runId") UUID runId,
      @PathVariable("approvalId") UUID approvalId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ApprovalDecisionBody request) {
    auth.authenticate(sessionToken);
    return detail(
        runs.decide(
            runId, approvalId, request == null ? null : request.decision(), idempotencyKey));
  }

  private static RunDetailResponse detail(AdminPlaygroundRunService.StartedRun accepted) {
    return new RunDetailResponse(
        accepted.runId(),
        accepted.conversationId(),
        "PLAYGROUND",
        accepted.agentKey(),
        accepted.status(),
        new RunBudget(16_000, 2_000, 8, BigDecimal.ONE, 120),
        new RunUsage(0, 0, 0, BigDecimal.ZERO, 0),
        "0",
        List.of(),
        accepted.createdAt(),
        accepted.updatedAt());
  }

  record CreatePlaygroundRunBody(
      String agentKey, Map<String, Object> target, String input, UUID businessUserId) {}

  record ApprovalDecisionBody(String decision) {}

  record RunBudget(
      int maxInputTokens,
      int maxOutputTokens,
      int maxToolCalls,
      BigDecimal maxCostUsd,
      int maxRunSeconds) {}

  record RunUsage(
      int inputTokens, int outputTokens, int toolCalls, BigDecimal costUsd, long elapsedMs) {}

  record RunDetailResponse(
      UUID runId,
      UUID sessionId,
      String origin,
      String agentKey,
      String status,
      RunBudget budget,
      RunUsage usage,
      String lastEventId,
      List<Object> structuredOutput,
      Instant createdAt,
      Instant updatedAt) {}
}
