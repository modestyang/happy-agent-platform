package happy.jayden.yang.fitness;

import static happy.jayden.yang.fitness.LocalAuthController.SESSION_COOKIE;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.AiMessageRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.AiMessageResponse;
import happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapDto;
import happy.jayden.yang.fitness.service.FitnessDtos.CompleteWorkoutRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateGoalRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecognitionJobRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMediaUploadTicketRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.GoalDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionJobDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessDtos.WorkoutCompletionDto;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app")
public class FitnessAppController {

  private final FitnessApplicationService application;
  private final MediaUploadPort mediaUploadPort;

  public FitnessAppController(FitnessApplicationService application, MediaUploadPort mediaUploadPort) {
    this.application = application;
    this.mediaUploadPort = mediaUploadPort;
  }

  @GetMapping("/bootstrap")
  BootstrapDto bootstrap(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
    return application.bootstrap(sessionToken);
  }

  @PostMapping("/body-records")
  ResponseEntity<BodyRecordDto> createBodyRecord(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody CreateBodyRecordRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(application.createBodyRecord(sessionToken, request));
  }

  @PostMapping("/meals")
  ResponseEntity<MealDto> createMeal(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody CreateMealRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(application.createMeal(sessionToken, request));
  }

  @PostMapping("/workouts/{id}/complete")
  WorkoutCompletionDto completeWorkout(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable("id") UUID id,
      @RequestBody CompleteWorkoutRequest request) {
    return application.completeWorkout(sessionToken, id, request);
  }

  @PostMapping("/goals")
  ResponseEntity<GoalDto> createGoal(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody CreateGoalRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(application.createGoal(sessionToken, request));
  }

  @PostMapping("/ai/messages")
  AiMessageResponse sendAiMessage(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody AiMessageRequest request) {
    return application.sendAiMessage(sessionToken, request.message());
  }

  @PostMapping("/v1/app/media-upload-tickets")
  ResponseEntity<MediaUploadTicket> createMediaUploadTicket(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody CreateMediaUploadTicketRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(application.createMediaUploadTicket(sessionToken, request));
  }

  @PutMapping("/media-uploads/{mediaId}")
  ResponseEntity<Void> uploadLocalMedia(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable UUID mediaId,
      @RequestBody byte[] bytes) {
    UUID userId = application.authenticateSession(sessionToken);
    // The local adapter performs byte length and SHA-256 verification before the durable status flip.
    // This endpoint is the upload target included only by the local profile adapter.
    if (!(mediaUploadPort instanceof MealRecognitionRuntime.LocalMediaUploadPort local)) {
      throw new DependencyNotConfiguredException();
    }
    local.upload(userId, mediaId, bytes);
    application.markMediaUploaded(sessionToken, mediaId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/v1/app/meal-recognition-jobs")
  ResponseEntity<MealRecognitionJobDto> createMealRecognitionJob(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody CreateMealRecognitionJobRequest request) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(application.createMealRecognitionJob(sessionToken, request));
  }

  @GetMapping("/v1/app/meal-recognition-jobs/{jobId}")
  MealRecognitionJobDto mealRecognitionJob(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @PathVariable UUID jobId) {
    return application.mealRecognitionJob(sessionToken, jobId);
  }

  @PostMapping("/v1/app/meal-records")
  ResponseEntity<MealDto> createMealRecord(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody CreateMealRecordRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(application.createMealRecord(sessionToken, request));
  }
}
