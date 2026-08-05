package happy.jayden.yang.fitness.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FitnessDtos {

  private FitnessDtos() {}

  public record UserDto(UUID id, String nickname) {}

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
      UUID id, String name, BigDecimal startWeightJin, BigDecimal targetWeightJin, String status) {}

  public record CreateGoalRequest(String name, BigDecimal targetWeightJin, LocalDate targetDate) {}

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

  public record MealDto(UUID id, Instant occurredAt, MealType mealType, List<MealItemDto> items) {}

  public record MealRecommendationDto(
      UUID id,
      LocalDate recommendationDate,
      MealType mealType,
      List<MealItemDto> items,
      String reason,
      String status,
      Instant generatedAt) {}

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
      List<PlanExerciseDto> exercises) {}

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

  public record BootstrapDto(
      UserDto user,
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
