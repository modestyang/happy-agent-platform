package happy.jayden.yang.fitness;

import static happy.jayden.yang.fitness.LocalAuthController.SESSION_COOKIE;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecognitionJobRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMediaUploadTicketRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import happy.jayden.yang.fitness.service.FitnessExceptions.ConflictException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.web.bind.annotation.RequestHeader;
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
  private final ObjectMapper mapper;
  public FitnessV1Controller(FitnessApplicationService application, MediaUploadPort media, ObjectMapper mapper) { this.application = application; this.media = media; this.mapper=mapper; }
  @PostMapping("/media-upload-tickets") ResponseEntity<MediaUploadTicket> ticket(@CookieValue(name = SESSION_COOKIE, required = false) String token,@RequestHeader(name="Idempotency-Key",required=false) String key, @RequestBody CreateMediaUploadTicketRequest request) { UUID user=application.authenticateSession(token); String h=hash(request); var prior=replay(user,"ticket",key,h,MediaUploadTicket.class); if(prior!=null)return ResponseEntity.status(HttpStatus.CREATED).body(prior); var body=application.createMediaUploadTicket(token,request); save(user,"ticket",key,h,body.mediaId(),body); return ResponseEntity.status(HttpStatus.CREATED).body(body); }
  @PutMapping("/media-uploads/{mediaId}") ResponseEntity<Void> upload(@CookieValue(name = SESSION_COOKIE, required = false) String token, @PathVariable UUID mediaId, @RequestBody byte[] bytes) { UUID user = application.authenticateSession(token); if (!(media instanceof MealRecognitionRuntime.LocalMediaUploadPort local)) throw new DependencyNotConfiguredException(); local.upload(user, mediaId, bytes); application.markMediaUploaded(token, mediaId); return ResponseEntity.noContent().build(); }
  @PostMapping("/meal-recognition-jobs") ResponseEntity<FitnessV1Responses.Job> create(@CookieValue(name = SESSION_COOKIE, required = false) String token,@RequestHeader(name="Idempotency-Key",required=false) String key, @RequestBody CreateMealRecognitionJobRequest request) { UUID user=application.authenticateSession(token); String h=hash(request); var prior=replay(user,"job",key,h,FitnessV1Responses.Job.class); if(prior!=null)return ResponseEntity.status(HttpStatus.ACCEPTED).body(prior); var job = FitnessV1Responses.job(application.createMealRecognitionJob(token, request)); save(user,"job",key,h,job.jobId(),job); return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.LOCATION, "/api/v1/app/meal-recognition-jobs/" + job.jobId()).header(HttpHeaders.RETRY_AFTER, "1").body(job); }
  @GetMapping("/meal-recognition-jobs/{jobId}") FitnessV1Responses.Job job(@CookieValue(name = SESSION_COOKIE, required = false) String token, @PathVariable UUID jobId) { return FitnessV1Responses.job(application.mealRecognitionJob(token, jobId)); }
  @PostMapping("/meal-records") ResponseEntity<FitnessV1Responses.MealRecord> record(@CookieValue(name = SESSION_COOKIE, required = false) String token,@RequestHeader(name="Idempotency-Key",required=false) String key, @RequestBody CreateMealRecordRequest request) { UUID user=application.authenticateSession(token); String h=hash(request); var prior=replay(user,"record",key,h,FitnessV1Responses.MealRecord.class); if(prior!=null)return ResponseEntity.status(HttpStatus.CREATED).body(prior); var meal = FitnessV1Responses.meal(application.createMealRecord(token, request)); save(user,"record",key,h,meal.mealRecordId(),meal); return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION, "/api/v1/app/meal-records/" + meal.mealRecordId()).body(meal); }
  private <T> T replay(UUID user,String op,String key,String hash,Class<T> type) { if(key==null||key.isBlank()) throw new InvalidRequestException("Idempotency-Key 必填"); var found=application.idempotency(user,op,key); if(found.isEmpty())return null; if(!found.get().requestHash().equals(hash))throw new ConflictException("Idempotency-Key 已用于不同请求"); try{return mapper.readValue(found.get().responseJson(),type);}catch(Exception e){throw new IllegalStateException(e);} }
  private void save(UUID user,String op,String key,String hash,UUID id,Object body) { try{application.saveIdempotency(user,op,key,hash,id,mapper.writeValueAsString(body));}catch(org.springframework.dao.DuplicateKeyException e){throw new ConflictException("并发幂等请求，请重试");}catch(Exception e){throw new IllegalStateException(e);} }
  private String hash(Object body) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));}catch(Exception e){throw new IllegalStateException(e);} }
}
