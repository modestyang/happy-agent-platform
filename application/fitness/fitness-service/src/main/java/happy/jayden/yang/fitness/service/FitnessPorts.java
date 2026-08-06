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
