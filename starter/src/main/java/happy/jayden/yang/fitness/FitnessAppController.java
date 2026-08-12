package happy.jayden.yang.fitness;

import static happy.jayden.yang.fitness.LocalAuthController.SESSION_COOKIE;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.BodyRecordDto;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapDto;
import happy.jayden.yang.fitness.service.FitnessDtos.CompleteWorkoutRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateBodyRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateGoalRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.FirstSetupRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.GoalDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealDto;
import happy.jayden.yang.fitness.service.FitnessDtos.TrainingProfileDto;
import happy.jayden.yang.fitness.service.FitnessDtos.TrainingProfileInput;
import happy.jayden.yang.fitness.service.FitnessDtos.WorkoutCompletionDto;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app")
public class FitnessAppController {

  private final FitnessApplicationService application;

  public FitnessAppController(FitnessApplicationService application) {
    this.application = application;
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

  @PostMapping("/first-setup")
  ResponseEntity<Void> completeFirstSetup(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody FirstSetupRequest request) {
    application.completeFirstSetup(sessionToken, request);
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  @PutMapping("/training-profile")
  TrainingProfileDto updateTrainingProfile(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
      @RequestBody TrainingProfileInput request) {
    return application.updateTrainingProfile(sessionToken, request);
  }
}
