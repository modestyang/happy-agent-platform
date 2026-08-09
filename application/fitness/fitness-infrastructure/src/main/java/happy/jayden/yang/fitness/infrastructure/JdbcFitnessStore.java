package happy.jayden.yang.fitness.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapData;
import happy.jayden.yang.fitness.service.FitnessDtos.ClaimedCurrentGoalReportRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.ClaimedDailyMealPlanRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.ClaimedMealRecognitionJob;
import happy.jayden.yang.fitness.service.FitnessDtos.CompleteWorkoutRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateGoalRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecommendationFeedbackRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportFacts;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportGenerationResult;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportNarrative;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportSourceData;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalWorkoutRecord;
import happy.jayden.yang.fitness.service.FitnessDtos.ExerciseDto;
import happy.jayden.yang.fitness.service.FitnessDtos.FeedbackReason;
import happy.jayden.yang.fitness.service.FitnessDtos.GoalState;
import happy.jayden.yang.fitness.service.FitnessDtos.IdempotencyEntry;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginAccount;
import happy.jayden.yang.fitness.service.FitnessDtos.MealDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealItemDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionCandidate;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionJobDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecommendationDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecommendationFeedbackDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessDtos.PlanDto;
import happy.jayden.yang.fitness.service.FitnessDtos.PlanExerciseDto;
import happy.jayden.yang.fitness.service.FitnessDtos.Sentiment;
import happy.jayden.yang.fitness.service.FitnessDtos.UserDto;
import happy.jayden.yang.fitness.service.FitnessDtos.WorkoutCompletionDto;
import happy.jayden.yang.fitness.service.FitnessExceptions.IdempotencyConcurrencyException;
import happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcFitnessStore implements FitnessStore {

  private static final TypeReference<List<MealItemDto>> MEAL_ITEMS = new TypeReference<>() {};
  private static final TypeReference<List<MealRecognitionCandidate>> CANDIDATES =
      new TypeReference<>() {};
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
  private static final int MAX_CONTEXT_FOOD_LENGTH = 120;
  private static final int MAX_CONTEXT_NOTE_LENGTH = 160;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcFitnessStore(DataSource fitnessDataSource, ObjectMapper objectMapper) {
    this.jdbc = new JdbcTemplate(fitnessDataSource);
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<LoginAccount> findLoginAccount(String username) {
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              "SELECT user_id,nickname,password_hash FROM users WHERE username=? AND"
                  + " status='ACTIVE'",
              (rs, row) ->
                  new LoginAccount(
                      rs.getObject("user_id", UUID.class),
                      rs.getString("nickname"),
                      rs.getString("password_hash")),
              username));
    } catch (EmptyResultDataAccessException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void createSession(String tokenHash, UUID userId, Instant expiresAt) {
    jdbc.update("DELETE FROM fitness_sessions WHERE expires_at <= CURRENT_TIMESTAMP");
    jdbc.update(
        "INSERT INTO fitness_sessions(session_token_hash,user_id,expires_at) VALUES (?,?,?)",
        tokenHash,
        userId,
        Timestamp.from(expiresAt));
  }

  @Override
  public Optional<UUID> findSessionUser(String tokenHash, Instant now) {
    List<UUID> values =
        jdbc.query(
            "SELECT user_id FROM fitness_sessions WHERE session_token_hash=? AND expires_at>?",
            (rs, row) -> rs.getObject("user_id", UUID.class),
            tokenHash,
            Timestamp.from(now));
    return values.stream().findFirst();
  }

  @Override
  public void revokeSession(String tokenHash) {
    jdbc.update("DELETE FROM fitness_sessions WHERE session_token_hash=?", tokenHash);
  }

  @Override
  public BootstrapData loadBootstrap(UUID userId, LocalDate recommendationDate) {
    UserDto user =
        required(
            "SELECT user_id,nickname FROM users WHERE user_id=?",
            (rs, row) -> new UserDto(rs.getObject("user_id", UUID.class), rs.getString("nickname")),
            userId);
    GoalState goal = latestGoal(userId);
    List<BodyRecordDto> records =
        jdbc.query(
            "SELECT body_record_id,recorded_at,weight_jin,waist_cm FROM body_records WHERE"
                + " user_id=? ORDER BY recorded_at DESC",
            (rs, row) ->
                new BodyRecordDto(
                    rs.getObject("body_record_id", UUID.class),
                    rs.getTimestamp("recorded_at").toInstant(),
                    rs.getBigDecimal("weight_jin"),
                    rs.getBigDecimal("waist_cm")),
            userId);
    List<MealDto> meals =
        jdbc.query(
            "SELECT meal_id,occurred_at,meal_type,items,source,recognition_job_id,note,created_at FROM meals"
                + " WHERE user_id=? ORDER BY occurred_at DESC",
            (rs, row) -> meal(rs),
            userId);
    List<MealRecommendationDto> mealRecommendations =
        jdbc.query(
            "SELECT"
                + " d.recommendation_id,d.recommendation_date,d.meal_type,d.items,d.reason,d.status,d.generated_at,"
                + " f.sentiment AS feedback_sentiment,f.reason AS feedback_reason,f.note AS feedback_note,f.created_at AS feedback_created_at,f.updated_at AS feedback_updated_at"
                + " FROM daily_meal_recommendations d LEFT JOIN meal_recommendation_feedback f ON f.recommendation_id=d.recommendation_id AND f.user_id=d.user_id WHERE d.user_id=? AND d.recommendation_date=? ORDER"
                + " BY CASE meal_type WHEN 'BREAKFAST' THEN 1 WHEN 'LUNCH' THEN 2 ELSE 3 END",
            (rs, row) -> mealRecommendation(rs),
            userId,
            recommendationDate);
    PlanDto plan = planForDate(userId, recommendationDate);
    List<ExerciseDto> exercises = exerciseDetails();
    Long completedWorkoutCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM workout_plans WHERE user_id=? AND status='COMPLETED'",
            Long.class,
            userId);
    return new BootstrapData(
        user,
        goal,
        records,
        meals,
        mealRecommendations,
        plan,
        exercises,
        completedWorkoutCount == null ? 0 : completedWorkoutCount);
  }

  @Override
  public BodyRecordDto createBodyRecord(UUID userId, CreateBodyRecordRequest request) {
    UUID id = UUID.randomUUID();
    Instant recordedAt = request.recordedAt() == null ? Instant.now() : request.recordedAt();
    jdbc.update(
        "INSERT INTO body_records(body_record_id,user_id,recorded_at,weight_jin,waist_cm) VALUES"
            + " (?,?,?,?,?)",
        id,
        userId,
        Timestamp.from(recordedAt),
        request.weightJin(),
        request.waistCm());
    return new BodyRecordDto(id, recordedAt, request.weightJin(), request.waistCm());
  }

  @Override
  public MealDto createMeal(UUID userId, CreateMealRequest request) {
    UUID id = UUID.randomUUID();
    Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();
    jdbc.update(
        "INSERT INTO meals(meal_id,user_id,occurred_at,meal_type,items) VALUES (?,?,?,?,?::jsonb)",
        id,
        userId,
        Timestamp.from(occurredAt),
        request.mealType().name(),
        json(request.items()));
    Instant createdAt =
        jdbc.queryForObject("SELECT created_at FROM meals WHERE meal_id=?", Timestamp.class, id)
            .toInstant();
    return new MealDto(
        id,
        occurredAt,
        request.mealType(),
        List.copyOf(request.items()),
        "MANUAL",
        null,
        null,
        createdAt);
  }

  @Override
  public void markMediaUploaded(UUID userId, UUID mediaId) {
    int changed =
        jdbc.update(
            "UPDATE media_objects SET status='UPLOADED' WHERE media_id=? AND user_id=? AND"
                + " status='PENDING' AND expires_at > CURRENT_TIMESTAMP",
            mediaId,
            userId);
    if (changed == 0) {
      Long alreadyUploaded =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM media_objects WHERE media_id=? AND user_id=? AND status='UPLOADED'",
              Long.class,
              mediaId,
              userId);
      if (alreadyUploaded == null || alreadyUploaded == 0) {
        throw new NotFoundException("上传票据不存在或已失效");
      }
    }
  }

  @Override
  public MealRecognitionJobDto createRecognitionJob(
      UUID userId, UUID mediaId, MealType mealType, Instant occurredAt) {
    Long uploaded =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM media_objects WHERE media_id=? AND user_id=? AND"
                + " status='UPLOADED'",
            Long.class,
            mediaId,
            userId);
    if (uploaded == null || uploaded == 0) throw new NotFoundException("图片尚未上传完成");
    UUID id = UUID.randomUUID();
    Instant at = occurredAt == null ? Instant.now() : occurredAt;
    jdbc.update(
        "INSERT INTO"
            + " meal_recognition_jobs(job_id,user_id,media_id,meal_type,occurred_at,status,candidates)"
            + " VALUES (?,?,?,?,?,'QUEUED','[]'::jsonb)",
        id,
        userId,
        mediaId,
        mealType.name(),
        Timestamp.from(at));
    return findRecognitionJob(userId, id).orElseThrow();
  }

  @Override
  public MealRecognitionJobDto updateRecognitionJob(
      ClaimedMealRecognitionJob job, MealRecognitionResult result) {
    if ("SUCCEEDED".equals(result.status()) && result.candidates().isEmpty()) {
      throw new IllegalArgumentException("SUCCEEDED recognition requires candidates");
    }
    int changed =
        jdbc.update(
            "UPDATE meal_recognition_jobs SET"
                + " status=?,candidates=?::jsonb,failure_code=?,failure_message=?,updated_at=CURRENT_TIMESTAMP"
                + " WHERE job_id=? AND status='RUNNING' AND updated_at=?",
            result.status(),
            json(result.candidates()),
            result.failureCode(),
            result.failureMessage(),
            job.jobId(),
            Timestamp.from(job.claimedAt()));
    if (changed != 1) {
      return findRecognitionJob(job.userId(), job.jobId())
          .orElseThrow(() -> new NotFoundException("识别任务不存在"));
    }
    return findRecognitionJob(job.userId(), job.jobId())
        .orElseThrow(() -> new NotFoundException("识别任务不存在"));
  }

  @Override
  public Optional<ClaimedMealRecognitionJob> claimNextRecognitionJob() {
    return jdbc
        .query(
            "WITH next AS (SELECT job_id FROM meal_recognition_jobs WHERE status='QUEUED' OR"
                + " (status='RUNNING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes') ORDER BY"
                + " created_at FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE meal_recognition_jobs j SET"
                + " status='RUNNING',updated_at=CURRENT_TIMESTAMP FROM next WHERE"
                + " j.job_id=next.job_id RETURNING"
                + " j.job_id,j.user_id,j.media_id,j.meal_type,j.occurred_at,j.updated_at AS claimed_at",
            (rs, row) ->
                new ClaimedMealRecognitionJob(
                    rs.getObject("job_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("media_id", UUID.class),
                    MealType.valueOf(rs.getString("meal_type")),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getTimestamp("claimed_at").toInstant()))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<MealRecognitionJobDto> findRecognitionJob(UUID userId, UUID jobId) {
    return jdbc
        .query(
            "SELECT"
                + " job_id,user_id,media_id,meal_type,occurred_at,status,candidates,failure_code,failure_message,created_at,updated_at"
                + " FROM meal_recognition_jobs WHERE user_id=? AND job_id=?",
            (rs, row) -> recognitionJob(rs),
            userId,
            jobId)
        .stream()
        .findFirst();
  }

  @Override
  public MealDto createMealRecord(UUID userId, CreateMealRecordRequest request) {
    UUID id = UUID.randomUUID();
    Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();
    jdbc.update(
        "INSERT INTO"
            + " meals(meal_id,user_id,occurred_at,meal_type,items,source,recognition_job_id,note)"
            + " VALUES (?,?,?,?,?::jsonb,?,?,?)",
        id,
        userId,
        Timestamp.from(occurredAt),
        request.mealType().name(),
        json(request.items()),
        request.source(),
        request.recognitionJobId(),
        request.note());
    Instant createdAt =
        jdbc.queryForObject("SELECT created_at FROM meals WHERE meal_id=?", Timestamp.class, id)
            .toInstant();
    return new MealDto(
        id,
        occurredAt,
        request.mealType(),
        List.copyOf(request.items()),
        request.source(),
        request.recognitionJobId(),
        request.note(),
        createdAt);
  }

  @Override
  public List<MealDto> listMealRecords(UUID userId) {
    return jdbc.query(
        "SELECT meal_id,occurred_at,meal_type,items,source,recognition_job_id,note,created_at FROM meals WHERE"
            + " user_id=? ORDER BY occurred_at DESC",
        (rs, row) -> meal(rs),
        userId);
  }

  @Override
  public MealRecommendationFeedbackDto upsertMealRecommendationFeedback(
      UUID userId, CreateMealRecommendationFeedbackRequest request) {
    Long owned =
        jdbc.queryForObject(
            "SELECT count(*) FROM daily_meal_recommendations WHERE recommendation_id=? AND user_id=?",
            Long.class,
            request.recommendationId(),
            userId);
    if (owned == null || owned == 0) throw new NotFoundException("饮食推荐不存在");
    return jdbc.queryForObject(
        "INSERT INTO meal_recommendation_feedback(user_id,recommendation_id,sentiment,reason,note) VALUES (?,?,?,?,?) ON CONFLICT (user_id,recommendation_id) DO UPDATE SET sentiment=EXCLUDED.sentiment,reason=EXCLUDED.reason,note=EXCLUDED.note,updated_at=CURRENT_TIMESTAMP RETURNING recommendation_id,sentiment AS feedback_sentiment,reason AS feedback_reason,note AS feedback_note,created_at AS feedback_created_at,updated_at AS feedback_updated_at",
        (rs, row) -> feedback(rs),
        userId,
        request.recommendationId(),
        request.sentiment().name(),
        request.reason() == null ? null : request.reason().name(),
        request.note() == null ? null : request.note().trim());
  }

  @Override
  public happy.jayden.yang.fitness.service.FitnessDtos.MealRecommendationFeedbackContext
      mealRecommendationFeedbackContext(UUID userId, Instant since) {
    List<String> liked = new ArrayList<>();
    List<String> disliked = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    jdbc.query(
        "SELECT f.sentiment,f.reason,f.note,d.items FROM meal_recommendation_feedback f JOIN"
            + " daily_meal_recommendations d ON d.recommendation_id=f.recommendation_id WHERE"
            + " f.user_id=? AND f.updated_at>=? ORDER BY f.updated_at DESC",
        rs -> {
          List<MealItemDto> items;
          try {
            items = objectMapper.readValue(rs.getString("items"), MEAL_ITEMS);
          } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid recommendation items JSON", exception);
          }
          List<String> target = "LIKE".equals(rs.getString("sentiment")) ? liked : disliked;
          items.stream()
              .map(MealItemDto::name)
              .map(name -> truncateCodePoints(name, MAX_CONTEXT_FOOD_LENGTH))
              .limit(8)
              .forEach(target::add);
          if (rs.getString("reason") != null) reasons.add(rs.getString("reason"));
          if (rs.getString("note") != null) {
            notes.add(truncateCodePoints(rs.getString("note").trim(), MAX_CONTEXT_NOTE_LENGTH));
          }
        },
        userId,
        Timestamp.from(since));
    return new happy.jayden.yang.fitness.service.FitnessDtos.MealRecommendationFeedbackContext(
        liked.stream().distinct().limit(30).toList(),
        disliked.stream().distinct().limit(30).toList(),
        reasons.stream().distinct().limit(12).toList(),
        notes.stream().distinct().limit(12).toList());
  }

  @Override
  public Optional<happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanStateDto>
      findDailyMealPlan(UUID userId, LocalDate date) {
    List<MealRecommendationDto> recommendations = dailyRecommendations(userId, date);
    List<happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanRunDto> runs =
        jdbc.query(
            "SELECT meal_plan_id,user_id,plan_date,status,generated_at,failure_code,failure_message,version,lease_token,lease_until"
                + " FROM daily_meal_plan_runs WHERE user_id=? AND plan_date=?",
            (rs, row) -> dailyMealPlanRun(rs),
            userId,
            date);
    if (!runs.isEmpty()) {
      return Optional.of(
          new happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanStateDto(
              runs.get(0), recommendations));
    }
    // V7 is deployed after V3: existing recommendation rows are already durable READY output.
    // Reading them must not invent a write-only run, but keeps old local/prod plans addressable.
    if (recommendations.size() == 3
        && recommendations.stream().allMatch(item -> "READY".equals(item.status()))) {
      Instant generatedAt =
          recommendations.stream()
              .map(MealRecommendationDto::generatedAt)
              .max(Instant::compareTo)
              .orElse(Instant.now());
      UUID legacyPlanId =
          UUID.nameUUIDFromBytes(
              ("daily-meal-plan:" + userId + ":" + date).getBytes(StandardCharsets.UTF_8));
      return Optional.of(
          new happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanStateDto(
              new happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanRunDto(
                  legacyPlanId, userId, date, "READY", generatedAt, null, null, 1, null, null),
              recommendations));
    }
    return Optional.empty();
  }

  @Override
  public happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanRunDto
      enqueueDailyMealPlanGeneration(UUID userId, LocalDate date) {
    List<happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanRunDto> enqueued =
        jdbc.query(
            "INSERT INTO daily_meal_plan_runs(meal_plan_id,user_id,plan_date,status) VALUES (?,?,?,'GENERATING')"
                + " ON CONFLICT (user_id,plan_date) DO UPDATE SET status='GENERATING',generated_at=NULL,"
                + " failure_code=NULL,failure_message=NULL,lease_token=NULL,lease_until=NULL,"
                + " version=daily_meal_plan_runs.version+1,updated_at=CURRENT_TIMESTAMP"
                + " WHERE daily_meal_plan_runs.status='FAILED' RETURNING"
                + " meal_plan_id,user_id,plan_date,status,generated_at,failure_code,failure_message,version,lease_token,lease_until",
            (rs, row) -> dailyMealPlanRun(rs),
            UUID.randomUUID(),
            userId,
            date);
    if (!enqueued.isEmpty()) return enqueued.get(0);
    return jdbc.queryForObject(
        "SELECT meal_plan_id,user_id,plan_date,status,generated_at,failure_code,failure_message,version,lease_token,lease_until"
            + " FROM daily_meal_plan_runs WHERE user_id=? AND plan_date=?",
        (rs, row) -> dailyMealPlanRun(rs),
        userId,
        date);
  }

  @Override
  public Optional<ClaimedDailyMealPlanRunDto> claimNextDailyMealPlanGeneration() {
    UUID leaseToken = UUID.randomUUID();
    return jdbc
        .query(
            "WITH candidate AS (SELECT meal_plan_id FROM daily_meal_plan_runs WHERE status='GENERATING'"
                + " AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP) ORDER BY created_at"
                + " FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE daily_meal_plan_runs r SET lease_token=?,"
                + " lease_until=CURRENT_TIMESTAMP + INTERVAL '2 minutes',version=r.version+1,updated_at=CURRENT_TIMESTAMP"
                + " FROM candidate WHERE r.meal_plan_id=candidate.meal_plan_id RETURNING"
                + " r.meal_plan_id,r.user_id,r.plan_date,r.status,r.generated_at,r.failure_code,r.failure_message,r.version,r.lease_token,r.lease_until",
            (rs, row) -> new ClaimedDailyMealPlanRunDto(dailyMealPlanRun(rs)),
            leaseToken)
        .stream()
        .findFirst();
  }

  @Override
  public boolean completeDailyMealPlanGeneration(
      ClaimedDailyMealPlanRunDto claim,
      happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanGenerationResult result) {
    if (!"SUCCEEDED".equals(result.status()) || result.recommendations() == null) {
      throw new IllegalArgumentException("只可持久化成功的三餐生成结果");
    }
    var run = claim.run();
    boolean owned =
        !jdbc.query(
                "SELECT meal_plan_id FROM daily_meal_plan_runs WHERE meal_plan_id=? AND status='GENERATING'"
                    + " AND version=? AND lease_token=? AND lease_until > CURRENT_TIMESTAMP FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class),
                run.mealPlanId(),
                run.version(),
                run.leaseToken())
            .isEmpty();
    if (!owned) return false;
    for (var recommendation : result.recommendations()) {
      jdbc.update(
          "INSERT INTO daily_meal_recommendations(recommendation_id,user_id,recommendation_date,meal_type,items,reason,status,generated_at)"
              + " VALUES (?,?,?,?,?::jsonb,?,'READY',CURRENT_TIMESTAMP) ON CONFLICT"
              + " (user_id,recommendation_date,meal_type) DO UPDATE SET items=EXCLUDED.items,"
              + " reason=EXCLUDED.reason,status='READY',generated_at=CURRENT_TIMESTAMP",
          UUID.randomUUID(),
          run.userId(),
          run.date(),
          recommendation.mealType().name(),
          json(recommendation.items()),
          recommendation.reason());
    }
    int changed =
        jdbc.update(
            "UPDATE daily_meal_plan_runs SET status='READY',generated_at=CURRENT_TIMESTAMP,"
                + " failure_code=NULL,failure_message=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP"
                + " WHERE meal_plan_id=? AND status='GENERATING' AND version=? AND lease_token=?"
                + " AND lease_until > CURRENT_TIMESTAMP",
            run.mealPlanId(),
            run.version(),
            run.leaseToken());
    return changed == 1;
  }

  @Override
  public boolean failDailyMealPlanGeneration(
      ClaimedDailyMealPlanRunDto claim, String failureCode, String failureMessage) {
    var run = claim.run();
    return jdbc.update(
            "UPDATE daily_meal_plan_runs SET status='FAILED',failure_code=?,failure_message=?,"
                + " lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE meal_plan_id=?"
                + " AND status='GENERATING' AND version=? AND lease_token=? AND lease_until > CURRENT_TIMESTAMP",
            failureCode,
            failureMessage,
            run.mealPlanId(),
            run.version(),
            run.leaseToken())
        == 1;
  }

  @Override
  public Optional<CurrentGoalReportRunDto> findCurrentGoalReport(UUID userId) {
    return jdbc
        .query(
            "SELECT r.report_id,r.user_id,r.goal_id,r.goal_version,"
                + " CASE WHEN r.state='READY' AND r.computed_through IS NOT NULL AND EXISTS (SELECT 1 FROM ("
                + " SELECT recorded_at AS event_at,created_at AS written_at FROM body_records WHERE user_id=r.user_id"
                + " UNION ALL SELECT occurred_at,created_at FROM meals WHERE user_id=r.user_id"
                + " UNION ALL SELECT completed_at,updated_at FROM workout_plans WHERE user_id=r.user_id AND completed_at IS NOT NULL"
                + " ) objective WHERE objective.event_at>=g.created_at AND objective.event_at<=CURRENT_TIMESTAMP"
                + " AND objective.written_at>r.computed_through) THEN 'STALE' ELSE r.state END AS state,"
                + " r.window_start,r.window_end,r.deterministic_snapshot::text,r.narrative::text,r.computed_through,"
                + " r.failure_code,r.failure_message,r.version,r.lease_token,r.lease_until,r.updated_at"
                + " FROM current_goal_reports r JOIN goals g ON g.goal_id=r.goal_id"
                + " WHERE r.user_id=? AND g.status='ACTIVE' ORDER BY g.created_at DESC LIMIT 1",
            (rs, row) -> currentGoalReportRun(rs),
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public CurrentGoalReportSourceData loadCurrentGoalReportSource(UUID userId, UUID goalId) {
    GoalState goal = currentGoal(userId, goalId);
    Instant start = goal.startedAt();
    Instant end = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant();
    List<BodyRecordDto> bodyRecords =
        jdbc.query(
            "SELECT body_record_id,recorded_at,weight_jin,waist_cm FROM body_records WHERE user_id=?"
                + " AND recorded_at>=? AND recorded_at<=? ORDER BY recorded_at",
            (rs, row) ->
                new BodyRecordDto(
                    rs.getObject("body_record_id", UUID.class),
                    rs.getTimestamp("recorded_at").toInstant(),
                    rs.getBigDecimal("weight_jin"),
                    rs.getBigDecimal("waist_cm")),
            userId,
            Timestamp.from(start),
            Timestamp.from(end));
    List<MealDto> meals =
        jdbc.query(
            "SELECT meal_id,occurred_at,meal_type,items,source,recognition_job_id,note,created_at FROM meals"
                + " WHERE user_id=? AND occurred_at>=? AND occurred_at<=? ORDER BY occurred_at",
            (rs, row) -> meal(rs),
            userId,
            Timestamp.from(start),
            Timestamp.from(end));
    List<CurrentGoalWorkoutRecord> workouts =
        jdbc.query(
            "SELECT w.completed_at,w.estimated_minutes,w.title,"
                + " COALESCE(jsonb_agg(DISTINCT e.target_area) FILTER (WHERE e.target_area IS NOT NULL),'[]'::jsonb)::text AS areas"
                + " FROM workout_plans w LEFT JOIN workout_plan_exercises pe ON pe.workout_plan_id=w.workout_plan_id"
                + " LEFT JOIN exercises e ON e.exercise_id=pe.exercise_id WHERE w.user_id=? AND w.status='COMPLETED'"
                + " AND w.completed_at>=? AND w.completed_at<=? GROUP BY w.workout_plan_id,w.completed_at,w.estimated_minutes,w.title"
                + " ORDER BY w.completed_at",
            (rs, row) ->
                new CurrentGoalWorkoutRecord(
                    rs.getTimestamp("completed_at").toInstant(),
                    rs.getInt("estimated_minutes"),
                    strings(rs.getString("areas")),
                    rs.getString("title")),
            userId,
            Timestamp.from(start),
            Timestamp.from(end));
    return new CurrentGoalReportSourceData(goal, end, bodyRecords, meals, workouts);
  }

  @Override
  public CurrentGoalReportRunDto enqueueCurrentGoalReport(UUID userId) {
    GoalState goal = currentGoal(userId, null);
    LocalDate windowStart = goal.startedAt().atZone(USER_ZONE).toLocalDate();
    LocalDate windowEnd = LocalDate.now(USER_ZONE);
    List<CurrentGoalReportRunDto> values =
        jdbc.query(
            "INSERT INTO current_goal_reports(report_id,user_id,goal_id,goal_version,state,window_start,window_end)"
                + " VALUES (?,?,?,?, 'QUEUED',?,?) ON CONFLICT (user_id,goal_id,goal_version) DO UPDATE SET"
                + " state=CASE WHEN current_goal_reports.state IN ('READY','STALE','FAILED') THEN 'QUEUED' ELSE current_goal_reports.state END,"
                + " deterministic_snapshot=CASE WHEN current_goal_reports.state IN ('READY','STALE','FAILED') THEN NULL ELSE current_goal_reports.deterministic_snapshot END,"
                + " narrative=CASE WHEN current_goal_reports.state IN ('READY','STALE','FAILED') THEN NULL ELSE current_goal_reports.narrative END,"
                + " computed_through=CASE WHEN current_goal_reports.state IN ('READY','STALE','FAILED') THEN NULL ELSE current_goal_reports.computed_through END,"
                + " failure_code=NULL,failure_message=NULL,lease_token=CASE WHEN current_goal_reports.state IN ('READY','STALE','FAILED') THEN NULL ELSE current_goal_reports.lease_token END,"
                + " lease_until=CASE WHEN current_goal_reports.state IN ('READY','STALE','FAILED') THEN NULL ELSE current_goal_reports.lease_until END,"
                + " version=CASE WHEN current_goal_reports.state IN ('READY','STALE','FAILED') THEN current_goal_reports.version+1 ELSE current_goal_reports.version END,"
                + " updated_at=CURRENT_TIMESTAMP RETURNING report_id,user_id,goal_id,goal_version,state,window_start,window_end,"
                + " deterministic_snapshot::text,narrative::text,computed_through,failure_code,failure_message,version,lease_token,lease_until,updated_at",
            (rs, row) -> currentGoalReportRun(rs),
            UUID.randomUUID(),
            userId,
            goal.id(),
            goal.version(),
            windowStart,
            windowEnd);
    return values.get(0);
  }

  @Override
  public Optional<ClaimedCurrentGoalReportRunDto> claimNextCurrentGoalReportGeneration() {
    UUID leaseToken = UUID.randomUUID();
    return jdbc
        .query(
            "WITH candidate AS (SELECT report_id FROM current_goal_reports WHERE state='QUEUED'"
                + " OR (state='GENERATING' AND lease_until < CURRENT_TIMESTAMP) ORDER BY created_at"
                + " FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE current_goal_reports r SET state='GENERATING',"
                + " lease_token=?,lease_until=CURRENT_TIMESTAMP + INTERVAL '2 minutes',version=r.version+1,updated_at=CURRENT_TIMESTAMP"
                + " FROM candidate WHERE r.report_id=candidate.report_id RETURNING r.report_id,r.user_id,r.goal_id,r.goal_version,r.state,"
                + " r.window_start,r.window_end,r.deterministic_snapshot::text,r.narrative::text,r.computed_through,r.failure_code,"
                + " r.failure_message,r.version,r.lease_token,r.lease_until,r.updated_at",
            (rs, row) -> new ClaimedCurrentGoalReportRunDto(currentGoalReportRun(rs)),
            leaseToken)
        .stream()
        .findFirst();
  }

  @Override
  public boolean completeCurrentGoalReportGeneration(
      ClaimedCurrentGoalReportRunDto claim,
      CurrentGoalReportFacts facts,
      CurrentGoalReportGenerationResult result,
      Instant computedThrough) {
    if (!"SUCCEEDED".equals(result.status()) || result.narrative() == null) {
      throw new IllegalArgumentException("只可持久化成功的当前目标报告");
    }
    CurrentGoalReportRunDto run = claim.run();
    return jdbc.update(
            "UPDATE current_goal_reports SET state='READY',window_start=?,window_end=?,deterministic_snapshot=?::jsonb,narrative=?::jsonb,"
                + " computed_through=?,failure_code=NULL,failure_message=NULL,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP"
                + " WHERE report_id=? AND state='GENERATING' AND version=? AND lease_token=? AND lease_until > CURRENT_TIMESTAMP",
            facts.windowStart(),
            facts.windowEnd(),
            json(facts),
            json(result.narrative()),
            Timestamp.from(computedThrough),
            run.reportId(),
            run.version(),
            run.leaseToken())
        == 1;
  }

  @Override
  public boolean failCurrentGoalReportGeneration(
      ClaimedCurrentGoalReportRunDto claim, String failureCode, String failureMessage) {
    CurrentGoalReportRunDto run = claim.run();
    return jdbc.update(
            "UPDATE current_goal_reports SET state='FAILED',failure_code=?,failure_message=?,lease_token=NULL,"
                + " lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE report_id=? AND state='GENERATING'"
                + " AND version=? AND lease_token=? AND lease_until > CURRENT_TIMESTAMP",
            failureCode,
            failureMessage,
            run.reportId(),
            run.version(),
            run.leaseToken())
        == 1;
  }

  @Override
  public List<UUID> activeUserIds() {
    return jdbc.query(
        "SELECT user_id FROM users WHERE status='ACTIVE' ORDER BY user_id",
        (rs, row) -> rs.getObject("user_id", UUID.class));
  }

  private static String truncateCodePoints(String value, int maximum) {
    if (value.codePointCount(0, value.length()) <= maximum) return value;
    return value.substring(0, value.offsetByCodePoints(0, maximum));
  }

  private List<MealRecommendationDto> dailyRecommendations(UUID userId, LocalDate date) {
    return jdbc.query(
        "SELECT d.recommendation_id,d.recommendation_date,d.meal_type,d.items,d.reason,d.status,d.generated_at,"
            + " f.sentiment AS feedback_sentiment,f.reason AS feedback_reason,f.note AS feedback_note,"
            + " f.created_at AS feedback_created_at,f.updated_at AS feedback_updated_at"
            + " FROM daily_meal_recommendations d LEFT JOIN meal_recommendation_feedback f"
            + " ON f.recommendation_id=d.recommendation_id AND f.user_id=d.user_id"
            + " WHERE d.user_id=? AND d.recommendation_date=? ORDER BY CASE d.meal_type"
            + " WHEN 'BREAKFAST' THEN 1 WHEN 'LUNCH' THEN 2 ELSE 3 END",
        (rs, row) -> mealRecommendation(rs),
        userId,
        date);
  }

  @Override
  public Optional<IdempotencyEntry> findIdempotency(UUID userId, String operation, String key) {
    return jdbc
        .query(
            "SELECT resource_id,request_hash,response_json::text FROM fitness_idempotency_keys"
                + " WHERE user_id=? AND operation=? AND idempotency_key=?",
            (rs, row) ->
                new IdempotencyEntry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            userId,
            operation,
            key)
        .stream()
        .findFirst();
  }

  @Override
  public void saveIdempotency(
      UUID userId,
      String operation,
      String key,
      String requestHash,
      UUID resourceId,
      String responseJson) {
    try {
      jdbc.update(
          "INSERT INTO"
              + " fitness_idempotency_keys(user_id,operation,idempotency_key,request_hash,resource_id,response_json)"
              + " VALUES (?,?,?,?,?,?::jsonb)",
          userId,
          operation,
          key,
          requestHash,
          resourceId,
          responseJson);
    } catch (DuplicateKeyException exception) {
      throw new IdempotencyConcurrencyException(exception);
    }
  }

  @Override
  public WorkoutCompletionDto completeWorkout(
      UUID userId, UUID workoutId, CompleteWorkoutRequest request) {
    int changed =
        jdbc.update(
            "UPDATE workout_plans SET"
                + " status='COMPLETED',completion_ratio=?,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE"
                + " workout_plan_id=? AND user_id=? AND status<>'COMPLETED'",
            request.completionRatio(),
            workoutId,
            userId);
    if (changed == 0) {
      return jdbc
          .query(
              "SELECT workout_plan_id,status,completion_ratio FROM workout_plans WHERE"
                  + " workout_plan_id=? AND user_id=?",
              (rs, row) ->
                  new WorkoutCompletionDto(
                      rs.getObject("workout_plan_id", UUID.class),
                      rs.getString("status"),
                      rs.getBigDecimal("completion_ratio")),
              workoutId,
              userId)
          .stream()
          .findFirst()
          .orElseThrow(() -> new NotFoundException("训练计划不存在"));
    }
    return new WorkoutCompletionDto(workoutId, "COMPLETED", request.completionRatio());
  }

  @Override
  public GoalState createGoal(UUID userId, CreateGoalRequest request) {
    BigDecimal current =
        jdbc
            .query(
                "SELECT weight_jin FROM body_records WHERE user_id=? AND weight_jin IS NOT NULL"
                    + " ORDER BY recorded_at DESC LIMIT 1",
                (rs, row) -> rs.getBigDecimal(1),
                userId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new NotFoundException("请先记录体重"));
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " goals(goal_id,user_id,name,start_weight_jin,target_weight_jin,target_date,status)"
            + " VALUES (?,?,?,?,?,?,'ACTIVE')",
        id,
        userId,
        request.name().trim(),
        current,
        request.targetWeightJin(),
        request.targetDate());
    return new GoalState(
        id,
        request.name().trim(),
        current,
        request.targetWeightJin(),
        "ACTIVE",
        1,
        jdbc.queryForObject("SELECT created_at FROM goals WHERE goal_id=?", Timestamp.class, id)
            .toInstant());
  }

  @Override
  public BootstrapData loadForAi(UUID userId) {
    return loadBootstrap(userId, LocalDate.now(USER_ZONE));
  }

  public void seedLocalExperience(String passwordHash) {
    UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    jdbc.update(
        "INSERT INTO users(user_id,external_subject,status,username,password_hash,nickname) VALUES"
            + " (?,'local:user','ACTIVE','user',?,'小秦') ON CONFLICT (external_subject) DO UPDATE"
            + " SET username='user',password_hash=EXCLUDED.password_hash,nickname='小秦',status='ACTIVE'",
        userId,
        passwordHash);
    jdbc.update(
        "INSERT INTO"
            + " goals(goal_id,user_id,name,start_weight_jin,target_weight_jin,target_date,status,created_at)"
            + " VALUES ('20000000-0000-0000-0000-000000000001',?,'8周减脂入门',160,140,CURRENT_DATE +"
            + " 56,'ACTIVE',CURRENT_TIMESTAMP - INTERVAL '8 weeks') ON CONFLICT DO NOTHING",
        userId);

    seedExercises();
    for (int week = 1; week <= 8; week++) {
      LocalDate weekDate = LocalDate.now(USER_ZONE).minusWeeks(8L - week);
      String suffix = String.format("%012d", week);
      jdbc.update(
          "INSERT INTO body_records(body_record_id,user_id,recorded_at,weight_jin,waist_cm) VALUES"
              + " (?::uuid,?,?,?,?) ON CONFLICT DO NOTHING",
          "30000000-0000-0000-0000-" + suffix,
          userId,
          Timestamp.from(weekDate.atStartOfDay(USER_ZONE).toInstant()),
          BigDecimal.valueOf(157L - week),
          BigDecimal.valueOf(91L - week));
      jdbc.update(
          "INSERT INTO meals(meal_id,user_id,occurred_at,meal_type,items) VALUES"
              + " (?::uuid,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
          "40000000-0000-0000-0000-" + suffix,
          userId,
          Timestamp.from(weekDate.atTime(0, 1).atZone(USER_ZONE).toInstant()),
          week % 2 == 0 ? "LUNCH" : "DINNER",
          "[{\"name\":\"第" + week + "周均衡餐\",\"estimatedKcal\":520}]");
      String planId = "50000000-0000-0000-0000-" + suffix;
      jdbc.update(
          "INSERT INTO"
              + " workout_plans(workout_plan_id,user_id,title,estimated_minutes,status,scheduled_for)"
              + " VALUES (?::uuid,?,'全身燃脂训练',28,'PLANNED',?) ON CONFLICT DO NOTHING",
          planId,
          userId,
          weekDate);
      for (int exercise = 1; exercise <= 4; exercise++) {
        jdbc.update(
            "INSERT INTO workout_plan_exercises(workout_plan_id,exercise_id,display_order) VALUES"
                + " (?::uuid,?::uuid,?) ON CONFLICT DO NOTHING",
            planId,
            "60000000-0000-0000-0000-" + String.format("%012d", exercise),
            exercise);
      }
    }
    seedTodayPlan(userId);
    seedMealRecommendation(
        userId,
        "BREAKFAST",
        List.of(new MealItemDto("燕麦酸奶莓果杯", 360), new MealItemDto("水煮蛋", 75)),
        "早餐补足蛋白质和慢碳水，让上午更稳。");
    seedMealRecommendation(
        userId,
        "LUNCH",
        List.of(new MealItemDto("番茄牛肉荞麦面", 480), new MealItemDto("清炒时蔬", 110)),
        "午餐保留主食，搭配牛肉和蔬菜更耐饿。");
    seedMealRecommendation(
        userId,
        "DINNER",
        List.of(new MealItemDto("香煎鸡胸南瓜碗", 420), new MealItemDto("菌菇豆腐汤", 120)),
        "晚餐清淡但不空腹，照顾训练后的恢复。");
  }

  private void seedMealRecommendation(
      UUID userId, String mealType, List<MealItemDto> items, String reason) {
    LocalDate recommendationDate = LocalDate.now(USER_ZONE);
    UUID recommendationId =
        UUID.nameUUIDFromBytes(
            ("local-meal-recommendation-" + recommendationDate + "-" + mealType)
                .getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        "INSERT INTO"
            + " daily_meal_recommendations(recommendation_id,user_id,recommendation_date,meal_type,items,reason,status,generated_at)"
            + " VALUES (?::uuid,?,?,?,?::jsonb,?,'READY',CURRENT_TIMESTAMP) ON CONFLICT"
            + " (user_id,recommendation_date,meal_type) DO UPDATE SET"
            + " items=EXCLUDED.items,reason=EXCLUDED.reason,status='READY',generated_at=CURRENT_TIMESTAMP",
        recommendationId,
        userId,
        recommendationDate,
        mealType,
        json(items),
        reason);
  }

  private void seedTodayPlan(UUID userId) {
    LocalDate today = LocalDate.now(USER_ZONE);
    Long existing =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM workout_plans WHERE user_id=? AND scheduled_for=?",
            Long.class,
            userId,
            today);
    if (existing != null && existing > 0) {
      return;
    }
    UUID planId =
        UUID.nameUUIDFromBytes(("local-workout-plan-" + today).getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        "INSERT INTO"
            + " workout_plans(workout_plan_id,user_id,title,estimated_minutes,status,scheduled_for)"
            + " VALUES (?,?,? ,28,'PLANNED',?)",
        planId,
        userId,
        "全身燃脂训练",
        today);
    for (int exercise = 1; exercise <= 4; exercise++) {
      jdbc.update(
          "INSERT INTO workout_plan_exercises(workout_plan_id,exercise_id,display_order) VALUES"
              + " (?,?::uuid,?) ON CONFLICT DO NOTHING",
          planId,
          "60000000-0000-0000-0000-" + String.format("%012d", exercise),
          exercise);
    }
  }

  private void seedExercises() {
    seedExercise(
        1,
        "深蹲",
        "腿部与臀部",
        3,
        45,
        List.of("双脚与肩同宽", "臀部向后坐", "膝盖与脚尖同向", "站起收紧臀部"),
        List.of("膝盖内扣", "塌腰"));
    seedExercise(
        2,
        "跪姿俯卧撑",
        "胸肩与手臂",
        3,
        40,
        List.of("双手略宽于肩", "身体保持直线", "屈肘下降", "推起回到起点"),
        List.of("耸肩", "腰部下沉"));
    seedExercise(
        3,
        "登山跑",
        "核心与心肺",
        4,
        30,
        List.of("进入高位平板", "右膝提向胸口", "换左膝提向胸口", "保持节奏交替"),
        List.of("臀部过高", "脚步落地过重"));
    seedExercise(
        4,
        "臀桥",
        "臀腿与核心",
        3,
        45,
        List.of("仰卧屈膝", "脚跟踩稳", "抬臀至肩髋膝一线", "控制下落"),
        List.of("过度挺腰", "膝盖外翻"));
  }

  private void seedExercise(
      int number,
      String name,
      String area,
      int sets,
      int seconds,
      List<String> steps,
      List<String> errors) {
    String id = "60000000-0000-0000-0000-" + String.format("%012d", number);
    List<String> images = new ArrayList<>();
    for (int step = 1; step <= 4; step++) {
      images.add(
          "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='320'"
              + " height='240'%3E%3Crect width='100%25' height='100%25' fill='%23eef2ff'/%3E%3Ctext"
              + " x='50%25' y='50%25' text-anchor='middle' fill='%23312e81'%3E"
              + name
              + " 第"
              + step
              + "步示意%3C/text%3E%3C/svg%3E");
    }
    jdbc.update(
        "INSERT INTO exercises(exercise_id,name,target_area,sets,seconds,steps,errors,image_urls)"
            + " VALUES (?::uuid,?,?,?,?,?::jsonb,?::jsonb,?::jsonb) ON CONFLICT DO NOTHING",
        id,
        name,
        area,
        sets,
        seconds,
        json(steps),
        json(errors),
        json(images));
  }

  private GoalState latestGoal(UUID userId) {
    return required(
        "SELECT goal_id,name,start_weight_jin,target_weight_jin,status,version,created_at FROM goals WHERE user_id=?"
            + " ORDER BY created_at DESC LIMIT 1",
        (rs, row) ->
            new GoalState(
                rs.getObject("goal_id", UUID.class),
                rs.getString("name"),
                rs.getBigDecimal("start_weight_jin"),
                rs.getBigDecimal("target_weight_jin"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant()),
        userId);
  }

  private GoalState currentGoal(UUID userId, UUID requestedGoalId) {
    String where = requestedGoalId == null ? "" : " AND goal_id=?";
    List<GoalState> values =
        jdbc.query(
            "SELECT goal_id,name,start_weight_jin,target_weight_jin,status,version,created_at FROM goals"
                + " WHERE user_id=? AND status='ACTIVE'"
                + where
                + " ORDER BY created_at DESC LIMIT 1",
            (rs, row) ->
                new GoalState(
                    rs.getObject("goal_id", UUID.class),
                    rs.getString("name"),
                    rs.getBigDecimal("start_weight_jin"),
                    rs.getBigDecimal("target_weight_jin"),
                    rs.getString("status"),
                    rs.getInt("version"),
                    rs.getTimestamp("created_at").toInstant()),
            requestedGoalId == null
                ? new Object[] {userId}
                : new Object[] {userId, requestedGoalId});
    return values.stream().findFirst().orElseThrow(() -> new NotFoundException("当前目标不存在"));
  }

  private PlanDto planForDate(UUID userId, LocalDate scheduledFor) {
    PlanDto base =
        jdbc
            .query(
                "SELECT workout_plan_id,title,estimated_minutes,status FROM workout_plans WHERE"
                    + " user_id=? AND scheduled_for=? ORDER BY CASE status WHEN 'PLANNED' THEN 0"
                    + " ELSE 1 END, workout_plan_id LIMIT 1",
                (rs, row) ->
                    new PlanDto(
                        rs.getObject("workout_plan_id", UUID.class),
                        rs.getString("title"),
                        rs.getInt("estimated_minutes"),
                        rs.getString("status"),
                        List.of()),
                userId,
                scheduledFor)
            .stream()
            .findFirst()
            .orElse(null);
    if (base == null) {
      return null;
    }
    List<PlanExerciseDto> exercises =
        jdbc.query(
            "SELECT e.exercise_id,e.name,e.target_area,e.sets,e.seconds,e.steps,e.errors FROM"
                + " workout_plan_exercises pe JOIN exercises e ON e.exercise_id=pe.exercise_id"
                + " WHERE pe.workout_plan_id=? ORDER BY pe.display_order",
            (rs, row) ->
                new PlanExerciseDto(
                    rs.getObject("exercise_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("target_area"),
                    rs.getInt("sets"),
                    rs.getInt("seconds"),
                    strings(rs.getString("steps")),
                    strings(rs.getString("errors"))),
            base.id());
    return new PlanDto(base.id(), base.title(), base.estimatedMinutes(), base.status(), exercises);
  }

  private List<ExerciseDto> exerciseDetails() {
    return jdbc.query(
        "SELECT exercise_id,name,target_area,sets,seconds,steps,errors,image_urls FROM exercises"
            + " ORDER BY name",
        (rs, row) ->
            new ExerciseDto(
                rs.getObject("exercise_id", UUID.class),
                rs.getString("name"),
                rs.getString("target_area"),
                rs.getInt("sets"),
                rs.getInt("seconds"),
                strings(rs.getString("steps")),
                strings(rs.getString("errors")),
                "FOUR_STEP_IMAGES",
                strings(rs.getString("image_urls"))));
  }

  private MealDto meal(ResultSet rs) throws SQLException {
    try {
      return new MealDto(
          rs.getObject("meal_id", UUID.class),
          rs.getTimestamp("occurred_at").toInstant(),
          happy.jayden.yang.fitness.service.FitnessDtos.MealType.valueOf(rs.getString("meal_type")),
          objectMapper.readValue(rs.getString("items"), MEAL_ITEMS),
          rs.getString("source"),
          rs.getObject("recognition_job_id", UUID.class),
          rs.getString("note"),
          rs.getTimestamp("created_at").toInstant());
    } catch (JsonProcessingException exception) {
      throw new SQLException("Invalid meal items JSON", exception);
    }
  }

  private happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanRunDto dailyMealPlanRun(
      ResultSet rs) throws SQLException {
    Timestamp generatedAt = rs.getTimestamp("generated_at");
    return new happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanRunDto(
        rs.getObject("meal_plan_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("plan_date", LocalDate.class),
        rs.getString("status"),
        generatedAt == null ? null : generatedAt.toInstant(),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getInt("version"),
        rs.getObject("lease_token", UUID.class),
        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant());
  }

  private CurrentGoalReportRunDto currentGoalReportRun(ResultSet rs) throws SQLException {
    try {
      Timestamp computedThrough = rs.getTimestamp("computed_through");
      Timestamp leaseUntil = rs.getTimestamp("lease_until");
      return new CurrentGoalReportRunDto(
          rs.getObject("report_id", UUID.class),
          rs.getObject("user_id", UUID.class),
          rs.getObject("goal_id", UUID.class),
          rs.getInt("goal_version"),
          rs.getString("state"),
          rs.getObject("window_start", LocalDate.class),
          rs.getObject("window_end", LocalDate.class),
          rs.getString("deterministic_snapshot") == null
              ? null
              : objectMapper.readValue(
                  rs.getString("deterministic_snapshot"), CurrentGoalReportFacts.class),
          rs.getString("narrative") == null
              ? null
              : objectMapper.readValue(rs.getString("narrative"), CurrentGoalReportNarrative.class),
          computedThrough == null ? null : computedThrough.toInstant(),
          rs.getString("failure_code"),
          rs.getString("failure_message"),
          rs.getInt("version"),
          rs.getObject("lease_token", UUID.class),
          leaseUntil == null ? null : leaseUntil.toInstant(),
          rs.getTimestamp("updated_at").toInstant());
    } catch (JsonProcessingException exception) {
      throw new SQLException("Invalid current goal report JSON", exception);
    }
  }

  private MealRecognitionJobDto recognitionJob(ResultSet rs) throws SQLException {
    try {
      return new MealRecognitionJobDto(
          rs.getObject("job_id", UUID.class),
          rs.getString("status"),
          rs.getObject("media_id", UUID.class),
          MealType.valueOf(rs.getString("meal_type")),
          rs.getTimestamp("occurred_at").toInstant(),
          objectMapper.readValue(rs.getString("candidates"), CANDIDATES),
          rs.getString("failure_code"),
          rs.getString("failure_message"),
          rs.getTimestamp("created_at").toInstant(),
          rs.getTimestamp("updated_at").toInstant());
    } catch (JsonProcessingException exception) {
      throw new SQLException("Invalid recognition candidates JSON", exception);
    }
  }

  private MealRecommendationDto mealRecommendation(ResultSet rs) throws SQLException {
    try {
      return new MealRecommendationDto(
          rs.getObject("recommendation_id", UUID.class),
          rs.getObject("recommendation_date", LocalDate.class),
          happy.jayden.yang.fitness.service.FitnessDtos.MealType.valueOf(rs.getString("meal_type")),
          objectMapper.readValue(rs.getString("items"), MEAL_ITEMS),
          rs.getString("reason"),
          rs.getString("status"),
          rs.getTimestamp("generated_at").toInstant(),
          rs.getString("feedback_sentiment") == null ? null : feedback(rs));
    } catch (JsonProcessingException exception) {
      throw new SQLException("Invalid recommendation items JSON", exception);
    }
  }

  private MealRecommendationFeedbackDto feedback(ResultSet rs) throws SQLException {
    return new MealRecommendationFeedbackDto(
        rs.getObject("recommendation_id", UUID.class),
        Sentiment.valueOf(rs.getString("feedback_sentiment")),
        rs.getString("feedback_reason") == null
            ? null
            : FeedbackReason.valueOf(rs.getString("feedback_reason")),
        rs.getString("feedback_note"),
        rs.getTimestamp("feedback_created_at").toInstant(),
        rs.getTimestamp("feedback_updated_at").toInstant());
  }

  private List<String> strings(String json) throws SQLException {
    try {
      return objectMapper.readValue(json, STRINGS);
    } catch (JsonProcessingException exception) {
      throw new SQLException("Invalid JSON string list", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Value cannot be serialized", exception);
    }
  }

  private <T> T required(
      String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    try {
      return jdbc.queryForObject(sql, mapper, args);
    } catch (EmptyResultDataAccessException exception) {
      throw new NotFoundException("体验数据尚未初始化");
    }
  }
}
