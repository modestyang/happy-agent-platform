package happy.jayden.yang.fitness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.infrastructure.JdbcFitnessAgentReadStore;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseCandidateFilter;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseDifficulty;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseImpactLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcFitnessAgentReadStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    var migrationDataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(migrationDataSource)
        .schemas("fitness")
        .defaultSchema("fitness")
        .locations("classpath:db/fitness")
        .load()
        .migrate();
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + "&currentSchema=fitness",
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  @Test
  void readsOnlyBoundedFactsForTheRequestedUser() {
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID exerciseId = UUID.randomUUID();
    insertUser(owner, "owner", "小花");
    insertUser(other, "other", "其他用户");
    jdbc.update(
        "INSERT INTO user_training_profiles(user_id,biological_sex,birth_year,height_cm,experience_level,training_venues,available_equipment,training_weekdays,session_minutes,training_restrictions,coaching_tone,nutrition_preferences) VALUES (?, 'FEMALE', 1996, 165, 'BEGINNER', '[\"HOME\"]', '[\"瑜伽垫\"]', '[1,3,5]', 30, '[\"避免跳跃\"]', 'WARM_DIRECT', '[\"中式家常\"]')",
        owner);
    jdbc.update(
        "INSERT INTO goals(goal_id,user_id,name,start_weight_jin,target_weight_jin,target_date,status,created_at) VALUES (?,?, '减脂',128,110,'2026-12-31','ACTIVE','2026-08-01T00:00:00Z')",
        UUID.randomUUID(),
        owner);
    jdbc.update(
        "INSERT INTO body_records(body_record_id,user_id,recorded_at,weight_jin,waist_cm) VALUES (?,?, '2026-08-10T01:00:00Z',128,NULL),(?,?, '2026-08-11T01:00:00Z',NULL,72),(?,?, '2026-08-12T01:00:00Z',200,100)",
        UUID.randomUUID(),
        owner,
        UUID.randomUUID(),
        owner,
        UUID.randomUUID(),
        other);
    jdbc.update(
        "INSERT INTO exercises(exercise_id,name,target_area,sets,seconds,steps,errors,image_urls) VALUES (?, '深蹲','腿部',3,45,'[\"下蹲\"]','[\"膝内扣\"]','[]')",
        exerciseId);
    UUID workoutId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workout_plans(workout_plan_id,user_id,title,estimated_minutes,status,scheduled_for,completion_ratio,completed_at) VALUES (?,?, '下肢训练',30,'COMPLETED','2026-08-10',1,'2026-08-10T02:00:00Z')",
        workoutId,
        owner);
    jdbc.update(
        "INSERT INTO workout_plan_exercises(workout_plan_id,exercise_id,display_order) VALUES (?,?,1)",
        workoutId,
        exerciseId);
    for (int index = 0; index < 3; index++) {
      jdbc.update(
          "INSERT INTO meals(meal_id,user_id,occurred_at,meal_type,items,source,note,created_at) VALUES (?,?,?,'LUNCH',?::jsonb,'MANUAL','private note',?)",
          UUID.randomUUID(),
          owner,
          java.sql.Timestamp.from(Instant.parse("2026-08-10T04:00:00Z").plusSeconds(index * 3600L)),
          "[{\"name\":\"米饭\",\"estimatedKcal\":500}]",
          java.sql.Timestamp.from(
              Instant.parse("2026-08-10T04:00:00Z").plusSeconds(index * 3600L)));
    }
    jdbc.update(
        "INSERT INTO meals(meal_id,user_id,occurred_at,meal_type,items,source,created_at) VALUES (?,?, '2026-08-10T04:00:00Z','LUNCH','[{\"name\":\"其他\",\"estimatedKcal\":999}]','MANUAL','2026-08-10T04:00:00Z')",
        UUID.randomUUID(),
        other);

    var store = new JdbcFitnessAgentReadStore(dataSource, new ObjectMapper());

    var profile = store.findUserProfile(owner).orElseThrow();
    var weight = store.findLatestWeight(owner).orElseThrow();
    var waist = store.findLatestWaist(owner).orElseThrow();
    var workouts =
        store.findWorkouts(owner, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 12), 10);
    var meals =
        store.findMeals(
            owner, Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-13T00:00:00Z"), 2);
    var exercises = store.searchExercises("深", "腿", 10);

    assertEquals("小花", profile.nickname());
    assertEquals(List.of("HOME"), profile.trainingVenues());
    assertEquals(new BigDecimal("128.00"), weight.value());
    assertEquals(new BigDecimal("72.00"), waist.value());
    assertEquals(1, workouts.totalCount());
    assertEquals(exerciseId, workouts.records().get(0).exercises().get(0).exerciseId());
    assertEquals(3, meals.totalCount());
    assertEquals(2, meals.records().size());
    assertEquals("米饭", meals.records().get(0).items().get(0).name());
    assertEquals(1, exercises.totalCount());
    assertTrue(exercises.records().get(0).steps().isEmpty());
  }

  @Test
  void filtersAndPagesExerciseCandidatesWithStableBalancedOrdering() {
    insertCandidate("徒手深蹲", "臀腿", "[\"股四头肌\"]", "[\"徒手\"]", "BEGINNER", "SQUAT", "LOW");
    insertCandidate("哑铃硬拉", "臀腿", "[\"腘绳肌\"]", "[\"哑铃\"]", "BEGINNER", "HINGE", "LOW");
    insertCandidate("杠铃硬拉", "臀腿", "[\"腘绳肌\"]", "[\"杠铃\"]", "BEGINNER", "HINGE", "LOW");
    insertCandidate(
        "中级俯卧撑", "胸部", "[\"胸大肌\"]", "[\"徒手\"]", "INTERMEDIATE", "HORIZONTAL_PUSH", "LOW");
    insertCandidate("开合跳", "心肺", "[\"股四头肌\"]", "[\"徒手\"]", "BEGINNER", "LOCOMOTION", "MEDIUM");
    jdbc.update(
        "INSERT INTO exercises(exercise_id,name,target_area,sets,seconds,steps,errors,image_urls) "
            + "VALUES (?, '未标注候选','核心',3,30,'[]','[]','[]')",
        UUID.randomUUID());
    var store = new JdbcFitnessAgentReadStore(dataSource, new ObjectMapper());

    var filtered =
        store.findExerciseCandidates(
            new ExerciseCandidateFilter(
                Set.of("徒手", "哑铃"),
                ExerciseDifficulty.BEGINNER,
                ExerciseImpactLevel.LOW,
                List.of("臀腿"),
                0,
                32));

    assertEquals(
        List.of("哑铃硬拉", "徒手深蹲"), filtered.records().stream().map(item -> item.name()).toList());
    assertEquals(2, filtered.eligibleCount());
    assertTrue(filtered.unlabeledCount() >= 1);
    assertEquals(2, filtered.eligibleCoverage().size());

    for (int index = 0; index < 34; index++) {
      insertCandidate(
          "分页动作" + String.format("%02d", index),
          index % 2 == 0 ? "核心" : "肩部",
          "[\"核心肌群\"]",
          "[\"徒手\"]",
          "BEGINNER",
          index % 2 == 0 ? "CORE_STABILITY" : "VERTICAL_PUSH",
          "LOW");
    }
    var allFilter =
        new ExerciseCandidateFilter(
            Set.of("徒手", "哑铃"),
            ExerciseDifficulty.BEGINNER,
            ExerciseImpactLevel.LOW,
            List.of(),
            0,
            32);
    var first = store.findExerciseCandidates(allFilter);
    var repeated = store.findExerciseCandidates(allFilter);
    var second =
        store.findExerciseCandidates(
            new ExerciseCandidateFilter(
                allFilter.availableEquipment(),
                allFilter.maxDifficulty(),
                allFilter.maxImpactLevel(),
                allFilter.focusAreas(),
                32,
                12));

    assertEquals(36, first.eligibleCount());
    assertEquals(32, first.records().size());
    assertEquals(4, second.records().size());
    assertEquals(
        first.records().stream().map(item -> item.exerciseId()).toList(),
        repeated.records().stream().map(item -> item.exerciseId()).toList());
    var firstIds =
        first.records().stream()
            .map(item -> item.exerciseId())
            .collect(java.util.stream.Collectors.toSet());
    assertFalse(second.records().stream().anyMatch(item -> firstIds.contains(item.exerciseId())));
  }

  private static void insertCandidate(
      String name,
      String targetArea,
      String muscleGroups,
      String equipment,
      String difficulty,
      String movementPattern,
      String impactLevel) {
    jdbc.update(
        "INSERT INTO exercises(exercise_id,name,target_area,sets,seconds,steps,errors,image_urls,"
            + "muscle_groups,equipment,difficulty,movement_pattern,impact_level) "
            + "VALUES (?,?,?,3,30,'[]','[]','[]',?::jsonb,?::jsonb,?,?,?)",
        UUID.randomUUID(),
        name,
        targetArea,
        muscleGroups,
        equipment,
        difficulty,
        movementPattern,
        impactLevel);
  }

  private static void insertUser(UUID id, String username, String nickname) {
    jdbc.update(
        "INSERT INTO users(user_id,external_subject,status,username,password_hash,nickname) VALUES (?,?,'ACTIVE',?,'hash',?)",
        id,
        "local:" + username,
        username,
        nickname);
  }
}
