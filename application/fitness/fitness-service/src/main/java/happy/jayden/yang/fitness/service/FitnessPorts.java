package happy.jayden.yang.fitness.service;

import happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapData;
import happy.jayden.yang.fitness.service.FitnessDtos.ClaimedCurrentGoalReportRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.ClaimedDailyMealPlanRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.CompleteWorkoutRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateGoalRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportFacts;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportGenerationResult;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.CurrentGoalReportSourceData;
import happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanGenerationResult;
import happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanRunDto;
import happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanStateDto;
import happy.jayden.yang.fitness.service.FitnessDtos.FirstSetupRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.GoalState;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginAccount;
import happy.jayden.yang.fitness.service.FitnessDtos.MealDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionJobDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.SaveTrainingPlanRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.TrainingProfileDto;
import happy.jayden.yang.fitness.service.FitnessDtos.TrainingProfileInput;
import happy.jayden.yang.fitness.service.FitnessDtos.UploadedMedia;
import happy.jayden.yang.fitness.service.FitnessDtos.WorkoutCompletionDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public final class FitnessPorts {

  private FitnessPorts() {}

  public interface FitnessStore {
    Optional<LoginAccount> findLoginAccount(String username);

    UUID createLoginAccount(String username, String nickname, String passwordHash);

    void createSession(String sessionTokenHash, UUID userId, Instant expiresAt);

    Optional<UUID> findSessionUser(String sessionTokenHash, Instant now);

    void revokeSession(String sessionTokenHash);

    BootstrapData loadBootstrap(UUID userId, LocalDate recommendationDate);

    Optional<FitnessDtos.PlanDto> findTrainingPlan(UUID userId, UUID workoutPlanId);

    BodyRecordDto createBodyRecord(UUID userId, CreateBodyRecordRequest request);

    MealDto createMeal(UUID userId, CreateMealRequest request);

    WorkoutCompletionDto completeWorkout(
        UUID userId, UUID workoutId, CompleteWorkoutRequest request);

    java.util.List<UUID> saveTrainingPlan(UUID userId, SaveTrainingPlanRequest request);

    GoalState createGoal(UUID userId, CreateGoalRequest request);

    void completeFirstSetup(UUID userId, FirstSetupRequest request);

    TrainingProfileDto updateTrainingProfile(UUID userId, TrainingProfileInput request);

    BootstrapData loadForAi(UUID userId);

    void markMediaUploaded(UUID userId, UUID mediaId);

    MealRecognitionJobDto createRecognitionJob(
        UUID userId, UUID mediaId, MealType mealType, Instant occurredAt);

    /** Completes only the durable claim represented by {@code job}. */
    MealRecognitionJobDto updateRecognitionJob(
        FitnessDtos.ClaimedMealRecognitionJob job, MealRecognitionResult result);

    Optional<FitnessDtos.ClaimedMealRecognitionJob> claimNextRecognitionJob();

    Optional<MealRecognitionJobDto> findRecognitionJob(UUID userId, UUID jobId);

    MealDto createMealRecord(UUID userId, FitnessDtos.CreateMealRecordRequest request);

    java.util.List<MealDto> listMealRecords(UUID userId);

    FitnessDtos.MealRecommendationFeedbackDto upsertMealRecommendationFeedback(
        UUID userId, FitnessDtos.CreateMealRecommendationFeedbackRequest request);

    FitnessDtos.MealRecommendationFeedbackContext mealRecommendationFeedbackContext(
        UUID userId, Instant since);

    /** Reads the durable state and the persisted recommendation rows for one local date. */
    Optional<DailyMealPlanStateDto> findDailyMealPlan(UUID userId, LocalDate date);

    /**
     * Enqueues an absent or failed plan. READY and currently GENERATING rows are returned without
     * changing their owner/version, so concurrent requests reuse one durable run.
     */
    DailyMealPlanRunDto enqueueDailyMealPlanGeneration(UUID userId, LocalDate date);

    /** Atomically leases one unclaimed or stale GENERATING run to exactly one worker. */
    Optional<ClaimedDailyMealPlanRunDto> claimNextDailyMealPlanGeneration();

    /** Returns false when a newer lease has fenced this worker out. */
    boolean completeDailyMealPlanGeneration(
        ClaimedDailyMealPlanRunDto claim, DailyMealPlanGenerationResult result);

    /** Returns false when a newer lease has fenced this worker out. */
    boolean failDailyMealPlanGeneration(
        ClaimedDailyMealPlanRunDto claim, String failureCode, String failureMessage);

    /**
     * Returns the active goal's durable report; READY is read as STALE when objective data moved
     * on.
     */
    Optional<CurrentGoalReportRunDto> findCurrentGoalReport(UUID userId);

    /**
     * Reads objective facts only by the current goal's time window, never by a goal_id on records.
     */
    CurrentGoalReportSourceData loadCurrentGoalReportSource(UUID userId, UUID goalId);

    /** Creates or atomically re-queues the one report identified by (user, goal, goal version). */
    CurrentGoalReportRunDto enqueueCurrentGoalReport(UUID userId);

    /** Atomically leases one QUEUED report or an expired GENERATING lease. */
    Optional<ClaimedCurrentGoalReportRunDto> claimNextCurrentGoalReportGeneration();

    /** Returns false when the claim was fenced by a newer worker or an expired lease. */
    boolean completeCurrentGoalReportGeneration(
        ClaimedCurrentGoalReportRunDto claim,
        CurrentGoalReportFacts facts,
        CurrentGoalReportGenerationResult result,
        Instant computedThrough);

    /** Returns false when the claim was fenced by a newer worker or an expired lease. */
    boolean failCurrentGoalReportGeneration(
        ClaimedCurrentGoalReportRunDto claim, String failureCode, String failureMessage);

    java.util.List<UUID> activeUserIds();

    void recordUserActivity(UUID userId, Instant occurredAt);

    java.util.List<UUID> dailyMealPlanEligibleUserIds(Instant activeSince, LocalDate planDate);

    Optional<FitnessDtos.IdempotencyEntry> findIdempotency(
        UUID userId, String operation, String key);

    void saveIdempotency(
        UUID userId,
        String operation,
        String key,
        String requestHash,
        UUID resourceId,
        String responseJson);
  }

  /** Bounded, read-only facts exposed exclusively to Fitness Agent tools. */
  public interface FitnessAgentReadStore {
    Optional<FitnessAgentDtos.UserProfileFact> findUserProfile(UUID userId);

    Optional<FitnessAgentDtos.GoalFact> findCurrentGoal(UUID userId);

    Optional<FitnessAgentDtos.BodyMetricFact> findLatestWeight(UUID userId);

    Optional<FitnessAgentDtos.BodyMetricFact> findLatestWaist(UUID userId);

    FitnessAgentDtos.RecordPage<FitnessAgentDtos.BodyRecordFact> findBodyRecords(
        UUID userId, Instant fromInclusive, Instant toExclusive, int limit);

    FitnessAgentDtos.RecordPage<FitnessAgentDtos.WorkoutFact> findWorkouts(
        UUID userId, LocalDate fromInclusive, LocalDate toInclusive, int limit);

    FitnessAgentDtos.RecordPage<FitnessAgentDtos.MealFact> findMeals(
        UUID userId, Instant fromInclusive, Instant toExclusive, int limit);

    FitnessAgentDtos.RecordPage<FitnessAgentDtos.ExerciseFact> searchExercises(
        String keyword, String targetArea, int limit);

    FitnessAgentDtos.ExerciseCandidatePage findExerciseCandidates(
        FitnessAgentDtos.ExerciseCandidateFilter filter);

    java.util.List<FitnessAgentDtos.ExerciseFact> findExercises(java.util.List<UUID> exerciseIds);

    FitnessAgentDtos.MealRecommendationStateFact findMealRecommendations(
        UUID userId, LocalDate date);

    FitnessAgentDtos.MealFeedbackFact findMealFeedback(UUID userId, Instant since);
  }

  public interface MediaUploadPort {
    MediaUploadTicket createTicket(
        UUID userId, String contentType, long contentLength, String sha256);

    /** Verifies that the direct-upload target contains the exact ticketed object. */
    default void verifyUploaded(UUID userId, UUID mediaId) {
      throw new UnsupportedOperationException("媒体存储不支持上传确认");
    }

    /** Reads an already verified object for the asynchronous recognition worker. */
    default UploadedMedia readUploaded(UUID mediaId) {
      throw new UnsupportedOperationException("媒体存储不支持读取");
    }
  }

  /** Infrastructure-supplied transaction boundary; use cases, not HTTP adapters, own it. */
  public interface TransactionRunner {
    <T> T inTransaction(TransactionWork<T> work);
  }

  @FunctionalInterface
  public interface TransactionWork<T> {
    T run();
  }

  public interface MealRecognitionPort {
    MealRecognitionResult recognize(
        UUID userId, UUID mediaId, MealType mealType, Instant occurredAt);
  }

  /** Isolated adapter boundary for a Skill-selected daily meal background Agent task. */
  public interface DailyMealPlanGenerationPort {
    DailyMealPlanGenerationResult generate(UUID userId, LocalDate date);
  }

  /** Isolated, non-conversational Agent boundary for the report's narrative-only JSON result. */
  public interface CurrentGoalReportGenerationPort {
    CurrentGoalReportGenerationResult generate(CurrentGoalReportFacts facts);
  }

  public interface PasswordVerifier {
    boolean matches(String rawPassword, String passwordHash);

    String hash(String rawPassword);
  }

  public interface AgentProviderStatus {
    boolean configured();
  }
}
