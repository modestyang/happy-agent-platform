package happy.jayden.yang.fitness.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.BodyMetricFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.BodyRecordFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseCandidateFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseCandidateFilter;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseCandidatePage;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseCoverageFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseDifficulty;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseImpactLevel;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.ExerciseMovementPattern;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.GoalFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.MealFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.MealFeedbackFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.MealItemFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.MealRecommendationFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.MealRecommendationStateFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.RecordPage;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.UserProfileFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.UserTextFact;
import happy.jayden.yang.fitness.service.FitnessAgentDtos.WorkoutFact;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessAgentReadStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent bounded SQL projections for Fitness Agent tools. */
public final class JdbcFitnessAgentReadStore implements FitnessAgentReadStore {

  private static final String EXERCISE_ELIGIBLE_CTE =
      "WITH labeled AS ("
          + "SELECT exercise_id,name,target_area,sets,seconds,muscle_groups,equipment,"
          + "difficulty,movement_pattern,impact_level FROM exercises "
          + "WHERE muscle_groups IS NOT NULL AND equipment IS NOT NULL "
          + "AND difficulty IS NOT NULL AND movement_pattern IS NOT NULL AND impact_level IS NOT NULL"
          + "), eligible_base AS ("
          + "SELECT *,CASE WHEN jsonb_build_array(target_area) <@ ?::jsonb THEN 0 ELSE 1 END focus_priority "
          + "FROM labeled WHERE (CASE difficulty WHEN 'BEGINNER' THEN 1 WHEN 'INTERMEDIATE' THEN 2 ELSE 3 END)<=? "
          + "AND (CASE impact_level WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END)<=? "
          + "AND (equipment - '徒手') <@ ?::jsonb"
          + ")";
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<List<Integer>> INTEGERS = new TypeReference<>() {};
  private static final TypeReference<List<MealItemFact>> MEAL_ITEMS = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcFitnessAgentReadStore(DataSource dataSource, ObjectMapper mapper) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.mapper = mapper;
  }

  @Override
  public Optional<UserProfileFact> findUserProfile(UUID userId) {
    return jdbc
        .query(
            "SELECT u.nickname,p.biological_sex,p.birth_year,p.height_cm,p.experience_level,"
                + "p.training_venues,p.available_equipment,p.training_weekdays,p.session_minutes,"
                + "p.training_restrictions,p.coaching_tone,p.nutrition_preferences FROM users u "
                + "LEFT JOIN user_training_profiles p ON p.user_id=u.user_id "
                + "WHERE u.user_id=? AND u.status='ACTIVE'",
            (rs, row) ->
                new UserProfileFact(
                    rs.getString("nickname"),
                    rs.getString("biological_sex"),
                    rs.getObject("birth_year", Integer.class),
                    rs.getBigDecimal("height_cm"),
                    rs.getString("experience_level"),
                    strings(rs.getString("training_venues")),
                    strings(rs.getString("available_equipment")),
                    integers(rs.getString("training_weekdays")),
                    rs.getObject("session_minutes", Integer.class),
                    strings(rs.getString("training_restrictions")),
                    rs.getString("coaching_tone"),
                    strings(rs.getString("nutrition_preferences"))),
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<GoalFact> findCurrentGoal(UUID userId) {
    return jdbc
        .query(
            "SELECT goal_id,name,status,created_at,target_date,start_weight_jin,target_weight_jin "
                + "FROM goals WHERE user_id=? AND status='ACTIVE' ORDER BY created_at DESC LIMIT 1",
            (rs, row) ->
                new GoalFact(
                    rs.getObject("goal_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getObject("target_date", LocalDate.class),
                    rs.getBigDecimal("start_weight_jin"),
                    rs.getBigDecimal("target_weight_jin")),
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<BodyMetricFact> findLatestWeight(UUID userId) {
    return latestMetric(userId, "weight_jin");
  }

  @Override
  public Optional<BodyMetricFact> findLatestWaist(UUID userId) {
    return latestMetric(userId, "waist_cm");
  }

  private Optional<BodyMetricFact> latestMetric(UUID userId, String column) {
    String sql =
        "SELECT "
            + column
            + " AS value,recorded_at FROM body_records WHERE user_id=? AND "
            + column
            + " IS NOT NULL ORDER BY recorded_at DESC LIMIT 1";
    return jdbc
        .query(
            sql,
            (rs, row) ->
                new BodyMetricFact(
                    rs.getBigDecimal("value"), rs.getTimestamp("recorded_at").toInstant()),
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public RecordPage<BodyRecordFact> findBodyRecords(
      UUID userId, Instant fromInclusive, Instant toExclusive, int limit) {
    requireLimit(limit);
    long count =
        count(
            "SELECT count(*) FROM body_records WHERE user_id=? AND recorded_at>=? AND recorded_at<?",
            userId,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    var records =
        jdbc.query(
            "SELECT recorded_at,weight_jin,waist_cm FROM body_records WHERE user_id=? "
                + "AND recorded_at>=? AND recorded_at<? ORDER BY recorded_at ASC LIMIT ?",
            (rs, row) ->
                new BodyRecordFact(
                    rs.getTimestamp("recorded_at").toInstant(),
                    rs.getBigDecimal("weight_jin"),
                    rs.getBigDecimal("waist_cm")),
            userId,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            limit);
    return new RecordPage<>(records, count);
  }

  @Override
  public RecordPage<WorkoutFact> findWorkouts(
      UUID userId, LocalDate fromInclusive, LocalDate toInclusive, int limit) {
    requireLimit(limit);
    long count =
        count(
            "SELECT count(*) FROM workout_plans WHERE user_id=? AND scheduled_for BETWEEN ? AND ?",
            userId,
            fromInclusive,
            toInclusive);
    var base =
        jdbc.query(
            "SELECT workout_plan_id,title,estimated_minutes,status,scheduled_for,completion_ratio "
                + "FROM workout_plans WHERE user_id=? AND scheduled_for BETWEEN ? AND ? "
                + "ORDER BY scheduled_for DESC,workout_plan_id LIMIT ?",
            (rs, row) ->
                new WorkoutFact(
                    rs.getObject("workout_plan_id", UUID.class),
                    rs.getString("title"),
                    rs.getInt("estimated_minutes"),
                    rs.getString("status"),
                    rs.getObject("scheduled_for", LocalDate.class),
                    rs.getBigDecimal("completion_ratio"),
                    List.of()),
            userId,
            fromInclusive,
            toInclusive,
            limit);
    if (base.isEmpty()) return new RecordPage<>(List.of(), count);
    Map<UUID, List<ExerciseFact>> exercises = workoutExercises(userId, base);
    var records =
        base.stream()
            .map(
                item ->
                    new WorkoutFact(
                        item.workoutPlanId(),
                        item.title(),
                        item.estimatedMinutes(),
                        item.status(),
                        item.scheduledFor(),
                        item.completionRatio(),
                        exercises.getOrDefault(item.workoutPlanId(), List.of())))
            .toList();
    return new RecordPage<>(records, count);
  }

  private Map<UUID, List<ExerciseFact>> workoutExercises(UUID userId, List<WorkoutFact> workouts) {
    var ids = workouts.stream().map(WorkoutFact::workoutPlanId).toList();
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    var arguments = new ArrayList<Object>();
    arguments.add(userId);
    arguments.addAll(ids);
    var result = new LinkedHashMap<UUID, List<ExerciseFact>>();
    jdbc.query(
        "SELECT pe.workout_plan_id,e.exercise_id,e.name,e.target_area,e.sets,e.seconds "
            + "FROM workout_plan_exercises pe JOIN workout_plans wp ON wp.workout_plan_id=pe.workout_plan_id "
            + "JOIN exercises e ON e.exercise_id=pe.exercise_id WHERE wp.user_id=? "
            + "AND pe.workout_plan_id IN ("
            + placeholders
            + ") ORDER BY pe.workout_plan_id,pe.display_order",
        rs -> {
          UUID planId = rs.getObject("workout_plan_id", UUID.class);
          result.computeIfAbsent(planId, ignored -> new ArrayList<>()).add(compactExercise(rs));
        },
        arguments.toArray());
    return result;
  }

  @Override
  public RecordPage<MealFact> findMeals(
      UUID userId, Instant fromInclusive, Instant toExclusive, int limit) {
    requireLimit(limit);
    long count =
        count(
            "SELECT count(*) FROM meals WHERE user_id=? AND occurred_at>=? AND occurred_at<?",
            userId,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    var records =
        jdbc.query(
            "SELECT meal_id,occurred_at,meal_type,source,items FROM meals WHERE user_id=? "
                + "AND occurred_at>=? AND occurred_at<? ORDER BY occurred_at DESC,meal_id LIMIT ?",
            (rs, row) ->
                new MealFact(
                    rs.getObject("meal_id", UUID.class),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("meal_type"),
                    rs.getString("source"),
                    mealItems(rs.getString("items"))),
            userId,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            limit);
    return new RecordPage<>(records, count);
  }

  @Override
  public RecordPage<ExerciseFact> searchExercises(String keyword, String targetArea, int limit) {
    requireLimit(limit);
    String normalizedKeyword = normalized(keyword);
    String normalizedTarget = normalized(targetArea);
    String where = " WHERE (?='' OR lower(name) LIKE ?) AND (?='' OR lower(target_area) LIKE ?)";
    String keywordLike = "%" + normalizedKeyword + "%";
    String targetLike = "%" + normalizedTarget + "%";
    long count =
        count(
            "SELECT count(*) FROM exercises" + where,
            normalizedKeyword,
            keywordLike,
            normalizedTarget,
            targetLike);
    var records =
        jdbc.query(
            "SELECT exercise_id,name,target_area,sets,seconds FROM exercises"
                + where
                + " ORDER BY name,exercise_id LIMIT ?",
            (rs, row) -> compactExercise(rs),
            normalizedKeyword,
            keywordLike,
            normalizedTarget,
            targetLike,
            limit);
    return new RecordPage<>(records, count);
  }

  @Override
  public ExerciseCandidatePage findExerciseCandidates(ExerciseCandidateFilter filter) {
    if (filter.offset() < 0) throw new IllegalArgumentException("offset 不能小于 0");
    if (filter.limit() < 1 || filter.limit() > 32) {
      throw new IllegalArgumentException("limit 必须在 1 到 32 之间");
    }
    String focusJson = json(filter.focusAreas());
    String equipmentJson = json(filter.availableEquipment().stream().sorted().toList());
    Object[] baseArguments =
        new Object[] {
          focusJson,
          filter.maxDifficulty().ordinal() + 1,
          filter.maxImpactLevel().ordinal() + 1,
          equipmentJson
        };
    var coverage =
        jdbc.query(
            EXERCISE_ELIGIBLE_CTE
                + " SELECT target_area,movement_pattern,count(*) eligible_count FROM eligible_base "
                + "GROUP BY target_area,movement_pattern ORDER BY target_area,movement_pattern",
            (rs, row) ->
                new ExerciseCoverageFact(
                    rs.getString("target_area"),
                    ExerciseMovementPattern.valueOf(rs.getString("movement_pattern")),
                    rs.getLong("eligible_count")),
            baseArguments);
    long eligibleCount = coverage.stream().mapToLong(ExerciseCoverageFact::eligibleCount).sum();
    Object[] pageArguments =
        new Object[] {
          focusJson,
          filter.maxDifficulty().ordinal() + 1,
          filter.maxImpactLevel().ordinal() + 1,
          equipmentJson,
          filter.offset(),
          filter.offset() + filter.limit()
        };
    var records =
        jdbc.query(
            EXERCISE_ELIGIBLE_CTE
                + ", bucketed AS ("
                + "SELECT *,ROW_NUMBER() OVER (PARTITION BY target_area,movement_pattern "
                + "ORDER BY name,exercise_id) bucket_rank FROM eligible_base"
                + "), ranked AS ("
                + "SELECT *,ROW_NUMBER() OVER (ORDER BY bucket_rank,focus_priority,target_area,"
                + "movement_pattern,name,exercise_id) candidate_rank FROM bucketed"
                + ") SELECT exercise_id,name,target_area,sets,seconds,muscle_groups,equipment,"
                + "difficulty,movement_pattern,impact_level FROM ranked "
                + "WHERE candidate_rank>? AND candidate_rank<=? ORDER BY candidate_rank",
            (rs, row) -> candidate(rs),
            pageArguments);
    long unlabeledCount =
        count(
            "SELECT count(*) FROM exercises WHERE muscle_groups IS NULL OR equipment IS NULL "
                + "OR difficulty IS NULL OR movement_pattern IS NULL OR impact_level IS NULL");
    return new ExerciseCandidatePage(records, eligibleCount, unlabeledCount, coverage);
  }

  @Override
  public List<ExerciseFact> findExercises(List<UUID> exerciseIds) {
    if (exerciseIds.isEmpty()) return List.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(exerciseIds.size(), "?"));
    var found =
        jdbc.query(
            "SELECT exercise_id,name,target_area,sets,seconds,steps,errors FROM exercises "
                + "WHERE exercise_id IN ("
                + placeholders
                + ")",
            (rs, row) -> detailedExercise(rs),
            exerciseIds.toArray());
    var byId =
        found.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ExerciseFact::exerciseId, java.util.function.Function.identity()));
    return exerciseIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
  }

  @Override
  public MealRecommendationStateFact findMealRecommendations(UUID userId, LocalDate date) {
    String runStatus =
        jdbc
            .query(
                "SELECT status FROM daily_meal_plan_runs WHERE user_id=? AND plan_date=?",
                (rs, row) -> rs.getString("status"),
                userId,
                date)
            .stream()
            .findFirst()
            .orElse(null);
    var recommendations =
        jdbc.query(
            "SELECT recommendation_id,recommendation_date,meal_type,items,reason,status,generated_at "
                + "FROM daily_meal_recommendations WHERE user_id=? AND recommendation_date=? "
                + "ORDER BY CASE meal_type WHEN 'BREAKFAST' THEN 1 WHEN 'LUNCH' THEN 2 ELSE 3 END",
            (rs, row) ->
                new MealRecommendationFact(
                    rs.getObject("recommendation_id", UUID.class),
                    rs.getObject("recommendation_date", LocalDate.class),
                    rs.getString("meal_type"),
                    mealItems(rs.getString("items")),
                    rs.getString("reason"),
                    rs.getString("status"),
                    rs.getTimestamp("generated_at").toInstant()),
            userId,
            date);
    String status =
        runStatus != null
            ? runStatus
            : recommendations.isEmpty() ? "EMPTY" : recommendations.get(0).status();
    return new MealRecommendationStateFact(status, recommendations);
  }

  @Override
  public MealFeedbackFact findMealFeedback(UUID userId, Instant since) {
    var rows =
        jdbc.query(
            "SELECT f.sentiment,f.reason,f.note,d.items FROM meal_recommendation_feedback f "
                + "JOIN daily_meal_recommendations d ON d.user_id=f.user_id "
                + "AND d.recommendation_id=f.recommendation_id WHERE f.user_id=? AND f.updated_at>=? "
                + "ORDER BY f.updated_at DESC LIMIT 100",
            (rs, row) ->
                new FeedbackRow(
                    rs.getString("sentiment"),
                    rs.getString("reason"),
                    rs.getString("note"),
                    mealItems(rs.getString("items"))),
            userId,
            Timestamp.from(since));
    var liked = new ArrayList<String>();
    var disliked = new ArrayList<String>();
    var reasons = new ArrayList<String>();
    var notes = new ArrayList<UserTextFact>();
    for (var row : rows) {
      var names = row.items().stream().map(MealItemFact::name).toList();
      if ("LIKE".equals(row.sentiment())) liked.addAll(names);
      if ("DISLIKE".equals(row.sentiment())) {
        disliked.addAll(names);
        if (row.reason() != null) reasons.add(row.reason());
        if (row.note() != null && !row.note().isBlank()) {
          notes.add(new UserTextFact(row.note().trim(), "USER_FEEDBACK", false));
        }
      }
    }
    return new MealFeedbackFact(distinct(liked), distinct(disliked), distinct(reasons), notes);
  }

  private ExerciseFact compactExercise(ResultSet rs) throws SQLException {
    return new ExerciseFact(
        rs.getObject("exercise_id", UUID.class),
        rs.getString("name"),
        rs.getString("target_area"),
        rs.getInt("sets"),
        rs.getInt("seconds"),
        List.of(),
        List.of());
  }

  private ExerciseCandidateFact candidate(ResultSet rs) throws SQLException {
    return new ExerciseCandidateFact(
        rs.getObject("exercise_id", UUID.class),
        rs.getString("name"),
        rs.getString("target_area"),
        strings(rs.getString("muscle_groups")),
        strings(rs.getString("equipment")),
        ExerciseDifficulty.valueOf(rs.getString("difficulty")),
        ExerciseMovementPattern.valueOf(rs.getString("movement_pattern")),
        ExerciseImpactLevel.valueOf(rs.getString("impact_level")),
        rs.getInt("sets"),
        rs.getInt("seconds"));
  }

  private ExerciseFact detailedExercise(ResultSet rs) throws SQLException {
    return new ExerciseFact(
        rs.getObject("exercise_id", UUID.class),
        rs.getString("name"),
        rs.getString("target_area"),
        rs.getInt("sets"),
        rs.getInt("seconds"),
        strings(rs.getString("steps")),
        strings(rs.getString("errors")));
  }

  private long count(String sql, Object... arguments) {
    Long count = jdbc.queryForObject(sql, Long.class, arguments);
    return count == null ? 0 : count;
  }

  private List<String> strings(String json) throws SQLException {
    return read(json, STRINGS, List.of());
  }

  private List<Integer> integers(String json) throws SQLException {
    return read(json, INTEGERS, List.of());
  }

  private List<MealItemFact> mealItems(String json) throws SQLException {
    return read(json, MEAL_ITEMS, List.of());
  }

  private <T> T read(String json, TypeReference<T> type, T empty) throws SQLException {
    if (json == null) return empty;
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException exception) {
      throw new SQLException("Invalid Fitness Agent fact JSON", exception);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to encode Fitness Agent query JSON", exception);
    }
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static void requireLimit(int limit) {
    if (limit < 1 || limit > 1001) {
      throw new IllegalArgumentException("limit 必须在 1 到 1001 之间");
    }
  }

  private static List<String> distinct(List<String> values) {
    return values.stream().filter(java.util.Objects::nonNull).distinct().limit(50).toList();
  }

  private record FeedbackRow(
      String sentiment, String reason, String note, List<MealItemFact> items) {}
}
