package happy.jayden.yang.fitness;

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
        value.occurredAt());
  }

  static MealRecordPage mealPage(List<MealDto> values) {
    return new MealRecordPage(
        values.stream().map(FitnessV1Responses::meal).toList(), new Page(false));
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

  record Page(boolean hasMore) {}
}
