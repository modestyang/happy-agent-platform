package happy.jayden.yang.fitness.service;

import happy.jayden.yang.fitness.service.FitnessDtos.AiMessageResponse;
import happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapData;
import happy.jayden.yang.fitness.service.FitnessDtos.CompleteWorkoutRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateGoalRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.GoalState;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginAccount;
import happy.jayden.yang.fitness.service.FitnessDtos.MealDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionJobDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
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

    void createSession(String sessionTokenHash, UUID userId, Instant expiresAt);

    Optional<UUID> findSessionUser(String sessionTokenHash, Instant now);

    void revokeSession(String sessionTokenHash);

    BootstrapData loadBootstrap(UUID userId, LocalDate recommendationDate);

    BodyRecordDto createBodyRecord(UUID userId, CreateBodyRecordRequest request);

    MealDto createMeal(UUID userId, CreateMealRequest request);

    WorkoutCompletionDto completeWorkout(
        UUID userId, UUID workoutId, CompleteWorkoutRequest request);

    GoalState createGoal(UUID userId, CreateGoalRequest request);

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

  public interface PasswordVerifier {
    boolean matches(String rawPassword, String passwordHash);
  }

  public interface AgentProviderStatus {
    boolean configured();
  }

  public interface AiConversation {
    AiMessageResponse send(UUID userId, String message);
  }
}
