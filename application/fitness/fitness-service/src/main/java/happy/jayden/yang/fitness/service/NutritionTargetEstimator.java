package happy.jayden.yang.fitness.service;

import happy.jayden.yang.fitness.service.FitnessAgentDtos.AgeRangeYears;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.BodyMetricFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.GoalFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.NumericRange;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.NutritionActivityLevel;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.NutritionInputFacts;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.NutritionMethod;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.NutritionTargetEstimate;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.UserProfileFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Versioned, deterministic nutrition reference calculation. */
public final class NutritionTargetEstimator {

  private static final BigDecimal JIN_PER_KG = new BigDecimal("2");

  public NutritionTargetEstimate estimate(
      UserProfileFact profile,
      BodyMetricFact latestWeight,
      GoalFact goal,
      NutritionActivityLevel activityLevel,
      LocalDate asOfDate) {
    var missing = new ArrayList<String>();
    if (profile == null || profile.birthYear() == null) missing.add("birthYear");
    if (profile == null || profile.heightCm() == null) missing.add("heightCm");
    if (latestWeight == null || latestWeight.value() == null) missing.add("latestWeightJin");
    if (profile == null || profile.biologicalSex() == null) missing.add("biologicalSex");

    var assumptions = new ArrayList<String>();
    if (activityLevel == null) {
      assumptions.add("活动水平未由用户明确提供，未估算维持热量");
    }
    if (!missing.isEmpty()) {
      return new NutritionTargetEstimate(
          "NEEDS_INPUT",
          List.copyOf(missing),
          null,
          method(assumptions),
          null,
          null,
          null,
          pace(goal, latestWeight, asOfDate),
          limitations());
    }

    int youngerAge = asOfDate.getYear() - profile.birthYear() - 1;
    int olderAge = asOfDate.getYear() - profile.birthYear();
    if (youngerAge < 18) {
      return new NutritionTargetEstimate(
          "NEEDS_INPUT",
          List.of("adultAge18Plus"),
          null,
          method(assumptions),
          null,
          null,
          null,
          pace(goal, latestWeight, asOfDate),
          limitations());
    }
    var ageRange = new AgeRangeYears(youngerAge, olderAge);
    BigDecimal weightKg = latestWeight.value().divide(JIN_PER_KG, 3, RoundingMode.HALF_UP);
    BigDecimal femaleMinimum = bmr(weightKg, profile.heightCm(), olderAge, new BigDecimal("-161"));
    BigDecimal femaleMaximum =
        bmr(weightKg, profile.heightCm(), youngerAge, new BigDecimal("-161"));
    BigDecimal maleMinimum = bmr(weightKg, profile.heightCm(), olderAge, new BigDecimal("5"));
    BigDecimal maleMaximum = bmr(weightKg, profile.heightCm(), youngerAge, new BigDecimal("5"));

    BigDecimal minimum;
    BigDecimal maximum;
    if ("FEMALE".equals(profile.biologicalSex())) {
      minimum = femaleMinimum;
      maximum = femaleMaximum;
    } else if ("MALE".equals(profile.biologicalSex())) {
      minimum = maleMinimum;
      maximum = maleMaximum;
    } else {
      minimum = femaleMinimum.min(maleMinimum);
      maximum = femaleMaximum.max(maleMaximum);
      assumptions.add("未披露生理性别，结果覆盖 Mifflin-St Jeor 男女公式范围");
    }

    var bmr = range(minimum, maximum);
    NumericRange maintenance =
        activityLevel == null
            ? null
            : range(
                minimum.multiply(factor(activityLevel)), maximum.multiply(factor(activityLevel)));
    var protein =
        range(weightKg.multiply(new BigDecimal("1.4")), weightKg.multiply(new BigDecimal("2.0")));
    var inputs =
        new NutritionInputFacts(
            weightKg.stripTrailingZeros(),
            profile.heightCm(),
            ageRange,
            profile.biologicalSex(),
            activityLevel);
    return new NutritionTargetEstimate(
        "AVAILABLE",
        List.of(),
        inputs,
        method(assumptions),
        bmr,
        maintenance,
        protein,
        pace(goal, latestWeight, asOfDate),
        limitations());
  }

  private static NutritionMethod method(List<String> assumptions) {
    return new NutritionMethod(
        "Mifflin-St Jeor + product activity factors",
        "nutrition-targets-v1",
        List.copyOf(assumptions));
  }

  private static BigDecimal bmr(
      BigDecimal weightKg, BigDecimal heightCm, int age, BigDecimal sexAdjustment) {
    return weightKg
        .multiply(BigDecimal.TEN)
        .add(heightCm.multiply(new BigDecimal("6.25")))
        .subtract(new BigDecimal(age).multiply(new BigDecimal("5")))
        .add(sexAdjustment);
  }

  private static NumericRange range(BigDecimal minimum, BigDecimal maximum) {
    return new NumericRange(
        minimum.setScale(0, RoundingMode.HALF_UP), maximum.setScale(0, RoundingMode.HALF_UP));
  }

  private static BigDecimal factor(NutritionActivityLevel level) {
    return switch (level) {
      case SEDENTARY -> new BigDecimal("1.20");
      case LIGHT -> new BigDecimal("1.375");
      case MODERATE -> new BigDecimal("1.55");
      case HIGH -> new BigDecimal("1.725");
      case VERY_HIGH -> new BigDecimal("1.90");
    };
  }

  private static String pace(GoalFact goal, BodyMetricFact latestWeight, LocalDate asOfDate) {
    if (goal == null
        || goal.targetDate() == null
        || goal.targetWeightJin() == null
        || latestWeight == null
        || latestWeight.value() == null) return null;
    long days = ChronoUnit.DAYS.between(asOfDate, goal.targetDate());
    if (days <= 0 || goal.targetWeightJin().compareTo(latestWeight.value()) >= 0) return null;
    BigDecimal kilogramsToLose =
        latestWeight
            .value()
            .subtract(goal.targetWeightJin())
            .divide(JIN_PER_KG, 3, RoundingMode.HALF_UP);
    BigDecimal kilogramsPerWeek =
        kilogramsToLose
            .multiply(new BigDecimal("7"))
            .divide(new BigDecimal(days), 3, RoundingMode.HALF_UP);
    return kilogramsPerWeek.compareTo(new BigDecimal("0.9")) > 0 ? "AGGRESSIVE" : "GRADUAL";
  }

  private static List<String> limitations() {
    return List.of("结果是基于现有身体资料的公式估算，不是实际能量消耗测量", "蛋白质范围仅供健康运动成人参考，不代表实际摄入或医学处方");
  }
}
