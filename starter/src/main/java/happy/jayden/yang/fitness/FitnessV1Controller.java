package happy.jayden.yang.fitness;

import static happy.jayden.yang.fitness.LocalAuthController.SESSION_COOKIE;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecognitionJobRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecommendationFeedbackRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMediaUploadTicketRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.FeedbackReason;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecommendationFeedbackDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.Sentiment;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Public v1 resource root — deliberately separate from legacy local experience endpoints. */
@RestController
@RequestMapping("/api/v1/app")
public class FitnessV1Controller {
  private final FitnessApplicationService application;
  private final MediaUploadPort media;
  private final ObjectMapper mapper;
  private final FitnessAgentRunService agentRuns;

  public FitnessV1Controller(
      FitnessApplicationService application,
      MediaUploadPort media,
      ObjectMapper mapper,
      FitnessAgentRunService agentRuns) {
    this.application = application;
    this.media = media;
    this.mapper = mapper;
    this.agentRuns = agentRuns;
  }

  @PostMapping("/ai/runs")
  ResponseEntity<FitnessAgentRunService.RunAccepted> createAiRun(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateAiRunBody request) {
    if (key == null || key.isBlank()) throw new InvalidRequestException("Idempotency-Key 必填");
    var run = agentRuns.startUser(token, request == null ? null : request.text());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/app/ai/runs/" + run.runId())
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(run);
  }

  @PostMapping("/ai/sessions")
  ResponseEntity<FitnessAgentRunService.AiSession> createAiSession(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateAiSessionBody request) {
    if (key == null || key.isBlank()) throw new InvalidRequestException("Idempotency-Key 必填");
    if (request == null || request.topic() == null) {
      throw new InvalidRequestException("topic 必填");
    }
    var session = agentRuns.createUserSession(token);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/v1/app/ai/sessions/" + session.sessionId())
        .body(session);
  }

  @PostMapping("/ai/sessions/{sessionId}/messages")
  ResponseEntity<FitnessAgentRunService.RunAccepted> createAiMessage(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("sessionId") UUID sessionId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateAiRunBody request) {
    if (key == null || key.isBlank()) throw new InvalidRequestException("Idempotency-Key 必填");
    var run = agentRuns.startUser(token, sessionId, request == null ? null : request.text());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/app/ai/runs/" + run.runId())
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(run);
  }

  @GetMapping(value = "/ai/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter streamAiRun(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("runId") UUID runId,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    return agentRuns.streamUser(token, runId, lastEventId);
  }

  @PostMapping("/ai/runs/{runId}/approvals/{approvalId}")
  FitnessAgentRunService.RunAccepted decideAiRunApproval(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("runId") UUID runId,
      @PathVariable("approvalId") UUID approvalId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody ApprovalDecisionBody request) {
    return agentRuns.decideUser(
        token, runId, approvalId, request == null ? null : request.decision(), key);
  }

  record CreateAiRunBody(String text, UUID clientMessageId) {}

  record CreateAiSessionBody(String topic, String clientTimezone) {}

  record ApprovalDecisionBody(String decision) {}

