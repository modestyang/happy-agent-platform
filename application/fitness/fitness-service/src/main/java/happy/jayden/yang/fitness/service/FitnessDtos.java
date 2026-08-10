package happy.jayden.yang.fitness.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FitnessDtos {

  private FitnessDtos() {}

  public record UserDto(UUID id, String nickname) {}

  public record RegisterRequest(String username, String nickname, String password) {}

  public record LoginRequest(String username, String password) {}

  public record LoginResponse(UserDto user) {}

  public record LoginResult(UserDto user, String sessionToken) {}

  public record GoalDto(
      UUID id,
      String name,
      BigDecimal startWeightJin,
      BigDecimal currentWeightJin,
      BigDecimal targetWeightJin,
      String status,
      int progressPercent) {}

  public record GoalState(
      UUID id,
      String name,
      BigDecimal startWeightJin,
      BigDecimal targetWeightJin,
      String status,
      int version,
      Instant startedAt) {}

  public record CreateGoalRequest(String name, BigDecimal targetWeightJin, LocalDate targetDate) {}

  public record FirstSetupRequest(
      BigDecimal weightJin, BigDecimal waistCm, BigDecimal targetWeightJin, LocalDate targetDate) {}

  public record BodyRecordDto(
      UUID id, Instant recordedAt, BigDecimal weightJin, BigDecimal waistCm) {}

  public record CreateBodyRecordRequest(
      BigDecimal weightJin, BigDecimal waistCm, Instant recordedAt) {}

  public enum MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK
  }

  public record MealItemDto(String name, int estimatedKcal) {}

  public record UploadHeader(String name, String value) {}

  public record MediaUploadTicket(
      UUID mediaId,
      String method,
      String uploadUrl,
      List<UploadHeader> headers,
      Instant expiresAt,
      long maxBytes) {}

  public record UploadedMedia(String contentType, byte[] bytes) {}

  public record CreateMediaUploadTicketRequest(
      String purpose, String contentType, long contentLength, String sha256) {}

  public record CreateMealRecognitionJobRequest(
      UUID mediaId, MealType mealType, Instant occurredAt) {}

  public record MealRecognitionCandidate(String name, int estimatedKcal, double confidence) {}

  public record MealRecognitionResult(
      String status,
      List<MealRecognitionCandidate> candidates,
      String failureCode,
      String failureMessage) {}

  public record MealRecognitionJobDto(
      UUID jobId,
      String status,
      UUID mediaId,
      MealType mealType,
      Instant occurredAt,
      List<MealRecognitionCandidate> candidates,
      String failureCode,
      String failureMessage,
      Instant createdAt,
      Instant updatedAt) {}

  /** Worker-only lease; owner identity is read from durable storage, never a request value. */
  public record ClaimedMealRecognitionJob(
      UUID jobId,
      UUID userId,
      UUID mediaId,
      MealType mealType,
      Instant occurredAt,
      Instant claimedAt) {}

  public record IdempotencyEntry(UUID resourceId, String requestHash, String responseJson) {}

  public record CreateMealRecordRequest(
      MealType mealType,
      Instant occurredAt,
      String source,
      UUID recognitionJobId,
      List<MealItemDto> items,
      String note) {}

  public record MealDto(
      UUID id,
      Instant occurredAt,
      MealType mealType,
      List<MealItemDto> items,
      String source,
      UUID recognitionJobId,
      String note,
      Instant createdAt) {}

  public enum Sentiment {
    LIKE,
    DISLIKE
  }

  public enum FeedbackReason {
    TASTE,
    PORTION,
    INGREDIENT,
    CALORIES,
    COOKING,
    OTHER
  }

  public record CreateMealRecommendationFeedbackRequest(
      UUID recommendationId, Sentiment sentiment, FeedbackReason reason, String note) {}

  public record MealRecommendationFeedbackDto(
      UUID recommendationId,
      Sentiment sentiment,
      FeedbackReason reason,
      String note,
      Instant createdAt,
      Instant updatedAt) {}

  public record MealRecommendationFeedbackContext(
      List<String> likedFoods,
      List<String> dislikedFoods,
      List<String> dislikeReasons,
      List<String> notes) {}

  public record MealRecommendationDto(
      UUID id,
      LocalDate recommendationDate,
      MealType mealType,
      List<MealItemDto> items,
      String reason,
      String status,
      Instant generatedAt,
      MealRecommendationFeedbackDto feedback) {}

  /** Durable state of one user's date-scoped three-meal generation. */
  public record DailyMealPlanRunDto(
      UUID mealPlanId,
      UUID userId,
      LocalDate date,
      String status,
      Instant generatedAt,
      String failureCode,
      String failureMessage,
      int version,
      UUID leaseToken,
      Instant leaseUntil) {}

  /** A fenced worker claim. A late worker must never complete a newer claim. */
  public record ClaimedDailyMealPlanRunDto(DailyMealPlanRunDto run) {}

  public record DailyMealPlanStateDto(
      DailyMealPlanRunDto run, List<MealRecommendationDto> recommendations) {}

  /** The bounded result returned by the dedicated meal-generation runtime. */
  public record DailyMealPlanGenerationResult(
      String status,
      List<GeneratedMealRecommendation> recommendations,
      String failureCode,
      String failureMessage) {}

  public record GeneratedMealRecommendation(
      MealType mealType, List<MealItemDto> items, String reason) {}

  public record DailyMealPlanFoodItem(
      String name, BigDecimal quantity, String unit, NutritionDto nutrition) {}

  public record NutritionDto(
      BigDecimal caloriesKcal, BigDecimal proteinG, BigDecimal carbohydrateG, BigDecimal fatG) {}

  public record DailyMealPlanSectionDto(
      MealType mealType, String title, List<DailyMealPlanFoodItem> items, NutritionDto nutrition) {}

  /**
   * Status-specific public wire values. Each record exactly matches one OpenAPI oneOf branch and
   * deliberately has no null-filled members from another state.
   */
  public sealed interface DailyMealPlanDto
      permits GeneratingDailyMealPlanDto, ReadyDailyMealPlanDto, FailedDailyMealPlanDto {
    UUID mealPlanId();

    LocalDate date();

    String timezone();

    String generatedAtLocalTime();

    String status();

    int version();
  }

  public record GeneratingDailyMealPlanDto(
      UUID mealPlanId,
      LocalDate date,
      String timezone,
      String generatedAtLocalTime,
      String status,
      int version)
      implements DailyMealPlanDto {}

  public record ReadyDailyMealPlanDto(
      UUID mealPlanId,
      LocalDate date,
      String timezone,
      String generatedAtLocalTime,
      String status,
      DailyMealPlanSectionDto breakfast,
      DailyMealPlanSectionDto lunch,
      DailyMealPlanSectionDto dinner,
      NutritionDto dailyNutrition,
      int version)
      implements DailyMealPlanDto {}

  public record FailedDailyMealPlanDto(
      UUID mealPlanId,
      LocalDate date,
      String timezone,
      String generatedAtLocalTime,
      String status,
      DailyMealPlanFailureDto failure,
      int version)
      implements DailyMealPlanDto {}

  public record DailyMealPlanFailureDto(String code, String message, boolean retryable) {}

  public record GenerateDailyMealPlanRequest(LocalDate date) {}

  /** Raw, time-windowed objective records. They deliberately contain no goal foreign key. */
  public record CurrentGoalReportSourceData(
      GoalState goal,
      Instant observedThrough,
      List<BodyRecordDto> bodyRecords,
      List<MealDto> meals,
      List<CurrentGoalWorkoutRecord> workouts) {}

  public record CurrentGoalWorkoutRecord(
      Instant completedAt, int minutes, List<String> targetAreas, String title) {}

  public record CurrentGoalReportMetric(
      String key,
      String label,
      BigDecimal value,
      String unit,
      BigDecimal comparison,
      String trend) {}

  public record CurrentGoalWeightTrendPoint(LocalDate weekStart, BigDecimal valueJin) {}

  public record CurrentGoalTrainingVolumePoint(LocalDate weekStart, int minutes, int sessions) {}

  public record CurrentGoalTrainingStructureItem(String area, BigDecimal percent) {}

  /**
   * Deterministic snapshot calculated solely by the fitness service before the Agent is invoked.
   */
  public record CurrentGoalReportFacts(
      String goalName,
      LocalDate windowStart,
      LocalDate windowEnd,
      List<CurrentGoalReportMetric> metrics,
      List<CurrentGoalWeightTrendPoint> weightTrend,
      List<CurrentGoalTrainingVolumePoint> trainingVolume,
      List<CurrentGoalTrainingStructureItem> trainingStructure,
      BigDecimal cardioPercent,
      BigDecimal strengthPercent) {}

  public record CurrentGoalReportConclusion(String summary, int score, String grade) {}

  /** The only fields a report Agent may generate; it cannot alter facts, charts, or percentages. */
  public record CurrentGoalReportNarrative(
      CurrentGoalReportConclusion conclusion,
      List<String> highlights,
      List<String> weaknesses,
      List<CurrentGoalReportNextAction> nextActions) {}

  public record CurrentGoalReportNextAction(String title, String rationale, String action) {}

  public record CurrentGoalReportGenerationResult(
      String status,
      CurrentGoalReportNarrative narrative,
      String failureCode,
      String failureMessage) {}

  public record CurrentGoalReportRunDto(
      UUID reportId,
      UUID userId,
      UUID goalId,
      int goalVersion,
      String state,
      LocalDate windowStart,
      LocalDate windowEnd,
      CurrentGoalReportFacts facts,
      CurrentGoalReportNarrative narrative,
      Instant computedThrough,
      String failureCode,
      String failureMessage,
      int version,
      UUID leaseToken,
      Instant leaseUntil,
      Instant updatedAt) {}

  public record ClaimedCurrentGoalReportRunDto(CurrentGoalReportRunDto run) {}

  public record CreateMealRequest(MealType mealType, List<MealItemDto> items, Instant occurredAt) {}

  public record PlanExerciseDto(
      UUID id,
      String name,
      String targetArea,
      int sets,
      int seconds,
      List<String> steps,
      List<String> errors) {}

  public record ExerciseDto(
      UUID id,
      String name,
      String targetArea,
      int sets,
      int seconds,
      List<String> steps,
      List<String> errors,
      String illustrationMode,
      List<String> imageUrls) {}

  public record PlanDto(
      UUID id,
      String title,
      int estimatedMinutes,
      String status,
      LocalDate scheduledFor,
      List<PlanExerciseDto> exercises) {}

  public record TrainingPlanDayInput(
      LocalDate scheduledFor, String title, int estimatedMinutes, List<UUID> exerciseIds) {}

  public record SaveTrainingPlanRequest(
      UUID approvalId, String scope, List<TrainingPlanDayInput> days) {}

  public record SavedTrainingPlanResult(List<UUID> planIds) {}

  public record CompleteWorkoutRequest(BigDecimal completionRatio) {}

  public record WorkoutCompletionDto(UUID id, String status, BigDecimal completionRatio) {}

  public record ReportMetric(String label, String value) {}

  public record ReportDto(
      String status,
      int score,
      String conclusion,
      List<ReportMetric> metrics,
      List<String> actions) {}

  public record AiStatusDto(boolean configured, String reason) {}

  public record AiMessageRequest(String message) {}

  public record AiMessageResponse(String message) {}

  public record BootstrapData(
      UserDto user,
      GoalState goal,
      List<BodyRecordDto> bodyRecords,
      List<MealDto> meals,
      List<MealRecommendationDto> mealRecommendations,
      PlanDto plan,
      List<ExerciseDto> exercises,
      long completedWorkoutCount) {}

  public record OnboardingDto(String state) {}

  public record BootstrapDto(
      UserDto user,
      OnboardingDto onboarding,
      GoalDto goal,
      List<BodyRecordDto> bodyRecords,
      List<MealDto> meals,
      List<MealRecommendationDto> mealRecommendations,
      PlanDto plan,
      List<ExerciseDto> exercises,
      long completedWorkoutCount,
      ReportDto report,
      AiStatusDto ai) {}

  public record LoginAccount(UUID userId, String nickname, String passwordHash) {}
}
