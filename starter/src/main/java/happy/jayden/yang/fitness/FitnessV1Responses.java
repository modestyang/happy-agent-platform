package happy.jayden.yang.fitness;

import com.fasterxml.jackson.annotation.JsonInclude;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportFacts;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportMetric;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealItemDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionCandidate;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionJobDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire representations for the public contract; legacy DTO names are deliberately not leaked. */
final class FitnessV1Responses {
  private FitnessV1Responses() {}

  static Job job(MealRecognitionJobDto value) {
    Failure failure =
        value.failureCode() == null
            ? null
            : new Failure(
                value.failureCode(),
                value.failureMessage() == null ? "识别失败" : value.failureMessage(),
                "TIMEOUT".equals(value.failureCode())
                    || "DEPENDENCY_UNAVAILABLE".equals(value.failureCode()));
    return new Job(
        value.jobId(),
        value.status(),
        value.mediaId(),
        value.mealType().name(),
        value.occurredAt(),
        value.candidates(),
        failure,
        value.createdAt(),
        value.updatedAt());
  }

  static MealRecord meal(MealDto value) {
    double calories = value.items().stream().mapToDouble(MealItemDto::estimatedKcal).sum();
    return new MealRecord(
        value.id(),
        value.mealType().name(),
        value.occurredAt(),
        value.source(),
        value.recognitionJobId(),
        value.note(),
        value.items(),
        new Nutrition(calories, 0, 0, 0),
        value.createdAt());
  }

  static MealRecordPage mealPage(List<MealDto> values) {
    return new MealRecordPage(
        values.stream().map(FitnessV1Responses::meal).toList(), new Page(false));
  }

  static Object currentGoalReport(CurrentGoalReportRunDto value) {
    return switch (value.state()) {
      case "QUEUED" ->
          new QueuedCurrentGoalReport(
              value.reportId(),
              value.goalId(),
              value.goalVersion(),
              "QUEUED",
              value.windowStart(),
              value.windowEnd(),
              value.updatedAt());
      case "GENERATING" ->
          new GeneratingCurrentGoalReport(
              value.reportId(),
              value.goalId(),
              value.goalVersion(),
              "GENERATING",
              value.windowStart(),
              value.windowEnd(),
              value.updatedAt());
      case "FAILED" ->
          new FailedCurrentGoalReport(
              value.reportId(),
              value.goalId(),
              value.goalVersion(),
              "FAILED",
              value.windowStart(),
              value.windowEnd(),
              new Failure(
                  value.failureCode(),
                  value.failureMessage(),
                  "TASK_FAILED".equals(value.failureCode())),
              value.updatedAt());
      case "READY", "STALE" -> completeCurrentGoalReport(value);
      default -> throw new IllegalArgumentException("未知当前目标报告状态");
    };
  }

  private static CompleteCurrentGoalReport completeCurrentGoalReport(
      CurrentGoalReportRunDto value) {
    CurrentGoalReportFacts facts = value.facts();
    if (facts == null || value.narrative() == null || value.computedThrough() == null) {
      throw new IllegalStateException("完成报告缺少快照或叙事");
    }
    return new CompleteCurrentGoalReport(
        value.reportId(),
        value.goalId(),
        value.goalVersion(),
        value.state(),
        value.windowStart(),
        value.windowEnd(),
        value.narrative().conclusion(),
        facts.metrics().stream().map(FitnessV1Responses::reportMetric).toList(),
        facts.weightTrend(),
        facts.trainingVolume(),
        facts.trainingStructure(),
        facts.cardioPercent(),
        facts.strengthPercent(),
        value.narrative().highlights(),
        value.narrative().weaknesses(),
        value.narrative().nextActions(),
        value.computedThrough(),
        value.updatedAt());
  }

  private static ReportMetric reportMetric(CurrentGoalReportMetric value) {
    return new ReportMetric(
        value.key(), value.label(), value.value(), value.unit(), value.comparison(), value.trend());
  }

  record Failure(String code, String message, boolean retryable) {}

  record Job(
      UUID jobId,
      String status,
      UUID mediaId,
      String mealType,
      Instant occurredAt,
      List<MealRecognitionCandidate> candidates,
      Failure failure,
      Instant createdAt,
      Instant updatedAt) {}

  record Nutrition(double caloriesKcal, double proteinG, double carbohydrateG, double fatG) {}

  record MealRecord(
      UUID mealRecordId,
      String mealType,
      Instant occurredAt,
      String source,
      UUID recognitionJobId,
      String note,
      List<MealItemDto> items,
      Nutrition nutrition,
      Instant createdAt) {}

  record MealRecordPage(List<MealRecord> items, Page page) {}

  record QueuedCurrentGoalReport(
      UUID reportId,
      UUID goalId,
      int goalVersion,
      String state,
      java.time.LocalDate windowStart,
      java.time.LocalDate windowEnd,
      Instant updatedAt) {}

  record GeneratingCurrentGoalReport(
      UUID reportId,
      UUID goalId,
      int goalVersion,
      String state,
      java.time.LocalDate windowStart,
      java.time.LocalDate windowEnd,
      Instant updatedAt) {}

  record FailedCurrentGoalReport(
      UUID reportId,
      UUID goalId,
      int goalVersion,
      String state,
      java.time.LocalDate windowStart,
      java.time.LocalDate windowEnd,
      Failure failure,
      Instant updatedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ReportMetric(
      String key,
      String label,
      java.math.BigDecimal value,
      String unit,
      java.math.BigDecimal comparison,
      String trend) {}

  record CompleteCurrentGoalReport(
      UUID reportId,
      UUID goalId,
      int goalVersion,
      String state,
      java.time.LocalDate windowStart,
      java.time.LocalDate windowEnd,
      happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportConclusion conclusion,
      List<ReportMetric> metrics,
      List<happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalWeightTrendPoint> weightTrend,
      List<happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalTrainingVolumePoint>
          trainingVolume,
      List<happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalTrainingStructureItem>
          trainingStructure,
      java.math.BigDecimal cardioPercent,
      java.math.BigDecimal strengthPercent,
      List<String> highlights,
      List<String> weaknesses,
      List<happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportNextAction> nextActions,
      Instant computedThrough,
      Instant updatedAt) {}

  record Page(boolean hasMore) {}
}
