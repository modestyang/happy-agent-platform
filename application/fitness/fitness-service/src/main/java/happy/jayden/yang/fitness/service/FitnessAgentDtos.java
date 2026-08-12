package happy.jayden.yang.fitness.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Facts and deterministic projections used only by Fitness Agent read capabilities. */
public final class FitnessAgentDtos {

  private FitnessAgentDtos() {}

  public enum NutritionActivityLevel {
    SEDENTARY,
    LIGHT,
    MODERATE,
    HIGH,
    VERY_HIGH
  }

  public record UserProfileFact(
      String nickname,
      String biologicalSex,
      Integer birthYear,
      BigDecimal heightCm,
      String experienceLevel,
      List<String> trainingVenues,
      List<String> availableEquipment,
      List<Integer> trainingWeekdays,
      Integer sessionMinutes,
      List<String> trainingRestrictions,
      String coachingTone,
      List<String> nutritionPreferences) {}

  public record GoalFact(
      UUID goalId,
      String name,
      String status,
      Instant startedAt,
      LocalDate targetDate,
      BigDecimal startWeightJin,
      BigDecimal targetWeightJin) {}

  public record BodyMetricFact(BigDecimal value, Instant recordedAt) {}

  public record BodyRecordFact(Instant recordedAt, BigDecimal weightJin, BigDecimal waistCm) {}

  public record ExerciseFact(
      UUID exerciseId,
      String name,
      String targetArea,
      int referenceSets,
      int referenceSeconds,
      List<String> steps,
      List<String> commonErrors) {}

  public enum ExerciseDifficulty {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED
  }

  public enum ExerciseMovementPattern {
    SQUAT,
    HINGE,
    LUNGE,
    HORIZONTAL_PUSH,
    VERTICAL_PUSH,
    HORIZONTAL_PULL,
    VERTICAL_PULL,
    CORE_STABILITY,
    CORE_FLEXION,
    ROTATION,
    LOCOMOTION,
    MOBILITY,
    ISOLATION
  }

  public enum ExerciseImpactLevel {
    LOW,
    MEDIUM,
    HIGH
  }

  public record ExerciseCandidateFact(
      UUID exerciseId,
      String name,
      String targetArea,
      List<String> muscleGroups,
      List<String> equipment,
      ExerciseDifficulty difficulty,
      ExerciseMovementPattern movementPattern,
      ExerciseImpactLevel impactLevel,
      int referenceSets,
      int referenceSeconds) {}

  public record ExerciseCandidateFilter(
      Set<String> availableEquipment,
      ExerciseDifficulty maxDifficulty,
      ExerciseImpactLevel maxImpactLevel,
      List<String> focusAreas,
      int offset,
      int limit) {}

  public record ExerciseCoverageFact(
      String targetArea, ExerciseMovementPattern movementPattern, long eligibleCount) {}

  public record ExerciseCandidatePage(
      List<ExerciseCandidateFact> records,
      long eligibleCount,
      long unlabeledCount,
      List<ExerciseCoverageFact> eligibleCoverage) {}

  public record ExerciseAppliedFilters(
      ExerciseDifficulty maxDifficulty,
      ExerciseImpactLevel maxImpactLevel,
      List<String> availableEquipment) {}

  public record ExerciseCoverage(
      String targetArea,
      ExerciseMovementPattern movementPattern,
      long eligibleCount,
      long returnedCount) {}

  public record ExerciseCandidatesView(
      QueryMetadata metadata,
      int page,
      List<ExerciseCandidateFact> candidates,
      ExerciseAppliedFilters appliedFilters,
      List<String> unrecognizedEquipment,
      long unlabeledCount,
      List<ExerciseCoverage> coverage,
      List<String> coverageGaps,
      boolean hasMore) {}

  public record WorkoutFact(
      UUID workoutPlanId,
      String title,
      int estimatedMinutes,
      String status,
      LocalDate scheduledFor,
      BigDecimal completionRatio,
      List<ExerciseFact> exercises) {}

  public record MealItemFact(String name, Integer estimatedKcal) {}

  public record MealFact(
      UUID mealId, Instant occurredAt, String mealType, String source, List<MealItemFact> items) {}

  public record MealRecommendationFact(
      UUID recommendationId,
      LocalDate recommendationDate,
      String mealType,
      List<MealItemFact> items,
      String reason,
      String status,
      Instant generatedAt) {}

