package happy.jayden.yang.fitness.service;

import happy.jayden.yang.fitness.service.FitnessDtos.AiMessageResponse;
import happy.jayden.yang.fitness.service.FitnessDtos.AiStatusDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapData;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapDto;
import happy.jayden.yang.fitness.service.FitnessDtos.CompleteWorkoutRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateGoalRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecognitionJobRequest;
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
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessExceptions.UnauthorizedException;
import happy.jayden.yang.fitness.service.FitnessPorts.AgentProviderStatus;
import happy.jayden.yang.fitness.service.FitnessPorts.AiConversation;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
import happy.jayden.yang.fitness.service.FitnessPorts.PasswordVerifier;
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
import java.util.UUID;

public final class FitnessApplicationService {

  public static final String AI_REASON = "请在 Agent 工作台配置模型 Provider";
  private static final Duration SESSION_TTL = Duration.ofDays(14);
  private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
  private final FitnessStore store;
  private final PasswordVerifier passwordVerifier;
  private final AgentProviderStatus providerStatus;
  private final AiConversation aiConversation;
  private final MediaUploadPort mediaUploadPort;
  private final SecureRandom secureRandom = new SecureRandom();

  public FitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      AiConversation aiConversation,
      MediaUploadPort mediaUploadPort) {
    this.store = store;
    this.passwordVerifier = passwordVerifier;
    this.providerStatus = providerStatus;
    this.aiConversation = aiConversation;
    this.mediaUploadPort = mediaUploadPort;
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
  public java.util.Optional<FitnessDtos.IdempotencyEntry> idempotency(UUID userId, String operation, String key) { return store.findIdempotency(userId, operation, key); }
  public void saveIdempotency(UUID userId, String operation, String key, String hash, UUID resourceId, String responseJson) { store.saveIdempotency(userId, operation, key, hash, resourceId, responseJson); }

  /** Loads data for a trusted in-process Agent Tool context. */
  public BootstrapData loadForTool(UUID userId) {
    return store.loadBootstrap(java.util.Objects.requireNonNull(userId, "userId"), LocalDate.now(USER_ZONE));
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
    if (request == null || !"MEAL_RECOGNITION".equals(request.purpose()) || blank(request.contentType()) || request.contentLength() <= 0 || blank(request.sha256())) {
      throw new InvalidRequestException("purpose、contentType、contentLength 和 sha256 必填");
    }
    if (!List.of("image/jpeg", "image/png", "image/webp").contains(request.contentType())
        || request.contentLength() > 10_485_760
        || !request.sha256().matches("[a-f0-9]{64}")) {
      throw new InvalidRequestException("图片格式、大小或 SHA-256 不合法");
    }
    return mediaUploadPort.createTicket(authenticate(sessionToken), request.contentType(), request.contentLength(), request.sha256());
  }

  public void markMediaUploaded(String sessionToken, UUID mediaId) {
    store.markMediaUploaded(authenticate(sessionToken), mediaId);
  }

  public MealRecognitionJobDto createMealRecognitionJob(
      String sessionToken, CreateMealRecognitionJobRequest request) {
    if (request == null || request.mediaId() == null || request.mealType() == null) {
      throw new InvalidRequestException("mediaId 和 mealType 必填");
    }
    UUID userId = authenticate(sessionToken);
    return store.createRecognitionJob(userId, request.mediaId(), request.mealType(), request.occurredAt());
  }

  public MealRecognitionJobDto mealRecognitionJob(String sessionToken, UUID jobId) {
    return store.findRecognitionJob(authenticate(sessionToken), jobId).orElseThrow(() -> new happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException("识别任务不存在"));
  }

  public MealDto createMealRecord(String sessionToken, CreateMealRecordRequest request) {
    if (request == null || request.mealType() == null || request.items() == null || request.items().isEmpty()) throw new InvalidRequestException("mealType 和 items 必填");
    if (!"MANUAL".equals(request.source()) && !"RECOGNITION_CONFIRMED".equals(request.source())) throw new InvalidRequestException("source 不合法");
    UUID userId = authenticate(sessionToken);
    if ("RECOGNITION_CONFIRMED".equals(request.source())) {
      if (request.recognitionJobId() == null) throw new InvalidRequestException("RECOGNITION_CONFIRMED 必须带 recognitionJobId");
      MealRecognitionJobDto job = store.findRecognitionJob(userId, request.recognitionJobId()).orElseThrow(() -> new happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException("识别任务不存在"));
      if (!"SUCCEEDED".equals(job.status())) throw new InvalidRequestException("识别任务尚未成功");
    }
    if (request.items().stream().anyMatch(item -> blank(item.name()) || item.estimatedKcal() < 0)) throw new InvalidRequestException("餐食名称不能为空且 estimatedKcal 不能为负数");
    return store.createMealRecord(userId, request);
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