  @GetMapping("/workout-plans")
  WorkoutPlanPage workoutPlans(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestParam(name = "from", required = false) java.time.LocalDate from,
      @RequestParam(name = "to", required = false) java.time.LocalDate to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "pageSize", required = false) Integer pageSize) {
    java.time.LocalDate start = from == null ? java.time.LocalDate.now() : from;
    java.time.LocalDate end = to == null ? start : to;
    if (end.isBefore(start) || end.isAfter(start.plusDays(31))) {
      throw new InvalidRequestException("训练计划查询日期范围不合法");
    }
    var items = new java.util.ArrayList<WorkoutPlanSummary>();
    for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
      var plan = application.trainingPlan(token, date);
      if (plan != null) {
        items.add(
            new WorkoutPlanSummary(
                plan.id(),
                plan.title(),
                date,
                plan.status(),
                plan.exercises().size(),
                plan.estimatedMinutes(),
                1));
      }
    }
    return new WorkoutPlanPage(List.copyOf(items), new CursorPage(false));
  }

  @GetMapping("/workout-plans/{workoutPlanId}")
  WorkoutPlanDetail workoutPlan(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("workoutPlanId") UUID workoutPlanId) {
    var plan = application.trainingPlan(token, workoutPlanId);
    var exercises = new java.util.ArrayList<WorkoutPlanExercise>();
    for (int index = 0; index < plan.exercises().size(); index++) {
      var exercise = plan.exercises().get(index);
      exercises.add(
          new WorkoutPlanExercise(
              index + 1,
              exercise.id(),
              exercise.name(),
              exercise.sets(),
              0,
              exercise.seconds(),
              0,
              exercise.steps()));
    }
    return new WorkoutPlanDetail(
        plan.id(),
        plan.title(),
        plan.scheduledFor(),
        plan.status(),
        plan.estimatedMinutes(),
        List.copyOf(exercises),
        1);
  }

  record CursorPage(boolean hasMore) {}

  record WorkoutPlanPage(List<WorkoutPlanSummary> items, CursorPage page) {}

  record WorkoutPlanSummary(
      UUID workoutPlanId,
      String title,
      java.time.LocalDate scheduledDate,
      String state,
      int exerciseCount,
      int estimatedMinutes,
      int version) {}

  record WorkoutPlanDetail(
      UUID workoutPlanId,
      String title,
      java.time.LocalDate scheduledDate,
      String state,
      int estimatedMinutes,
      List<WorkoutPlanExercise> exercises,
      int version) {}

  record WorkoutPlanExercise(
      int position,
      UUID exerciseId,
      String name,
      int sets,
      int repetitions,
      int durationSeconds,
      int restSeconds,
      List<String> voiceCues) {}

  @PostMapping("/media-upload-tickets")
  ResponseEntity<MediaUploadTicket> ticket(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateMediaUploadTicketRequest request) {
    return idempotent(
        token,
        "ticket",
        key,
        hash(request),
        MediaUploadTicket.class,
        () -> application.createMediaUploadTicket(token, request),
        ticket -> ResponseEntity.status(HttpStatus.CREATED).body(ticket));
  }

  @PutMapping("/media-uploads/{mediaId}")
  ResponseEntity<Void> upload(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("mediaId") UUID mediaId,
      @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
      @RequestBody byte[] bytes) {
    UUID user = application.authenticateSession(token);
    if (!(media instanceof MealRecognitionRuntime.LocalMediaUploadPort local)) {
      throw new DependencyNotConfiguredException();
    }
    local.upload(user, mediaId, contentType.split(";", 2)[0].trim(), bytes);
    application.completeMediaUpload(token, mediaId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/media-uploads/{mediaId}/complete")
  ResponseEntity<Void> completeUpload(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("mediaId") UUID mediaId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody java.util.Map<String, Object> body) {
    if (key == null || key.isBlank()) throw new InvalidRequestException("Idempotency-Key 必填");
    if (body.size() != 1 || !"DIRECT_UPLOAD_COMPLETED".equals(body.get("confirmation"))) {
      throw new InvalidRequestException("完成上传确认不合法");
    }
    application.completeMediaUpload(token, mediaId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/meal-recognition-jobs")
  ResponseEntity<FitnessV1Responses.Job> create(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateMealRecognitionJobRequest request) {
    return idempotent(
        token,
        "job",
        key,
        hash(request),
        FitnessV1Responses.Job.class,
        () -> FitnessV1Responses.job(application.createMealRecognitionJob(token, request)),
        job ->
            ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/v1/app/meal-recognition-jobs/" + job.jobId())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(job));
  }

  @GetMapping("/meal-recognition-jobs/{jobId}")
  FitnessV1Responses.Job job(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("jobId") UUID jobId) {
    return FitnessV1Responses.job(application.mealRecognitionJob(token, jobId));
  }

  @GetMapping("/meal-records")
  FitnessV1Responses.MealRecordPage mealRecords(
      @CookieValue(name = SESSION_COOKIE, required = false) String token) {
    return FitnessV1Responses.mealPage(application.mealRecords(token));
  }

  @GetMapping("/reports/current-goal")
  Object currentGoalReport(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
    return FitnessV1Responses.currentGoalReport(application.currentGoalReport(token));
  }

  @PostMapping("/reports/current-goal")
  ResponseEntity<Object> refreshCurrentGoalReport(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody RefreshCurrentGoalReportBody request) {
    if (request == null
        || !("USER_REFRESH".equals(request.reason()) || "RETRY_FAILED".equals(request.reason()))) {
      throw new InvalidRequestException("报告刷新原因不合法");
    }
    CurrentGoalReportRunDto report =
        application.idempotently(
            token,
            "current-goal-report-refresh",
            key,
            hash(request),
            () -> application.enqueueCurrentGoalReport(token),
            CurrentGoalReportRunDto::reportId,
            this::write,
            json -> read(json, CurrentGoalReportRunDto.class));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/app/reports/current-goal")
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(FitnessV1Responses.currentGoalReport(report));
  }

  @GetMapping("/meal-plans/daily")
  happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanDto dailyMealPlan(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestParam(name = "date", required = false) java.time.LocalDate date) {
    return application.dailyMealPlan(token, date);
  }

  @PostMapping("/meal-plans/daily/generate")
  ResponseEntity<happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanDto>
      generateDailyMealPlan(
          @CookieValue(name = SESSION_COOKIE, required = false) String token,
          @RequestHeader(name = "Idempotency-Key", required = false) String key,
          @RequestBody
              happy.jayden.yang.fitness.service.FitnessDtos.GenerateDailyMealPlanRequest request) {
    var state =
        application.idempotently(
            token,
            "daily-meal-plan-generate",
            key,
            hash(request),
            () -> application.enqueueDailyMealPlan(token, request == null ? null : request.date()),
            value -> value.run().mealPlanId(),
            this::write,
            json ->
                read(
                    json,
                    happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanStateDto.class));
    java.time.LocalDate date = request == null || request.date() == null ? null : request.date();
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/app/meal-plans/daily")
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(application.dailyMealPlan(token, date));
  }

  @PutMapping("/meal-recommendations/{recommendationId}/feedback")
  MealRecommendationFeedbackDto feedback(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("recommendationId") UUID recommendationId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody MealRecommendationFeedbackBody body) {
    return application.idempotently(
        token,
        "meal-feedback",
        key,
        hash(body),
        () ->
            application.upsertMealRecommendationFeedback(
                token,
                new CreateMealRecommendationFeedbackRequest(
                    recommendationId, body.sentiment(), body.reason(), body.note())),
        MealRecommendationFeedbackDto::recommendationId,
        this::write,
        json -> read(json, MealRecommendationFeedbackDto.class));
  }

  @PostMapping("/meal-records")
  ResponseEntity<FitnessV1Responses.MealRecord> record(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateMealRecordRequest request) {
    return idempotent(
        token,
        "record",
        key,
        hash(request),
        FitnessV1Responses.MealRecord.class,
        () -> FitnessV1Responses.meal(application.createMealRecord(token, request)),
        meal ->
            ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/v1/app/meal-records/" + meal.mealRecordId())
                .body(meal));
  }

  private <T> ResponseEntity<T> idempotent(
      String token,
      String operation,
      String key,
      String requestHash,
      Class<T> responseType,
      Supplier<T> create,
      Function<T, ResponseEntity<T>> response) {
    T value =
        application.idempotently(
            token,
            operation,
            key,
            requestHash,
            create,
            FitnessV1Controller::resourceId,
            this::write,
            json -> read(json, responseType));
    return response.apply(value);
  }

  private <T> T read(String json, Class<T> responseType) {
    try {
      return mapper.readValue(json, responseType);
    } catch (Exception exception) {
      throw new IllegalStateException("无法读取已提交的幂等响应", exception);
    }
  }

  private String write(Object response) {
    try {
      return mapper.writeValueAsString(response);
    } catch (Exception exception) {
      throw new IllegalStateException("无法保存幂等响应", exception);
    }
  }

  private static UUID resourceId(Object response) {
    if (response instanceof MediaUploadTicket ticket) {
      return ticket.mediaId();
    }
    if (response instanceof FitnessV1Responses.Job job) {
      return job.jobId();
    }
    if (response instanceof FitnessV1Responses.MealRecord meal) {
      return meal.mealRecordId();
    }
    if (response
        instanceof happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanStateDto plan) {
      return plan.run().mealPlanId();
    }
    if (response instanceof CurrentGoalReportRunDto report) {
      return report.reportId();
    }
    throw new IllegalArgumentException("不支持的幂等响应类型");
  }

  private String hash(Object body) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));
    } catch (Exception exception) {
      throw new IllegalStateException("无法计算请求摘要", exception);
    }
  }

  record MealRecommendationFeedbackBody(Sentiment sentiment, FeedbackReason reason, String note) {}

  record RefreshCurrentGoalReportBody(String reason) {}
}