  public record MealRecommendationStateFact(
      String status, List<MealRecommendationFact> recommendations) {}

  public record UserTextFact(String text, String origin, boolean executable) {}

  public record MealFeedbackFact(
      List<String> likedFoods,
      List<String> dislikedFoods,
      List<String> dislikeReasons,
      List<UserTextFact> noteReferences) {}

  public record RecordPage<T>(List<T> records, long totalCount) {}

  public record QueryWindow(LocalDate from, LocalDate to) {}

  public record QueryMetadata(
      Instant asOf,
      String timezone,
      QueryWindow window,
      String dataStatus,
      long recordCount,
      int limit,
      boolean truncated,
      List<String> limitations) {}

  public record ProfileView(
      QueryMetadata metadata,
      String nickname,
      String biologicalSex,
      AgeRangeYears ageRangeYears,
      BigDecimal heightCm,
      String coachingTone,
      List<String> missingFields) {}

  public record GoalView(QueryMetadata metadata, GoalFact currentGoal) {}

  public record TrainingConstraintsView(
      QueryMetadata metadata,
      String experienceLevel,
      List<String> trainingVenues,
      List<String> availableEquipment,
      List<Integer> trainingWeekdays,
      Integer sessionMinutes,
      List<String> trainingRestrictions,
      List<String> missingFields) {}

  public record NutritionPreferencesView(
      QueryMetadata metadata, List<String> preferences, String restrictionNote) {}

  public record LatestBodyView(
      QueryMetadata metadata, BodyMetricFact latestWeightJin, BodyMetricFact latestWaistCm) {}

  public record TrendChange(BigDecimal first, BigDecimal latest, BigDecimal change) {}

  public record BodyTrendPoint(LocalDate weekStart, BigDecimal weightJin, BigDecimal waistCm) {}

  public record BodyTrendView(
      QueryMetadata metadata,
      List<BodyTrendPoint> points,
      TrendChange weightTrendJin,
      TrendChange waistTrendCm) {}

  public record WorkoutsView(QueryMetadata metadata, List<WorkoutFact> workouts) {}

  public record WeeklyWorkoutSummary(
      LocalDate weekStart,
      int scheduledPastCount,
      int completedStatusCount,
      int estimatedMinutesOfCompletedPlans) {}

  public record TargetAreaCount(String targetArea, int planAppearances) {}

  public record WorkoutSummaryView(
      QueryMetadata metadata,
      int scheduledPastCount,
      int completedStatusCount,
      BigDecimal adherenceRate,
      BigDecimal averageCompletionRatio,
      int estimatedMinutesOfCompletedPlans,
      String durationSemantics,
      List<WeeklyWorkoutSummary> weeklySummaries,
      List<TargetAreaCount> targetAreaCounts) {}

  public record ExercisesView(QueryMetadata metadata, List<ExerciseFact> exercises) {}

  public record MealsView(QueryMetadata metadata, List<MealFact> meals) {}

  public record DailyMealSummary(LocalDate date, int mealRecordCount, Integer totalEstimatedKcal) {}

  public record MealSummaryView(
      QueryMetadata metadata,
      int daysWithRecords,
      int daysWithoutRecords,
      BigDecimal coverageRate,
      BigDecimal averageEstimatedKcalOnRecordedDays,
      List<DailyMealSummary> dailySummaries) {}

  public record MealRecommendationsView(
      QueryMetadata metadata, String status, List<MealRecommendationFact> recommendations) {}

  public record MealFeedbackView(QueryMetadata metadata, MealFeedbackFact feedback) {}

  public record NutritionTargetsView(QueryMetadata metadata, NutritionTargetEstimate estimate) {}

  public record NumericRange(BigDecimal minimum, BigDecimal maximum) {}

  public record AgeRangeYears(int minimum, int maximum) {}

  public record NutritionInputFacts(
      BigDecimal weightKg,
      BigDecimal heightCm,
      AgeRangeYears ageRangeYears,
      String biologicalSex,
      NutritionActivityLevel activityLevel) {}

  public record NutritionMethod(String name, String version, List<String> assumptions) {}

  public record NutritionTargetEstimate(
      String status,
      List<String> missingFields,
      NutritionInputFacts inputFacts,
      NutritionMethod method,
      NumericRange bmrKcalRange,
      NumericRange maintenanceKcalRange,
      NumericRange exerciseProteinReferenceGramsRange,
      String targetPaceAssessment,
      List<String> limitations) {}
}
