package happy.jayden.yang.fitness.service;

import happy.jayden.yang.fitness.service.FitnessDtos.AiMessageResponse;
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
import happy.jayden.yang.fitness.service.FitnessDtos.GoalDto;
import happy.jayden.yang.fitness.service.FitnessDtos.GoalState;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionJobDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.ReportDto;
import happy.jayden.yang.fitness.service.FitnessDtos.ReportMetric;
import happy.jayden.yang.fitness.service.FitnessDtos.WorkoutCompletionDto;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import happy.jayden.yang.fitness.service.FitnessExceptions.IdempotencyConflictException;
import happy.jayden.yang.fitness.service.FitnessExceptions.IdempotencyConcurrencyException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessExceptions.UnauthorizedException;
import happy.jayden.yang.fitness.service.FitnessPorts.AgentProviderStatus;
import happy.jayden.yang.fitness.service.FitnessPorts.AiConversation;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
  private final AiConversation aiConversation;
  private final MediaUploadPort mediaUploadPort;
  private final TransactionRunner transactionRunner;
  private final DailyMealPlanGenerationPort dailyMealPlanGenerationPort;
  private final SecureRandom secureRandom = new SecureRandom();

  public FitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      AiConversation aiConversation,
      MediaUploadPort mediaUploadPort) {
    this(
        store,
        passwordVerifier,
        providerStatus,
        aiConversation,
        mediaUploadPort,
        (userId, date, feedback) ->
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
      AiConversation aiConversation,
      MediaUploadPort mediaUploadPort,
      TransactionRunner transactionRunner) {
    this(
        store,
        passwordVerifier,
        providerStatus,
        aiConversation,
        mediaUploadPort,
        (userId, date, feedback) ->
            new FitnessDtos.DailyMealPlanGenerationResult(
                "FAILED", List.of(), "DEPENDENCY_NOT_CONFIGURED", "三餐生成运行时未配置"),
        transactionRunner);
  }

  public FitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      AiConversation aiConversation,
      MediaUploadPort mediaUploadPort,
      DailyMealPlanGenerationPort dailyMealPlanGenerationPort,
      TransactionRunner transactionRunner) {
    this.store = store;
    this.passwordVerifier = passwordVerifier;
    this.providerStatus = providerStatus;
    this.aiConversation = aiConversation;
    this.mediaUploadPort = mediaUploadPort;
    this.dailyMealPlanGenerationPort = dailyMealPlanGenerationPort;
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

  public void logout(String sessionToken) {
    if (!blank(sessionToken)) {
      store.revokeSession(hashToken(sessionToken));
    }
  }

  public BootstrapDto bootstrap(String sessionToken) {
    BootstrapData data = store.loadBootstrap(authenticate(sessionToken), LocalDate.now(USER_ZONE));
    BigDecimal currentWeight = currentWeight(data);
    GoalDto goal = goal(data.goal(), currentWeight);
    return new BootstrapDto(
        data.user(),
        goal,
        data.bodyRecords(),
        data.meals(),
        data.mealRecommendations(),
        data.plan(),
        data.exercises(),
        data.completedWorkoutCount(),
        report(data, goal),
        new AiStatusDto(
            providerStatus.configured(), providerStatus.configured() ? null : AI_REASON));
  }

  public UUID authenticateSession(String sessionToken) {
    return authenticate(sessionToken);
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

  /** Loads data for a trusted in-process Agent Tool context. */
  public BootstrapData loadForTool(UUID userId) {
    return store.loadBootstrap(
        java.util.Objects.requireNonNull(userId, "userId"), LocalDate.now(USER_ZONE));
  }

  public BodyRecordDto createBodyRecord(String sessionToken, CreateBodyRecordRequest request) {
    if (request == null || (request.weightJin() == null && request.waistCm() == null)) {
      throw new InvalidRequestException("weightJin 和 waistCm 至少填写一个");
    }
    positive(request.weightJin(), "weightJin");
    positive(request.waistCm(), "waistCm");
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
    if (request.note() != null
        && request.note().codePointCount(0, request.note().length()) > 300) {
      throw new InvalidRequestException("说明不能超过 300 个字符");
    }
    if (request.reason() == FitnessDtos.FeedbackReason.OTHER
        && (request.note() == null || request.note().isBlank())) {
      throw new InvalidRequestException("OTHER 说明必须为 1 到 300 个字符");
    }
    return store.upsertMealRecommendationFeedback(authenticate(sessionToken), request);
  }

  public FitnessDtos.MealRecommendationFeedbackContext mealRecommendationFeedbackContext(UUID userId) {
    return store.mealRecommendationFeedbackContext(userId, Instant.now().minus(Duration.ofDays(30)));
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
   * Persists an asynchronous request. HTTP and the 05:30 scheduler never invoke the model; only
   * the leased worker below can do so.
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
    for (UUID userId : store.activeUserIds()) {
      enqueueDailyMealPlan(userId, today);
    }
  }

  private FitnessDtos.DailyMealPlanStateDto enqueueDailyMealPlan(UUID userId, LocalDate date) {
    var existing = store.findDailyMealPlan(userId, date);
    if (existing.isPresent()
        && ("READY".equals(existing.get().run().status())
            || "GENERATING".equals(existing.get().run().status()))) {
      return existing.get();
    }
    store.enqueueDailyMealPlanGeneration(userId, date);
    return store
        .findDailyMealPlan(userId, date)
        .orElseThrow(() -> new IllegalStateException("三餐生成状态未持久化"));
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
      result =
          dailyMealPlanGenerationPort.generate(
              run.userId(), run.date(), mealRecommendationFeedbackContext(run.userId()));
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
                blank(generationResult.failureCode()) ? "RUNTIME_ERROR" : generationResult.failureCode(),
                blank(generationResult.failureMessage()) ? "三餐生成未完成" : generationResult.failureMessage());
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
    java.util.Set<FitnessDtos.MealType> types = java.util.EnumSet.noneOf(FitnessDtos.MealType.class);
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
            .collect(java.util.stream.Collectors.toMap(FitnessDtos.MealRecommendationDto::mealType, item -> item));
    FitnessDtos.DailyMealPlanSectionDto breakfast = section(byType.get(FitnessDtos.MealType.BREAKFAST));
    FitnessDtos.DailyMealPlanSectionDto lunch = section(byType.get(FitnessDtos.MealType.LUNCH));
    FitnessDtos.DailyMealPlanSectionDto dinner = section(byType.get(FitnessDtos.MealType.DINNER));
    if (breakfast == null || lunch == null || dinner == null) {
      throw new IllegalStateException("READY 三餐计划缺少持久化餐次");
    }
    FitnessDtos.NutritionDto nutrition =
        nutrition(
            breakfast.nutrition().caloriesKcal()
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
                        item.name(), BigDecimal.ONE, "份", nutrition(BigDecimal.valueOf(item.estimatedKcal()))))
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
    return new FitnessDtos.NutritionDto(calories, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
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

  public AiMessageResponse sendAiMessage(String sessionToken, String message) {
    UUID userId = authenticate(sessionToken);
    if (blank(message)) {
      throw new InvalidRequestException("message 不能为空");
    }
    if (!providerStatus.configured()) {
      throw new DependencyNotConfiguredException();
    }
    return aiConversation.send(userId, message.trim());
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
