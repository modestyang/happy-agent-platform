package happy.jayden.yang.fitness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.fitness.service.FitnessAgentDtos.BodyMetricFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.GoalFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.NutritionActivityLevel;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.UserProfileFact;
import happy.jayden.yang.fitness.service.NutritionTargetEstimator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NutritionTargetEstimatorTest {

  private final NutritionTargetEstimator estimator = new NutritionTargetEstimator();
  private final LocalDate today = LocalDate.of(2026, 8, 12);

  @Test
  void returnsAuditableRangesWithoutInventingAnActivityLevel() {
    var result =
        estimator.estimate(
            profile("FEMALE", 1996, "165.0"),
            new BodyMetricFact(new BigDecimal("128.0"), Instant.parse("2026-08-11T00:00:00Z")),
            goal("100.0", today.plusWeeks(20)),
            null,
            today);

    assertEquals("AVAILABLE", result.status());
    assertEquals("nutrition-targets-v1", result.method().version());
    assertEquals(new BigDecimal("1360"), result.bmrKcalRange().minimum());
    assertEquals(new BigDecimal("1365"), result.bmrKcalRange().maximum());
    assertNull(result.maintenanceKcalRange());
    assertEquals(new BigDecimal("90"), result.exerciseProteinReferenceGramsRange().minimum());
    assertEquals(new BigDecimal("128"), result.exerciseProteinReferenceGramsRange().maximum());
    assertTrue(result.method().assumptions().contains("活动水平未由用户明确提供，未估算维持热量"));
  }

  @Test
  void usesBothSexFormulaeWhenSexIsNotDisclosedAndFlagsAggressivePace() {
    var result =
        estimator.estimate(
            profile("NOT_DISCLOSED", 1996, "165.0"),
            new BodyMetricFact(new BigDecimal("128.0"), Instant.parse("2026-08-11T00:00:00Z")),
            goal("100.0", today.plusWeeks(4)),
            NutritionActivityLevel.MODERATE,
            today);

    assertEquals(new BigDecimal("2108"), result.maintenanceKcalRange().minimum());
    assertEquals(new BigDecimal("2373"), result.maintenanceKcalRange().maximum());
    assertEquals("AGGRESSIVE", result.targetPaceAssessment());
    assertTrue(result.method().assumptions().stream().anyMatch(value -> value.contains("男女公式")));
  }

  @Test
  void reportsMissingFactsInsteadOfReturningZeroTargets() {
    var result = estimator.estimate(profile("NOT_DISCLOSED", null, null), null, null, null, today);

    assertEquals("NEEDS_INPUT", result.status());
    assertEquals(List.of("birthYear", "heightCm", "latestWeightJin"), result.missingFields());
    assertNull(result.bmrKcalRange());
    assertNull(result.exerciseProteinReferenceGramsRange());
  }

  @Test
  void doesNotApplyAdultFormulaWithoutConfirmedAdultAge() {
    var result =
        estimator.estimate(
            profile("FEMALE", 2009, "165.0"),
            new BodyMetricFact(new BigDecimal("128.0"), Instant.parse("2026-08-11T00:00:00Z")),
            null,
            NutritionActivityLevel.MODERATE,
            today);

    assertEquals("NEEDS_INPUT", result.status());
    assertEquals(List.of("adultAge18Plus"), result.missingFields());
    assertNull(result.bmrKcalRange());
    assertNull(result.maintenanceKcalRange());
  }

  private static UserProfileFact profile(String sex, Integer birthYear, String heightCm) {
    return new UserProfileFact(
        "用户",
        sex,
        birthYear,
        heightCm == null ? null : new BigDecimal(heightCm),
        "BEGINNER",
        List.of("HOME"),
        List.of(),
        List.of(1, 3, 5),
        30,
        List.of(),
        "WARM_DIRECT",
        List.of());
  }

  private static GoalFact goal(String targetWeightJin, LocalDate targetDate) {
    return new GoalFact(
        UUID.randomUUID(),
        "减脂",
        "ACTIVE",
        Instant.parse("2026-08-01T00:00:00Z"),
        targetDate,
        new BigDecimal("128.0"),
        new BigDecimal(targetWeightJin));
  }
}
