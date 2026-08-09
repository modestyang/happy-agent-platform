package happy.jayden.yang.fitness;

import static happy.jayden.yang.fitness.LocalAuthController.SESSION_COOKIE;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecognitionJobRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMediaUploadTicketRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public v1 resource root — deliberately separate from legacy local experience endpoints. */
@RestController
@RequestMapping("/api/v1/app")
public class FitnessV1Controller {
  private final FitnessApplicationService application;
  private final MediaUploadPort media;
  public FitnessV1Controller(FitnessApplicationService application, MediaUploadPort media) { this.application = application; this.media = media; }
  @PostMapping("/media-upload-tickets") ResponseEntity<MediaUploadTicket> ticket(@CookieValue(name = SESSION_COOKIE, required = false) String token, @RequestBody CreateMediaUploadTicketRequest request) { return ResponseEntity.status(HttpStatus.CREATED).body(application.createMediaUploadTicket(token, request)); }
  @PutMapping("/media-uploads/{mediaId}") ResponseEntity<Void> upload(@CookieValue(name = SESSION_COOKIE, required = false) String token, @PathVariable UUID mediaId, @RequestBody byte[] bytes) { UUID user = application.authenticateSession(token); if (!(media instanceof MealRecognitionRuntime.LocalMediaUploadPort local)) throw new DependencyNotConfiguredException(); local.upload(user, mediaId, bytes); application.markMediaUploaded(token, mediaId); return ResponseEntity.noContent().build(); }
  @PostMapping("/meal-recognition-jobs") ResponseEntity<FitnessV1Responses.Job> create(@CookieValue(name = SESSION_COOKIE, required = false) String token, @RequestBody CreateMealRecognitionJobRequest request) { var job = application.createMealRecognitionJob(token, request); return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.LOCATION, "/api/v1/app/meal-recognition-jobs/" + job.jobId()).header(HttpHeaders.RETRY_AFTER, "1").body(FitnessV1Responses.job(job)); }
  @GetMapping("/meal-recognition-jobs/{jobId}") FitnessV1Responses.Job job(@CookieValue(name = SESSION_COOKIE, required = false) String token, @PathVariable UUID jobId) { return FitnessV1Responses.job(application.mealRecognitionJob(token, jobId)); }
  @PostMapping("/meal-records") ResponseEntity<FitnessV1Responses.MealRecord> record(@CookieValue(name = SESSION_COOKIE, required = false) String token, @RequestBody CreateMealRecordRequest request) { var meal = application.createMealRecord(token, request); return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION, "/api/v1/app/meal-records/" + meal.id()).body(FitnessV1Responses.meal(meal)); }
}
