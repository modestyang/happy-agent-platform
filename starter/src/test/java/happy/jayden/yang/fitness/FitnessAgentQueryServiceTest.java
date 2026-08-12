package happy.jayden.yang.fitness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseCandidateFilter;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseCandidatePage;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseDifficulty;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseImpactLevel;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.MealFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.MealItemFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.RecordPage;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.UserProfileFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.WorkoutFact;
import happy.jayden.yang.fitness.service.FitnessAgentQueryService;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessAgentReadStore;
import happy.jayden.yang.fitness.service.NutritionTargetEstimator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FitnessAgentQueryServiceTest {

  private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-12T04:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  @Mock private FitnessAgentReadStore store;
  private FitnessAgentQueryService queries;

  @BeforeEach
  void setUp() {
    queries =
        new FitnessAgentQueryService(
            store, new NutritionTargetEstimator(), Clock.fixed(NOW, ZoneOffset.UTC), ZONE);
  }

  @Test
  void profileReturnsAnAgeRangeAndNamesOnlyActuallyMissingFields() {
    when(store.findUserProfile(USER_ID))
        .thenReturn(
            Optional.of(
                new UserProfileFact(
                    "小花",
                    "NOT_DISCLOSED",
                    1996,
                    null,
                    "BEGINNER",
                    List.of("HOME"),
                    List.of("瑜伽垫"),
                    List.of(1, 3, 5),
                    30,
                    List.of(),
                    "WARM_DIRECT",
                    List.of("中式家常"))));

    var result = queries.profile(USER_ID);

    assertEquals("PARTIAL", result.metadata().dataStatus());
    assertEquals(29, result.ageRangeYears().minimum());
    assertEquals(30, result.ageRangeYears().maximum());
    assertEquals(List.of("heightCm"), result.missingFields());
    assertNull(result.heightCm());
  }

  @Test
  void workoutSummaryUsesPlanStatusAndEstimatedMinutesWithoutInventingActualDuration() {
    var completed =
        workout(LocalDate.of(2026, 8, 10), "COMPLETED", "1.0", 30, List.of(exercise("腿部")));
    var planned = workout(LocalDate.of(2026, 8, 11), "PLANNED", "0.5", 20, List.of(exercise("核心")));
    when(store.findWorkouts(USER_ID, LocalDate.of(2026, 7, 16), LocalDate.of(2026, 8, 12), 1001))
        .thenReturn(new RecordPage<>(List.of(completed, planned), 2));

    var result = queries.workoutSummary(USER_ID, 28);

    assertEquals(2, result.scheduledPastCount());
    assertEquals(1, result.completedStatusCount());
    assertEquals(new BigDecimal("0.500"), result.adherenceRate());
    assertEquals(new BigDecimal("0.750"), result.averageCompletionRatio());
    assertEquals(30, result.estimatedMinutesOfCompletedPlans());
    assertEquals("预计分钟来自已完成状态的计划，不代表实际训练时长", result.durationSemantics());
    assertEquals(
        List.of("核心", "腿部"),
        result.targetAreaCounts().stream().map(item -> item.targetArea()).toList());
  }

  @Test
  void workoutSummaryKeepsAdherenceUnknownWhenNothingWasScheduled() {
    when(store.findWorkouts(USER_ID, LocalDate.of(2026, 7, 16), LocalDate.of(2026, 8, 12), 1001))
        .thenReturn(new RecordPage<>(List.of(), 0));

    var result = queries.workoutSummary(USER_ID, 28);

    assertNull(result.adherenceRate());
    assertEquals("EMPTY", result.metadata().dataStatus());
  }

  @Test
  void mealSummaryExcludesUnrecordedDaysFromTheCalorieAverage() {
    var first =
        new MealFact(
            UUID.randomUUID(),
            Instant.parse("2026-08-10T04:00:00Z"),
            "LUNCH",
            "MANUAL",
            List.of(new MealItemFact("米饭", 500)));
    var second =
        new MealFact(
            UUID.randomUUID(),
            Instant.parse("2026-08-11T10:00:00Z"),
            "DINNER",
            "MANUAL",
            List.of(new MealItemFact("面条", 700)));
    when(store.findMeals(
            USER_ID,
            Instant.parse("2026-08-05T16:00:00Z"),
            Instant.parse("2026-08-12T16:00:00Z"),
            1001))
        .thenReturn(new RecordPage<>(List.of(first, second), 2));

    var result = queries.mealSummary(USER_ID, 7);

    assertEquals(2, result.daysWithRecords());
    assertEquals(5, result.daysWithoutRecords());
    assertEquals(new BigDecimal("0.286"), result.coverageRate());
    assertEquals(new BigDecimal("600"), result.averageEstimatedKcalOnRecordedDays());
  }

  @Test
  void historyAppliesTheRequestedLimitAndReportsTruncation() {
    var rows =
        List.of(
            meal("2026-08-12T03:00:00Z", "早餐"),
            meal("2026-08-11T03:00:00Z", "午餐"),
            meal("2026-08-10T03:00:00Z", "晚餐"));
    when(store.findMeals(
            USER_ID,
            Instant.parse("2026-08-05T16:00:00Z"),
            Instant.parse("2026-08-12T16:00:00Z"),
            3))
        .thenReturn(new RecordPage<>(rows, 3));

    var result = queries.mealHistory(USER_ID, 7, 2);

    assertEquals(2, result.meals().size());
    assertEquals(3, result.metadata().recordCount());
    assertEquals(2, result.metadata().limit());
    assertTrue(result.metadata().truncated());
  }

  @Test
  void scheduleReturnsChronologicalRowsEvenThoughHistoryStorageIsNewestFirst() {
    var later = workout(LocalDate.of(2026, 8, 13), "PLANNED", "0", 30, List.of());
    var earlier = workout(LocalDate.of(2026, 8, 12), "PLANNED", "0", 20, List.of());
    when(store.findWorkouts(USER_ID, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13), 101))
        .thenReturn(new RecordPage<>(List.of(later, earlier), 2));

    var result = queries.workoutSchedule(USER_ID, LocalDate.of(2026, 8, 12), 2);

    assertEquals(
        List.of(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13)),
        result.workouts().stream().map(WorkoutFact::scheduledFor).toList());
  }

  @Test
  void rejectsWindowsAndLimitsOutsidePublishedToolBounds() {
    assertThrows(IllegalArgumentException.class, () -> queries.bodyTrend(USER_ID, 6));
    assertThrows(IllegalArgumentException.class, () -> queries.workoutSchedule(USER_ID, null, 15));
    assertThrows(IllegalArgumentException.class, () -> queries.workoutHistory(USER_ID, 91, 20));
    assertThrows(IllegalArgumentException.class, () -> queries.workoutHistory(USER_ID, 28, 51));
    assertThrows(IllegalArgumentException.class, () -> queries.searchExercises(null, null, 21));
    assertThrows(IllegalArgumentException.class, () -> queries.exerciseDetails(List.of()));
    assertThrows(IllegalArgumentException.class, () -> queries.mealHistory(USER_ID, 31, 30));
    assertThrows(IllegalArgumentException.class, () -> queries.mealHistory(USER_ID, 7, 101));
    assertThrows(IllegalArgumentException.class, () -> queries.mealSummary(USER_ID, 91));
  }

  @Test
  void exerciseCandidatesApplyProfileEquipmentDifficultyAndImpactLimits() {
    when(store.findUserProfile(USER_ID))
        .thenReturn(
            Optional.of(
                new UserProfileFact(
                    "小花",
                    "NOT_DISCLOSED",
                    1996,
                    null,
                    "BEGINNER",
                    List.of("HOME"),
                    List.of("一对哑铃，瑜伽垫"),
                    List.of(1, 3, 5),
                    30,
                    List.of("膝盖不舒服，避免跳跃"),
                    "WARM_DIRECT",
                    List.of())));
    when(store.findExerciseCandidates(any()))
        .thenReturn(new ExerciseCandidatePage(List.of(), 0, 2, List.of()));

    var result =
        queries.exerciseCandidates(
            USER_ID, List.of("臀腿"), ExerciseImpactLevel.HIGH, Integer.valueOf(1));

    var filter = ArgumentCaptor.forClass(ExerciseCandidateFilter.class);
    verify(store).findExerciseCandidates(filter.capture());
    assertEquals(ExerciseDifficulty.BEGINNER, filter.getValue().maxDifficulty());
    assertEquals(ExerciseImpactLevel.LOW, filter.getValue().maxImpactLevel());
    assertEquals(Set.of("徒手", "哑铃", "瑜伽垫"), filter.getValue().availableEquipment());
    assertEquals(0, filter.getValue().offset());
    assertEquals(32, filter.getValue().limit());
    assertEquals(List.of(), result.unrecognizedEquipment());
    assertEquals(2, result.unlabeledCount());
    assertEquals(List.of("targetArea:臀腿:NO_ELIGIBLE"), result.coverageGaps());
    assertEquals("EMPTY", result.metadata().dataStatus());
  }

  @Test
  void exerciseCandidatesReportUnknownEquipmentAndUseConservativeMissingExperience() {
    when(store.findUserProfile(USER_ID))
        .thenReturn(
            Optional.of(
                new UserProfileFact(
                    "小花",
                    "NOT_DISCLOSED",
                    1996,
                    null,
                    null,
                    List.of("HOME"),
                    List.of("可调哑铃", "阻力带", "神秘器械"),
                    List.of(1, 3, 5),
                    30,
                    List.of(),
                    "WARM_DIRECT",
                    List.of())));
    when(store.findExerciseCandidates(any()))
        .thenReturn(new ExerciseCandidatePage(List.of(), 0, 0, List.of()));

    var result = queries.exerciseCandidates(USER_ID, List.of(), null, null);

    var filter = ArgumentCaptor.forClass(ExerciseCandidateFilter.class);
    verify(store).findExerciseCandidates(filter.capture());
    assertEquals(Set.of("徒手", "哑铃", "弹力带"), filter.getValue().availableEquipment());
    assertEquals(ExerciseDifficulty.BEGINNER, filter.getValue().maxDifficulty());
    assertEquals(List.of("神秘器械"), result.unrecognizedEquipment());
    assertTrue(result.metadata().limitations().contains("训练经验缺失，按 BEGINNER 筛选"));
  }

  @Test
  void exerciseCandidatesTreatWholeBodyAsNoFocusWithoutRelaxingHardLimits() {
    when(store.findExerciseCandidates(any()))
        .thenReturn(new ExerciseCandidatePage(List.of(), 0, 0, List.of()));

    queries.exerciseCandidates(
        USER_ID, List.of(" 全身 "), ExerciseImpactLevel.LOW, Integer.valueOf(1));

    var filter = ArgumentCaptor.forClass(ExerciseCandidateFilter.class);
    verify(store).findExerciseCandidates(filter.capture());
    assertEquals(List.of(), filter.getValue().focusAreas());
    assertEquals(ExerciseImpactLevel.LOW, filter.getValue().maxImpactLevel());
  }

  @Test
  void exerciseCandidatesValidateFocusAreasAndBoundedPages() {
    assertThrows(
        IllegalArgumentException.class,
        () -> queries.exerciseCandidates(USER_ID, List.of("未知部位"), null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> queries.exerciseCandidates(USER_ID, List.of("臀腿", "核心", "胸部", "背部"), null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> queries.exerciseCandidates(USER_ID, List.of("臀腿", "臀腿"), null, 1));
    var mixed =
        assertThrows(
            IllegalArgumentException.class,
            () -> queries.exerciseCandidates(USER_ID, List.of("全身", "核心"), null, 1));
    assertEquals("全身不能与具体部位同时使用", mixed.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> queries.exerciseCandidates(USER_ID, List.of(), null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> queries.exerciseCandidates(USER_ID, List.of(), null, 3));
  }

  private static WorkoutFact workout(
      LocalDate date,
      String status,
      String completionRatio,
      int estimatedMinutes,
      List<ExerciseFact> exercises) {
    return new WorkoutFact(
        UUID.randomUUID(),
        "训练",
        estimatedMinutes,
        status,
        date,
        new BigDecimal(completionRatio),
        exercises);
  }

  private static ExerciseFact exercise(String targetArea) {
    return new ExerciseFact(UUID.randomUUID(), "动作", targetArea, 3, 45, List.of(), List.of());
  }

  private static MealFact meal(String occurredAt, String name) {
    return new MealFact(
        UUID.randomUUID(),
        Instant.parse(occurredAt),
        "SNACK",
        "MANUAL",
        List.of(new MealItemFact(name, 100)));
  }
}
