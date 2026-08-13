package happy.jayden.yang.fitness.service;

import happy.jayden.yang.fitness.service.FitnessDtos.AiStatusDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapData;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapDto;
import happy.jayden.yang.fitness.service.FitnessDtos.CompleteWorkoutRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateGoalRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecognitionJobRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMediaUploadTicketRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.FirstSetupRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.GoalDto;
import happy.jayden.yang.fitness.service.FitnessDtos.GoalState;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionJobDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.OnboardingDto;
import happy.jayden.yang.fitness.service.FitnessDtos.RegisterRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.ReportDto;
import happy.jayden.yang.fitness.service.FitnessDtos.ReportMetric;
import happy.jayden.yang.fitness.service.FitnessDtos.TrainingProfileDto;
import happy.jayden.yang.fitness.service.FitnessDtos.TrainingProfileInput;
import happy.jayden.yang.fitness.service.FitnessDtos.WorkoutCompletionDto;
import happy.jayden.yang.fitness.service.FitnessExceptions.IdempotencyConcurrencyException;
import happy.jayden.yang.fitness.service.FitnessExceptions.IdempotencyConflictException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException;
import happy.jayden.yang.fitness.service.FitnessExceptions.UnauthorizedException;
import happy.jayden.yang.fitness.service.FitnessPorts.AgentProviderStatus;
import happy.jayden.yang.fitness.service.FitnessPorts.DailyMealPlanGenerationPort;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import happy.jayden.yang.fitness.service.FitnessPorts.PasswordVerifier;
import happy.jayden.yang.fitness.service.FitnessPorts.TransactionRunner;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class FitnessApplicationService {

  public static final String AI_REASON = "请在 Agent 工作台配置模型 Provider";
  private static final Duration SESSION_TTL = Duration.ofDays(14);
  private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
  private final FitnessStore store;
  private final PasswordVerifier passwordVerifier;
  private final AgentProviderStatus providerStatus;
  private final MediaUploadPort mediaUploadPort;
  private final TransactionRunner transactionRunner;
  private final DailyMealPlanGenerationPort dailyMealPlanGenerationPort;
  private final FitnessPorts.CurrentGoalReportGenerationPort currentGoalReportGenerationPort;
  private final SecureRandom secureRandom = new SecureRandom();

  public FitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      MediaUploadPort mediaUploadPort) {
    this(
        store,
        passwordVerifier,
        providerStatus,
        mediaUploadPort,
        (userId, date) ->
            new FitnessDtos.DailyMealPlanGenerationResult(
                "FAILED", List.of(), "DEPENDENCY_NOT_CONFIGURED", "三餐生成运行时未配置"),
        new TransactionRunner() {
          @Override
          public <T> T inTransaction(FitnessPorts.TransactionWork<T> work) {
            return work.run();
          }
        });
  }

  public FitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      MediaUploadPort mediaUploadPort,
      TransactionRunner transactionRunner) {
    this(
        store,
        passwordVerifier,
        providerStatus,
        mediaUploadPort,
        (userId, date) ->
            new FitnessDtos.DailyMealPlanGenerationResult(
                "FAILED", List.of(), "DEPENDENCY_NOT_CONFIGURED", "三餐生成运行时未配置"),
        transactionRunner);
  }

  public FitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      MediaUploadPort mediaUploadPort,
      DailyMealPlanGenerationPort dailyMealPlanGenerationPort,
      TransactionRunner transactionRunner) {
    this(
        store,
        passwordVerifier,
        providerStatus,
        mediaUploadPort,
        dailyMealPlanGenerationPort,
        facts ->
            new FitnessDtos.CurrentGoalReportGenerationResult(
                "FAILED", null, "DEPENDENCY_NOT_CONFIGURED", "当前目标报告运行时未配置"),
        transactionRunner);
  }

  public FitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      MediaUploadPort mediaUploadPort,
      DailyMealPlanGenerationPort dailyMealPlanGenerationPort,
      FitnessPorts.CurrentGoalReportGenerationPort currentGoalReportGenerationPort,
      TransactionRunner transactionRunner) {
    this.store = store;
    this.passwordVerifier = passwordVerifier;
    this.providerStatus = providerStatus;
    this.mediaUploadPort = mediaUploadPort;
    this.dailyMealPlanGenerationPort = dailyMealPlanGenerationPort;
    this.currentGoalReportGenerationPort = currentGoalReportGenerationPort;
    this.transactionRunner = transactionRunner;
  }

  public LoginResult login(LoginRequest request) {
    if (request == null || blank(request.username()) || blank(request.password())) {
      throw new UnauthorizedException();
    }
    var account =
        store.findLoginAccount(request.username().trim()).orElseThrow(UnauthorizedException::new);
    if (!passwordVerifier.matches(request.password(), account.passwordHash())) {
      throw new UnauthorizedException();
    }
    byte[] tokenBytes = new byte[32];
    secureRandom.nextBytes(tokenBytes);
    String token = HexFormat.of().formatHex(tokenBytes);
    store.createSession(hashToken(token), account.userId(), Instant.now().plus(SESSION_TTL));
    return new LoginResult(new FitnessDtos.UserDto(account.userId(), account.nickname()), token);
  }

  public LoginResult register(RegisterRequest request) {
    if (request == null
        || blank(request.username())
        || blank(request.nickname())
        || blank(request.password())) {
      throw new InvalidRequestException("username、nickname 和 password 都不能为空");
    }
    String username = request.username().trim();
    String nickname = request.nickname().trim();
    if (store.findLoginAccount(username).isPresent()) {
      throw new InvalidRequestException("用户名已存在");
    }
    UUID userId =
        store.createLoginAccount(username, nickname, passwordVerifier.hash(request.password()));
    byte[] tokenBytes = new byte[32];
    secureRandom.nextBytes(tokenBytes);
    String token = HexFormat.of().formatHex(tokenBytes);
    store.createSession(hashToken(token), userId, Instant.now().plus(SESSION_TTL));
    return new LoginResult(new FitnessDtos.UserDto(userId, nickname), token);
  }

  public void logout(String sessionToken) {
    if (!blank(sessionToken)) {
      store.revokeSession(hashToken(sessionToken));
    }
  }

  public BootstrapDto bootstrap(String sessionToken) {
    UUID userId = authenticate(sessionToken);
    Instant now = Instant.now();
    LocalDate today = LocalDate.now(USER_ZONE);
    store.recordUserActivity(userId, now);
    BootstrapData data = store.loadBootstrap(userId, today);
    if (data.goal() != null && store.findDailyMealPlan(userId, today).isEmpty()) {
      store.enqueueDailyMealPlanGeneration(userId, today);
    }
    AiStatusDto ai =
        new AiStatusDto(
            providerStatus.configured(), providerStatus.configured() ? null : AI_REASON);
    if (data.goal() == null) {
      return new BootstrapDto(
          data.user(),
          new OnboardingDto("REQUIRED"),
          null,
          List.of(),
          List.of(),
          List.of(),
          null,
          List.of(),
          0,
          null,
          ai,
          data.trainingProfile());
    }
    BigDecimal currentWeight = currentWeight(data);
    GoalDto goal = goal(data.goal(), currentWeight);
    return new BootstrapDto(
        data.user(),
        new OnboardingDto("COMPLETE"),
        goal,
        data.bodyRecords(),
        data.meals(),
        data.mealRecommendations(),
        data.plan(),
        data.exercises(),
        data.completedWorkoutCount(),
        report(data, goal),
        ai,
        data.trainingProfile());
  }

  public UUID authenticateSession(String sessionToken) {
    return authenticate(sessionToken);
  }

  public FitnessDtos.PlanDto trainingPlan(String sessionToken, LocalDate scheduledFor) {
    LocalDate date = scheduledFor == null ? LocalDate.now(USER_ZONE) : scheduledFor;
    return store.loadBootstrap(authenticate(sessionToken), date).plan();
  }

  public FitnessDtos.PlanDto trainingPlan(String sessionToken, UUID workoutPlanId) {
    return store
        .findTrainingPlan(authenticate(sessionToken), workoutPlanId)
        .orElseThrow(() -> new NotFoundException("训练计划不存在"));
  }

  public void completeFirstSetup(String sessionToken, FirstSetupRequest request) {
    if (request == null
        || request.weightJin() == null
        || request.targetWeightJin() == null
        || request.targetDate() == null) {
      throw new InvalidRequestException("weightJin、targetWeightJin 和 targetDate 必填");
    }
    positive(request.weightJin(), "weightJin");
    positive(request.waistCm(), "waistCm");
    positive(request.targetWeightJin(), "targetWeightJin");
    store.completeFirstSetup(
        authenticate(sessionToken),
        new FirstSetupRequest(
            request.weightJin(),
            request.waistCm(),
            request.targetWeightJin(),
            request.targetDate(),
            validateTrainingProfile(request.trainingProfile())));
  }

  public TrainingProfileDto updateTrainingProfile(
      String sessionToken, TrainingProfileInput request) {
    return store.updateTrainingProfile(
        authenticate(sessionToken), validateTrainingProfile(request));
  }

  public java.util.Optional<FitnessDtos.IdempotencyEntry> idempotency(
      UUID userId, String operation, String key) {
    return store.findIdempotency(userId, operation, key);
  }

  public void saveIdempotency(
      UUID userId,
      String operation,
      String key,
      String hash,
      UUID resourceId,
      String responseJson) {
    store.saveIdempotency(userId, operation, key, hash, resourceId, responseJson);
  }

  /** Persists a write and its replay representation in one use-case transaction. */
  public <T> T idempotently(
      String sessionToken,
      String operation,
      String key,
      String requestHash,
      Supplier<T> create,
      Function<T, UUID> resourceId,
      Function<T, String> serialize,
      Function<String, T> deserialize) {
    UUID userId = authenticate(sessionToken);
    if (blank(key)) throw new InvalidRequestException("Idempotency-Key 必填");
    T replay = replay(userId, operation, key, requestHash, deserialize);
    if (replay != null) return replay;
    try {
      return transactionRunner.inTransaction(
          () -> {
            T inTransactionReplay = replay(userId, operation, key, requestHash, deserialize);
            if (inTransactionReplay != null) return inTransactionReplay;
            T created = create.get();
            store.saveIdempotency(
                userId,
                operation,
                key,
                requestHash,
                resourceId.apply(created),
                serialize.apply(created));
            return created;
          });
    } catch (IdempotencyConcurrencyException exception) {
      T winner = replay(userId, operation, key, requestHash, deserialize);
      if (winner == null) {
        throw new IdempotencyConflictException("Idempotency-Key 并发请求未能找到已提交结果");
      }
      return winner;
    }
  }

  private <T> T replay(
      UUID userId,
      String operation,
      String key,
      String requestHash,
      Function<String, T> deserialize) {
    var entry = store.findIdempotency(userId, operation, key);
    if (entry.isEmpty()) return null;
    if (!entry.get().requestHash().equals(requestHash)) {
      throw new IdempotencyConflictException("Idempotency-Key 已用于不同请求");
    }
    return deserialize.apply(entry.get().responseJson());
  }

  public BodyRecordDto createBodyRecord(String sessionToken, CreateBodyRecordRequest request) {
    if (request == null || (request.weightJin() == null && request.waistCm() == null)) {
      throw new InvalidRequestException("weightJin 和 waistCm 至少填写一个");
    }
    positive(request.weightJin(), "weightJin");
    positive(request.waistCm(), "waistCm");
    rejectFuture(request.recordedAt(), "recordedAt");
    return store.createBodyRecord(authenticate(sessionToken), request);
  }

  public MealDto createMeal(String sessionToken, CreateMealRequest request) {
    if (request == null
        || request.mealType() == null
        || request.items() == null
        || request.items().isEmpty()) {
      throw new InvalidRequestException("mealType 和 items 必填");
    }
    if (request.items().stream().anyMatch(item -> blank(item.name()) || item.estimatedKcal() < 0)) {
      throw new InvalidRequestException("餐食名称不能为空且 estimatedKcal 不能为负数");
    }
    rejectFuture(request.occurredAt(), "occurredAt");
    return store.createMeal(authenticate(sessionToken), request);
  }

  public MediaUploadTicket createMediaUploadTicket(
      String sessionToken, CreateMediaUploadTicketRequest request) {
    if (request == null
        || !"MEAL_RECOGNITION".equals(request.purpose())
        || blank(request.contentType())
        || request.contentLength() <= 0
        || blank(request.sha256())) {
      throw new InvalidRequestException("purpose、contentType、contentLength 和 sha256 必填");
    }
    if (!List.of("image/jpeg", "image/png", "image/webp").contains(request.contentType())
        || request.contentLength() > 10_485_760
        || !request.sha256().matches("[a-f0-9]{64}")) {
      throw new InvalidRequestException("图片格式、大小或 SHA-256 不合法");
    }
    return mediaUploadPort.createTicket(
        authenticate(sessionToken),
        request.contentType(),
        request.contentLength(),
        request.sha256());
  }

  public void markMediaUploaded(String sessionToken, UUID mediaId) {
    store.markMediaUploaded(authenticate(sessionToken), mediaId);
  }

  /** Marks media available only after its storage adapter has verified the direct upload. */
  public void completeMediaUpload(String sessionToken, UUID mediaId) {
    UUID userId = authenticate(sessionToken);
    mediaUploadPort.verifyUploaded(userId, mediaId);
    store.markMediaUploaded(userId, mediaId);
  }

  public MealRecognitionJobDto createMealRecognitionJob(
      String sessionToken, CreateMealRecognitionJobRequest request) {
    if (request == null || request.mediaId() == null || request.mealType() == null) {
      throw new InvalidRequestException("mediaId 和 mealType 必填");
    }
    rejectFuture(request.occurredAt(), "occurredAt");
    UUID userId = authenticate(sessionToken);
    return store.createRecognitionJob(
        userId, request.mediaId(), request.mealType(), request.occurredAt());
  }

  public MealRecognitionJobDto mealRecognitionJob(String sessionToken, UUID jobId) {
    return store
        .findRecognitionJob(authenticate(sessionToken), jobId)
        .orElseThrow(
            () ->
                new happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException(
                    "识别任务不存在"));
  }

  public MealDto createMealRecord(String sessionToken, CreateMealRecordRequest request) {
    if (request == null
        || request.mealType() == null
        || request.items() == null
        || request.items().isEmpty()) throw new InvalidRequestException("mealType 和 items 必填");
    if (!"MANUAL".equals(request.source()) && !"RECOGNITION_CONFIRMED".equals(request.source()))
      throw new InvalidRequestException("source 不合法");
    UUID userId = authenticate(sessionToken);
    if ("RECOGNITION_CONFIRMED".equals(request.source())) {
      if (request.recognitionJobId() == null)
        throw new InvalidRequestException("RECOGNITION_CONFIRMED 必须带 recognitionJobId");
      MealRecognitionJobDto job =
          store
              .findRecognitionJob(userId, request.recognitionJobId())
              .orElseThrow(
                  () ->
                      new happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException(
                          "识别任务不存在"));
      if (!"SUCCEEDED".equals(job.status())) throw new InvalidRequestException("识别任务尚未成功");
    }
    if (request.items().stream().anyMatch(item -> blank(item.name()) || item.estimatedKcal() < 0))
      throw new InvalidRequestException("餐食名称不能为空且 estimatedKcal 不能为负数");
    rejectFuture(request.occurredAt(), "occurredAt");
    return store.createMealRecord(userId, request);
  }

  public List<MealDto> mealRecords(String sessionToken) {
    return store.listMealRecords(authenticate(sessionToken));
  }

  public FitnessDtos.MealRecommendationFeedbackDto upsertMealRecommendationFeedback(
      String sessionToken, FitnessDtos.CreateMealRecommendationFeedbackRequest request) {
    if (request == null || request.recommendationId() == null || request.sentiment() == null) {
      throw new InvalidRequestException("recommendationId 和 sentiment 必填");
    }
    if (request.sentiment() == FitnessDtos.Sentiment.LIKE
        && (request.reason() != null || request.note() != null)) {
      throw new InvalidRequestException("赞仅允许 sentiment 字段");
    }
    if (request.sentiment() == FitnessDtos.Sentiment.DISLIKE && request.reason() == null) {
      throw new InvalidRequestException("点踩必须选择原因");
    }
    if (request.note() != null && request.note().codePointCount(0, request.note().length()) > 300) {
      throw new InvalidRequestException("说明不能超过 300 个字符");
    }
    if (request.reason() == FitnessDtos.FeedbackReason.OTHER
        && !FeedbackNotePolicy.hasNonWhitespaceCodePoint(request.note())) {
      throw new InvalidRequestException("OTHER 说明必须为 1 到 300 个字符");
    }
    return store.upsertMealRecommendationFeedback(authenticate(sessionToken), request);
  }

  /** Reads only a durable plan state; it never fabricates an answer when no run exists. */
  public FitnessDtos.DailyMealPlanDto dailyMealPlan(String sessionToken, LocalDate requestedDate) {
    UUID userId = authenticate(sessionToken);
    LocalDate date = requestedDate == null ? LocalDate.now(USER_ZONE) : requestedDate;
    return dailyMealPlan(
        store
            .findDailyMealPlan(userId, date)
            .orElseThrow(
                () ->
                    new happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException(
                        "当日三餐计划尚未生成")));
  }

  /**
   * Persists an asynchronous request. HTTP and the 05:30 scheduler never invoke the model; only the
   * leased worker below can do so.
   */
  public FitnessDtos.DailyMealPlanStateDto enqueueDailyMealPlan(
      String sessionToken, LocalDate requestedDate) {
    UUID userId = authenticate(sessionToken);
    LocalDate date = requestedDate == null ? LocalDate.now(USER_ZONE) : requestedDate;
    return enqueueDailyMealPlan(userId, date);
  }

  /** Invoked by the 05:30 local scheduler; it only makes durable work visible to workers. */
  public void enqueueScheduledDailyMealPlans() {
    LocalDate today = LocalDate.now(USER_ZONE);
    Instant activeSince = Instant.now().minus(Duration.ofDays(14));
    for (UUID userId : store.dailyMealPlanEligibleUserIds(activeSince, today)) {
      store.enqueueDailyMealPlanGeneration(userId, today);
    }
  }

  private FitnessDtos.DailyMealPlanStateDto enqueueDailyMealPlan(UUID userId, LocalDate date) {
    var existing = store.findDailyMealPlan(userId, date);
    if (existing.isPresent()
        && ("GENERATING".equals(existing.get().run().status())
            || ("READY".equals(existing.get().run().status())
                && usesChineseUserFacingCopy(existing.get())))) {
      return existing.get();
    }
    store.enqueueDailyMealPlanGeneration(userId, date);
    return store
        .findDailyMealPlan(userId, date)
        .orElseThrow(() -> new IllegalStateException("三餐生成状态未持久化"));
  }

  private static boolean usesChineseUserFacingCopy(FitnessDtos.DailyMealPlanStateDto plan) {
    return plan.recommendations().size() == 3
        && plan.recommendations().stream()
            .allMatch(
                recommendation ->
                    containsHan(recommendation.reason())
                        && recommendation.items().stream()
                            .allMatch(item -> containsHan(item.name())));
  }

  private static boolean containsHan(String value) {
    return value != null
        && value
            .codePoints()
            .anyMatch(
                codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
  }

  /** Executes at most one durable lease. Returns false if no pending/stale run was claimable. */
  public boolean runNextDailyMealPlanGeneration() {
    var claim = store.claimNextDailyMealPlanGeneration();
    if (claim.isEmpty()) return false;
    runClaimedDailyMealPlan(claim.get());
    return true;
  }

  private void runClaimedDailyMealPlan(FitnessDtos.ClaimedDailyMealPlanRunDto claim) {
    FitnessDtos.DailyMealPlanRunDto run = claim.run();
    FitnessDtos.DailyMealPlanGenerationResult result;
    try {
      result = dailyMealPlanGenerationPort.generate(run.userId(), run.date());
    } catch (RuntimeException exception) {
      result =
          new FitnessDtos.DailyMealPlanGenerationResult(
              "FAILED", List.of(), "RUNTIME_ERROR", "三餐生成运行时发生未处理错误");
    }
    final FitnessDtos.DailyMealPlanGenerationResult generationResult = result;
    String invalid = invalidGeneration(generationResult);
    if (invalid != null) {
      transactionRunner.inTransaction(
          () -> {
            store.failDailyMealPlanGeneration(claim, "INVALID_MODEL_RESPONSE", invalid);
            return null;
          });
    } else if ("SUCCEEDED".equals(generationResult.status())) {
      transactionRunner.inTransaction(
          () -> {
            store.completeDailyMealPlanGeneration(claim, generationResult);
            return null;
          });
    } else {
      transactionRunner.inTransaction(
          () -> {
            store.failDailyMealPlanGeneration(
                claim,
                blank(generationResult.failureCode())
                    ? "RUNTIME_ERROR"
                    : generationResult.failureCode(),
                blank(generationResult.failureMessage())
                    ? "三餐生成未完成"
                    : generationResult.failureMessage());
            return null;
          });
    }
  }

  private static String invalidGeneration(FitnessDtos.DailyMealPlanGenerationResult result) {
    if (result == null) return "三餐运行时未返回结果";
    if (!"SUCCEEDED".equals(result.status())) return null;
    if (result.recommendations() == null || result.recommendations().size() != 3) {
      return "三餐生成结果必须包含早餐、午餐和晚餐";
    }
    java.util.Set<FitnessDtos.MealType> types =
        java.util.EnumSet.noneOf(FitnessDtos.MealType.class);
    for (FitnessDtos.GeneratedMealRecommendation recommendation : result.recommendations()) {
      if (recommendation == null
          || recommendation.mealType() == null
          || !types.add(recommendation.mealType())
          || !List.of(
                  FitnessDtos.MealType.BREAKFAST,
                  FitnessDtos.MealType.LUNCH,
                  FitnessDtos.MealType.DINNER)
              .contains(recommendation.mealType())
          || recommendation.items() == null
          || recommendation.items().isEmpty()
          || blank(recommendation.reason())
          || recommendation.items().stream()
              .anyMatch(
                  item ->
                      item == null
                          || blank(item.name())
                          || item.name().codePointCount(0, item.name().length()) > 120
                          || item.estimatedKcal() < 0
                          || item.estimatedKcal() > 20_000)) {
        return "三餐生成结果不符合餐食约束";
      }
    }
    return null;
  }

  private static FitnessDtos.DailyMealPlanDto dailyMealPlan(
      FitnessDtos.DailyMealPlanStateDto state) {
    FitnessDtos.DailyMealPlanRunDto run = state.run();
    if ("GENERATING".equals(run.status())) {
      return new FitnessDtos.GeneratingDailyMealPlanDto(
          run.mealPlanId(), run.date(), USER_ZONE.getId(), "05:30:00", "GENERATING", run.version());
    }
    if ("FAILED".equals(run.status())) {
      String rawCode = blank(run.failureCode()) ? "RUNTIME_ERROR" : run.failureCode();
      String code = "DEPENDENCY_NOT_CONFIGURED".equals(rawCode) ? rawCode : "TASK_FAILED";
      return new FitnessDtos.FailedDailyMealPlanDto(
          run.mealPlanId(),
          run.date(),
          USER_ZONE.getId(),
          "05:30:00",
          "FAILED",
          new FitnessDtos.DailyMealPlanFailureDto(
              code,
              blank(run.failureMessage()) ? "三餐生成失败" : run.failureMessage(),
              !"INVALID_MODEL_RESPONSE".equals(rawCode)),
          run.version());
    }
    Map<FitnessDtos.MealType, FitnessDtos.MealRecommendationDto> byType =
        state.recommendations().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    FitnessDtos.MealRecommendationDto::mealType, item -> item));
    FitnessDtos.DailyMealPlanSectionDto breakfast =
        section(byType.get(FitnessDtos.MealType.BREAKFAST));
    FitnessDtos.DailyMealPlanSectionDto lunch = section(byType.get(FitnessDtos.MealType.LUNCH));
    FitnessDtos.DailyMealPlanSectionDto dinner = section(byType.get(FitnessDtos.MealType.DINNER));
    if (breakfast == null || lunch == null || dinner == null) {
      throw new IllegalStateException("READY 三餐计划缺少持久化餐次");
    }
    FitnessDtos.NutritionDto nutrition =
        nutrition(
            breakfast
                .nutrition()
                .caloriesKcal()
                .add(lunch.nutrition().caloriesKcal())
                .add(dinner.nutrition().caloriesKcal()));
    return new FitnessDtos.ReadyDailyMealPlanDto(
        run.mealPlanId(),
        run.date(),
        USER_ZONE.getId(),
        "05:30:00",
        "READY",
        breakfast,
        lunch,
        dinner,
        nutrition,
        run.version());
  }

  private static FitnessDtos.DailyMealPlanSectionDto section(
      FitnessDtos.MealRecommendationDto recommendation) {
    if (recommendation == null) return null;
    BigDecimal calories =
        recommendation.items().stream()
            .map(FitnessDtos.MealItemDto::estimatedKcal)
            .map(BigDecimal::valueOf)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    FitnessDtos.NutritionDto nutrition = nutrition(calories);
    return new FitnessDtos.DailyMealPlanSectionDto(
        recommendation.mealType(),
        mealTitle(recommendation.mealType()),
        recommendation.items().stream()
            .map(
                item ->
                    new FitnessDtos.DailyMealPlanFoodItem(
                        item.name(),
                        BigDecimal.ONE,
                        "份",
                        nutrition(BigDecimal.valueOf(item.estimatedKcal()))))
            .toList(),
        nutrition);
  }

  private static String mealTitle(FitnessDtos.MealType mealType) {
    return switch (mealType) {
      case BREAKFAST -> "早餐建议";
      case LUNCH -> "午餐建议";
      case DINNER -> "晚餐建议";
      case SNACK -> throw new IllegalArgumentException("日计划不包含加餐");
    };
  }

  private static FitnessDtos.NutritionDto nutrition(BigDecimal calories) {
    return new FitnessDtos.NutritionDto(
        calories, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /** Reads the one cumulative report for the active goal; a missing active goal is a real 404. */
  public FitnessDtos.CurrentGoalReportRunDto currentGoalReport(String sessionToken) {
    return store
        .findCurrentGoalReport(authenticate(sessionToken))
        .orElseThrow(
            () ->
                new happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException(
                    "当前目标不存在或报告尚未入队"));
  }

  /**
   * HTTP only persists QUEUED work. The model is exclusively invoked by the leased worker below.
   */
  public FitnessDtos.CurrentGoalReportRunDto enqueueCurrentGoalReport(String sessionToken) {
    return store.enqueueCurrentGoalReport(authenticate(sessionToken));
  }

  /** Executes no more than one fenced report lease. */
  public boolean runNextCurrentGoalReportGeneration() {
    var claim = store.claimNextCurrentGoalReportGeneration();
    if (claim.isEmpty()) return false;
    runClaimedCurrentGoalReport(claim.get());
    return true;
  }

  private void runClaimedCurrentGoalReport(FitnessDtos.ClaimedCurrentGoalReportRunDto claim) {
    FitnessDtos.CurrentGoalReportRunDto run = claim.run();
    FitnessDtos.CurrentGoalReportFacts facts;
    FitnessDtos.CurrentGoalReportSourceData source;
    try {
      source = store.loadCurrentGoalReportSource(run.userId(), run.goalId());
      facts = currentGoalReportFacts(source);
    } catch (RuntimeException exception) {
      transactionRunner.inTransaction(
          () -> {
            store.failCurrentGoalReportGeneration(claim, "INTERNAL_ERROR", "报告客观数据计算失败");
            return null;
          });
      return;
    }
    FitnessDtos.CurrentGoalReportGenerationResult result;
    try {
      result = currentGoalReportGenerationPort.generate(facts);
    } catch (RuntimeException exception) {
      result =
          new FitnessDtos.CurrentGoalReportGenerationResult(
              "FAILED", null, "INTERNAL_ERROR", "报告生成运行时发生未处理错误");
    }
    String invalid = invalidCurrentGoalNarrative(result);
    Instant computedThrough = source.observedThrough();
    if (invalid != null) {
      transactionRunner.inTransaction(
          () -> {
            store.failCurrentGoalReportGeneration(claim, "TASK_FAILED", invalid);
            return null;
          });
      return;
    }
    if ("SUCCEEDED".equals(result.status())) {
      FitnessDtos.CurrentGoalReportGenerationResult completed = result;
      transactionRunner.inTransaction(
          () -> {
            store.completeCurrentGoalReportGeneration(claim, facts, completed, computedThrough);
            return null;
          });
      return;
    }
    FitnessDtos.CurrentGoalReportGenerationResult failed = result;
    transactionRunner.inTransaction(
        () -> {
          store.failCurrentGoalReportGeneration(
              claim,
              reportFailureCode(failed == null ? null : failed.failureCode()),
              blank(failed == null ? null : failed.failureMessage())
                  ? "当前目标报告未生成"
                  : failed.failureMessage());
          return null;
        });
  }

  private static String reportFailureCode(String value) {
    return "DEPENDENCY_NOT_CONFIGURED".equals(value) ? value : "TASK_FAILED";
  }

  private static String invalidCurrentGoalNarrative(
      FitnessDtos.CurrentGoalReportGenerationResult result) {
    if (result == null) return "报告运行时未返回结果";
    if (!"SUCCEEDED".equals(result.status())) return null;
    FitnessDtos.CurrentGoalReportNarrative narrative = result.narrative();
    if (narrative == null
        || narrative.conclusion() == null
        || blank(narrative.conclusion().summary())
        || narrative.conclusion().score() < 0
        || narrative.conclusion().score() > 100
        || !List.of("A", "B", "C", "D").contains(narrative.conclusion().grade())
        || narrative.highlights() == null
        || narrative.highlights().size() != 2
        || narrative.weaknesses() == null
        || narrative.weaknesses().isEmpty()
        || narrative.weaknesses().size() > 2
        || narrative.nextActions() == null
        || narrative.nextActions().isEmpty()
        || narrative.nextActions().size() > 3) {
      return "报告模型返回不符合结构化约束";
    }
    List<String> text = new ArrayList<>();
    text.add(narrative.conclusion().summary());
    text.addAll(narrative.highlights());
    text.addAll(narrative.weaknesses());
    for (FitnessDtos.CurrentGoalReportNextAction action : narrative.nextActions()) {
      if (action == null
          || blank(action.title())
          || blank(action.rationale())
          || !List.of("GENERATE_PLAN", "OPEN_RECORD", "NONE").contains(action.action())) {
        return "报告模型返回不符合结构化约束";
      }
      text.add(action.title());
      text.add(action.rationale());
    }
    return text.stream()
            .anyMatch(value -> blank(value) || value.contains("<") || value.contains(">"))
        ? "报告模型不得生成 HTML"
        : null;
  }

  /** Computes report facts from timestamp-windowed objective data; no record has a goal_id. */
  public static FitnessDtos.CurrentGoalReportFacts currentGoalReportFacts(
      FitnessDtos.CurrentGoalReportSourceData source) {
    FitnessDtos.GoalState goal = source.goal();
    LocalDate end = source.observedThrough().atZone(USER_ZONE).toLocalDate();
    LocalDate start = goal.startedAt().atZone(USER_ZONE).toLocalDate();
    LocalDate currentWeek = end.with(DayOfWeek.MONDAY);
    LocalDate firstWeek = start.with(DayOfWeek.MONDAY);
    if (firstWeek.isAfter(currentWeek.minusWeeks(3))) firstWeek = currentWeek.minusWeeks(3);
    List<LocalDate> weeks = new ArrayList<>();
    for (LocalDate week = firstWeek; !week.isAfter(currentWeek); week = week.plusWeeks(1)) {
      weeks.add(week);
    }

    List<FitnessDtos.BodyRecordDto> weights =
        source.bodyRecords().stream()
            .filter(record -> record.weightJin() != null)
            .sorted(Comparator.comparing(FitnessDtos.BodyRecordDto::recordedAt))
            .toList();
    HashMap<LocalDate, BigDecimal> latestWeightByWeek = new HashMap<>();
    for (FitnessDtos.BodyRecordDto record : weights) {
      latestWeightByWeek.put(
          record.recordedAt().atZone(USER_ZONE).toLocalDate().with(DayOfWeek.MONDAY),
          record.weightJin());
    }
    List<FitnessDtos.CurrentGoalWeightTrendPoint> weightTrend =
        weeks.stream()
            .map(
                week ->
                    new FitnessDtos.CurrentGoalWeightTrendPoint(week, latestWeightByWeek.get(week)))
            .toList();

    HashMap<LocalDate, int[]> workoutByWeek = new HashMap<>();
    HashMap<String, Integer> areas = new HashMap<>();
    int cardioMinutes = 0;
    int strengthMinutes = 0;
    for (FitnessDtos.CurrentGoalWorkoutRecord workout : source.workouts()) {
      LocalDate week = workout.completedAt().atZone(USER_ZONE).toLocalDate().with(DayOfWeek.MONDAY);
      int[] aggregate = workoutByWeek.computeIfAbsent(week, ignored -> new int[2]);
      aggregate[0] += workout.minutes();
      aggregate[1]++;
      boolean cardio =
          (workout.title() + " " + String.join(" ", workout.targetAreas()))
              .matches(".*(有氧|心肺|跑|骑行|HIIT).*");
      if (cardio) cardioMinutes += workout.minutes();
      else strengthMinutes += workout.minutes();
      workout.targetAreas().forEach(area -> areas.merge(area, 1, Integer::sum));
    }
    List<FitnessDtos.CurrentGoalTrainingVolumePoint> trainingVolume =
        weeks.stream()
            .map(
                week -> {
                  int[] aggregate = workoutByWeek.getOrDefault(week, new int[2]);
                  return new FitnessDtos.CurrentGoalTrainingVolumePoint(
                      week, aggregate[0], aggregate[1]);
                })
            .toList();
    int totalAreas = areas.values().stream().mapToInt(Integer::intValue).sum();
    List<FitnessDtos.CurrentGoalTrainingStructureItem> structure =
        areas.entrySet().stream()
            .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
            .map(
                entry ->
                    new FitnessDtos.CurrentGoalTrainingStructureItem(
                        entry.getKey(), percent(entry.getValue(), totalAreas)))
            .toList();
    int totalMinutes = cardioMinutes + strengthMinutes;
    BigDecimal cardioPercent = percent(cardioMinutes, totalMinutes);
    BigDecimal strengthPercent = percent(strengthMinutes, totalMinutes);
    BigDecimal currentWeight =
        weights.isEmpty() ? goal.startWeightJin() : weights.get(weights.size() - 1).weightJin();
    BigDecimal previousWeight =
        weights.size() < 2 ? null : weights.get(weights.size() - 2).weightJin();
    BigDecimal progress = goalProgress(goal, currentWeight);
    int workoutCount = source.workouts().size();
    LocalDate monthStart = end.withDayOfMonth(1);
    List<FitnessDtos.CurrentGoalWorkoutRecord> currentMonthWorkouts =
        source.workouts().stream()
            .filter(
                workout -> {
                  LocalDate date = workout.completedAt().atZone(USER_ZONE).toLocalDate();
                  return !date.isBefore(monthStart) && !date.isAfter(end);
                })
            .toList();
    int currentMonthWorkoutMinutes =
        currentMonthWorkouts.stream().mapToInt(FitnessDtos.CurrentGoalWorkoutRecord::minutes).sum();
    BigDecimal calories =
        source.meals().stream()
            .flatMap(meal -> meal.items().stream())
            .map(item -> BigDecimal.valueOf(item.estimatedKcal()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    List<FitnessDtos.CurrentGoalReportMetric> metrics =
        List.of(
            new FitnessDtos.CurrentGoalReportMetric(
                "GOAL_PROGRESS", "目标进度", progress, "%", null, "NOT_AVAILABLE"),
            new FitnessDtos.CurrentGoalReportMetric(
                "WEIGHT",
                "当前体重",
                currentWeight,
                "斤",
                previousWeight == null ? null : currentWeight.subtract(previousWeight),
                direction(currentWeight, previousWeight, true)),
            new FitnessDtos.CurrentGoalReportMetric(
                "WORKOUT_COUNT",
                "完成训练",
                BigDecimal.valueOf(workoutCount),
                "次",
                null,
                workoutCount == 0 ? "NOT_AVAILABLE" : "UP"),
            new FitnessDtos.CurrentGoalReportMetric(
                "WORKOUT_MINUTES",
                "训练时长",
                BigDecimal.valueOf(totalMinutes),
                "分钟",
                null,
                totalMinutes == 0 ? "NOT_AVAILABLE" : "UP"),
            new FitnessDtos.CurrentGoalReportMetric(
                "CURRENT_MONTH_WORKOUT_COUNT",
                "本月训练",
                BigDecimal.valueOf(currentMonthWorkouts.size()),
                "次",
                null,
                currentMonthWorkouts.isEmpty() ? "NOT_AVAILABLE" : "UP"),
            new FitnessDtos.CurrentGoalReportMetric(
                "CURRENT_MONTH_WORKOUT_MINUTES",
                "本月时长",
                BigDecimal.valueOf(currentMonthWorkoutMinutes),
                "分钟",
                null,
                currentMonthWorkoutMinutes == 0 ? "NOT_AVAILABLE" : "UP"),
            new FitnessDtos.CurrentGoalReportMetric(
                "BODY_RECORD_COUNT",
                "身体记录",
                BigDecimal.valueOf(source.bodyRecords().size()),
                "次",
                null,
                source.bodyRecords().isEmpty() ? "NOT_AVAILABLE" : "STABLE"),
            new FitnessDtos.CurrentGoalReportMetric(
                "MEAL_RECORD_COUNT",
                "饮食记录",
                BigDecimal.valueOf(source.meals().size()),
                "餐",
                null,
                source.meals().isEmpty() ? "NOT_AVAILABLE" : "STABLE"),
            new FitnessDtos.CurrentGoalReportMetric(
                "CALORIES",
                "饮食记录热量",
                calories,
                "kcal",
                null,
                calories.signum() == 0 ? "NOT_AVAILABLE" : "STABLE"));
    return new FitnessDtos.CurrentGoalReportFacts(
        goal.name(),
        start,
        end,
        metrics,
        weightTrend,
        trainingVolume,
        structure,
        cardioPercent,
        strengthPercent);
  }

  private static BigDecimal goalProgress(FitnessDtos.GoalState goal, BigDecimal currentWeight) {
    BigDecimal denominator = goal.startWeightJin().subtract(goal.targetWeightJin());
    if (denominator.signum() == 0) return BigDecimal.ZERO;
    BigDecimal value =
        goal.startWeightJin()
            .subtract(currentWeight)
            .multiply(BigDecimal.valueOf(100))
            .divide(denominator, 0, RoundingMode.HALF_UP);
    return value.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
  }

  private static BigDecimal percent(int value, int total) {
    return total == 0
        ? BigDecimal.ZERO
        : BigDecimal.valueOf(value)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP);
  }

  private static String direction(BigDecimal current, BigDecimal previous, boolean lowerIsBetter) {
    if (previous == null) return "NOT_AVAILABLE";
    int compare = current.compareTo(previous);
    if (compare == 0) return "STABLE";
    if (lowerIsBetter) return compare < 0 ? "DOWN" : "UP";
    return compare > 0 ? "UP" : "DOWN";
  }

  public WorkoutCompletionDto completeWorkout(
      String sessionToken, UUID workoutId, CompleteWorkoutRequest request) {
    if (request == null
        || request.completionRatio() == null
        || request.completionRatio().compareTo(BigDecimal.ZERO) < 0
        || request.completionRatio().compareTo(BigDecimal.ONE) > 0) {
      throw new InvalidRequestException("completionRatio 必须在 0 到 1 之间");
    }
    return store.completeWorkout(authenticate(sessionToken), workoutId, request);
  }

  public FitnessDtos.SavedTrainingPlanResult saveTrainingPlan(
      UUID userId, FitnessDtos.SaveTrainingPlanRequest request) {
    if (userId == null
        || request == null
        || request.approvalId() == null
        || request.days() == null) {
      throw new InvalidRequestException("训练计划保存参数不完整");
    }
    if (request.days().isEmpty() || request.days().size() > 31) {
      throw new InvalidRequestException("训练计划必须包含 1 到 31 个训练日");
    }
    var days =
        request.days().stream()
            .sorted(
                Comparator.comparing(
                    FitnessDtos.TrainingPlanDayInput::scheduledFor,
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    LocalDate today = LocalDate.now(USER_ZONE);
    var availableExercises =
        store.loadForAi(userId).exercises().stream()
            .map(FitnessDtos.ExerciseDto::id)
            .collect(java.util.stream.Collectors.toSet());
    var scheduledDates = new java.util.HashSet<LocalDate>();
    for (var day : days) {
      if (day == null
          || day.scheduledFor() == null
          || day.scheduledFor().isBefore(today)
          || day.scheduledFor().isAfter(today.plusYears(1))
          || blank(day.title())
          || day.title().trim().length() > 160
          || day.estimatedMinutes() < 1
          || day.estimatedMinutes() > 240
          || day.exerciseIds() == null
          || day.exerciseIds().isEmpty()
          || day.exerciseIds().size() > 12
          || day.exerciseIds().stream().distinct().count() != day.exerciseIds().size()
          || !availableExercises.containsAll(day.exerciseIds())
          || !scheduledDates.add(day.scheduledFor())) {
        throw new InvalidRequestException("训练计划日期、时长或动作不合法");
      }
    }
    var normalizedRequest = new FitnessDtos.SaveTrainingPlanRequest(request.approvalId(), days);
    return transactionRunner.inTransaction(
        () ->
            new FitnessDtos.SavedTrainingPlanResult(
                store.saveTrainingPlan(userId, normalizedRequest)));
  }

  public GoalDto createGoal(String sessionToken, CreateGoalRequest request) {
    UUID userId = authenticate(sessionToken);
    if (request == null || blank(request.name()) || request.targetWeightJin() == null) {
      throw new InvalidRequestException("name 和 targetWeightJin 必填");
    }
    positive(request.targetWeightJin(), "targetWeightJin");
    GoalState created = store.createGoal(userId, request);
    BigDecimal current = currentWeight(store.loadBootstrap(userId, LocalDate.now(USER_ZONE)));
    return goal(created, current);
  }

  public UUID developerUserId() {
    return store.activeUserIds().stream()
        .findFirst()
        .orElseThrow(() -> new InvalidRequestException("没有可用于调试的本地用户数据"));
  }

  private UUID authenticate(String token) {
    if (blank(token)) {
      throw new UnauthorizedException();
    }
    return store
        .findSessionUser(hashToken(token), Instant.now())
        .orElseThrow(UnauthorizedException::new);
  }

  private static GoalDto goal(GoalState goal, BigDecimal current) {
    BigDecimal denominator = goal.startWeightJin().subtract(goal.targetWeightJin());
    int percent = 0;
    if (denominator.compareTo(BigDecimal.ZERO) != 0) {
      percent =
          goal.startWeightJin()
              .subtract(current)
              .multiply(BigDecimal.valueOf(100))
              .divide(denominator, 0, RoundingMode.HALF_UP)
              .intValue();
    }
    percent = Math.max(0, Math.min(100, percent));
    return new GoalDto(
        goal.id(),
        goal.name(),
        goal.startWeightJin(),
        current,
        goal.targetWeightJin(),
        goal.status(),
        percent);
  }

  private static BigDecimal currentWeight(BootstrapData data) {
    return data.bodyRecords().stream()
        .map(BodyRecordDto::weightJin)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(data.goal().startWeightJin());
  }

  private static ReportDto report(BootstrapData data, GoalDto goal) {
    long completedMeals = data.meals().size();
    int score =
        Math.min(100, 55 + goal.progressPercent() / 2 + Math.min(20, (int) completedMeals * 2));
    String conclusion = "基于数据库中的身体、饮食和训练记录确定性计算（非 AI）：当前目标进度 " + goal.progressPercent() + "%";
    return new ReportDto(
        "READY",
        score,
        conclusion,
        List.of(
            new ReportMetric("目标进度", goal.progressPercent() + "%"),
            new ReportMetric(
                "当前体重", goal.currentWeightJin().stripTrailingZeros().toPlainString() + "斤"),
            new ReportMetric("饮食记录", completedMeals + "条")),
        List.of("继续每周记录体重和腰围", "按计划完成训练并如实记录完成比例"));
  }

  private static void positive(BigDecimal value, String name) {
    if (value != null && value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new InvalidRequestException(name + " 必须大于 0");
    }
  }

  private static TrainingProfileInput validateTrainingProfile(TrainingProfileInput request) {
    if (request == null) {
      throw new InvalidRequestException("请补充训练档案");
    }
    String biologicalSex =
        allowed(request.biologicalSex(), "biologicalSex", "FEMALE", "MALE", "NOT_DISCLOSED");
    String experienceLevel =
        allowed(
            request.experienceLevel(), "experienceLevel", "BEGINNER", "INTERMEDIATE", "ADVANCED");
    List<String> venues =
        allowedStrings(
            request.trainingVenues(),
            "trainingVenues",
            Set.of("HOME", "GYM", "OUTDOOR", "OTHER"),
            true);
    List<String> equipment =
        freeTextList(request.availableEquipment(), "availableEquipment", 20, 40);
    List<Integer> weekdays = weekdays(request.trainingWeekdays());
    List<String> restrictions =
        freeTextList(request.trainingRestrictions(), "trainingRestrictions", 20, 80);
    String coachingTone =
        optionalAllowed(
            request.coachingTone(),
            "coachingTone",
            "WARM_DIRECT",
            "LIGHT_HEARTED",
            "CALM_PROFESSIONAL");
    List<String> nutritionPreferences =
        freeTextList(request.nutritionPreferences(), "nutritionPreferences", 20, 40);
    if (request.sessionMinutes() == null
        || request.sessionMinutes() < 10
        || request.sessionMinutes() > 180) {
      throw new InvalidRequestException("sessionMinutes 必须在 10 到 180 之间");
    }
    if (request.birthYear() != null
        && (request.birthYear() < 1900
            || request.birthYear() > LocalDate.now(USER_ZONE).getYear())) {
      throw new InvalidRequestException("birthYear 不合理");
    }
    if (request.heightCm() != null
        && (request.heightCm().compareTo(BigDecimal.valueOf(80)) < 0
            || request.heightCm().compareTo(BigDecimal.valueOf(250)) > 0)) {
      throw new InvalidRequestException("heightCm 必须在 80 到 250 之间");
    }
    return new TrainingProfileInput(
        biologicalSex,
        request.birthYear(),
        request.heightCm(),
        experienceLevel,
        venues,
        equipment,
        weekdays,
        request.sessionMinutes(),
        restrictions,
        coachingTone,
        nutritionPreferences);
  }

  private static String allowed(String value, String field, String... candidates) {
    if (blank(value) || !Set.of(candidates).contains(value.trim())) {
      throw new InvalidRequestException(field + " 不合法");
    }
    return value.trim();
  }

  private static String optionalAllowed(String value, String field, String... candidates) {
    return blank(value) ? candidates[0] : allowed(value, field, candidates);
  }

  private static List<String> allowedStrings(
      List<String> values, String field, Set<String> candidates, boolean required) {
    if (values == null || (required && values.isEmpty())) {
      throw new InvalidRequestException(field + " 至少选择一项");
    }
    List<String> normalized =
        values.stream().map(value -> value == null ? "" : value.trim()).toList();
    if (normalized.stream().anyMatch(value -> !candidates.contains(value))
        || normalized.stream().distinct().count() != normalized.size()) {
      throw new InvalidRequestException(field + " 不合法");
    }
    return normalized;
  }

  private static List<String> freeTextList(
      List<String> values, String field, int maxItems, int maxLength) {
    if (values == null) return List.of();
    List<String> normalized =
        values.stream().map(value -> value == null ? "" : value.trim()).toList();
    if (normalized.size() > maxItems
        || normalized.stream().anyMatch(value -> value.isEmpty() || value.length() > maxLength)
        || normalized.stream().distinct().count() != normalized.size()) {
      throw new InvalidRequestException(field + " 不合法");
    }
    return normalized;
  }

  private static List<Integer> weekdays(List<Integer> values) {
    if (values == null) return List.of();
    if (values.stream().anyMatch(value -> value == null || value < 1 || value > 7)
        || values.stream().distinct().count() != values.size()) {
      throw new InvalidRequestException("trainingWeekdays 不合法");
    }
    return values.stream().sorted().toList();
  }

  private static void rejectFuture(Instant occurredAt, String field) {
    if (occurredAt != null && occurredAt.isAfter(Instant.now())) {
      throw new InvalidRequestException(field + " 不能晚于当前时刻");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String hashToken(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
