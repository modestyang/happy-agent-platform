package happy.jayden.yang.fitness;

import static happy.jayden.yang.fitness.LocalAuthController.SESSION_COOKIE;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecognitionJobRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMediaUploadTicketRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessExceptions.ConflictException;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public v1 resource root — deliberately separate from legacy local experience endpoints. */
@RestController
@RequestMapping("/api/v1/app")
public class FitnessV1Controller {
  private final FitnessApplicationService application;
  private final MediaUploadPort media;
  private final ObjectMapper mapper;
  private final TransactionTemplate fitnessTransaction;

  public FitnessV1Controller(
      FitnessApplicationService application,
      MediaUploadPort media,
      ObjectMapper mapper,
      @Qualifier("fitnessTransactionManager")
          PlatformTransactionManager fitnessTransactionManager) {
    this.application = application;
    this.media = media;
    this.mapper = mapper;
    this.fitnessTransaction = new TransactionTemplate(fitnessTransactionManager);
  }

  @PostMapping("/media-upload-tickets")
  ResponseEntity<MediaUploadTicket> ticket(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateMediaUploadTicketRequest request) {
    UUID user = application.authenticateSession(token);
    return idempotent(
        user,
        "ticket",
        key,
        hash(request),
        MediaUploadTicket.class,
        () -> application.createMediaUploadTicket(token, request),
        ticket -> ResponseEntity.status(HttpStatus.CREATED).body(ticket));
  }

  @PutMapping("/media-uploads/{mediaId}")
  ResponseEntity<Void> upload(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("mediaId") UUID mediaId,
      @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
      @RequestBody byte[] bytes) {
    UUID user = application.authenticateSession(token);
    if (!(media instanceof MealRecognitionRuntime.LocalMediaUploadPort local)) {
      throw new DependencyNotConfiguredException();
    }
    local.upload(user, mediaId, contentType.split(";", 2)[0].trim(), bytes);
    application.markMediaUploaded(token, mediaId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/meal-recognition-jobs")
  ResponseEntity<FitnessV1Responses.Job> create(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateMealRecognitionJobRequest request) {
    UUID user = application.authenticateSession(token);
    return idempotent(
        user,
        "job",
        key,
        hash(request),
        FitnessV1Responses.Job.class,
        () -> FitnessV1Responses.job(application.createMealRecognitionJob(token, request)),
        job ->
            ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/v1/app/meal-recognition-jobs/" + job.jobId())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(job));
  }

  @GetMapping("/meal-recognition-jobs/{jobId}")
  FitnessV1Responses.Job job(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("jobId") UUID jobId) {
    return FitnessV1Responses.job(application.mealRecognitionJob(token, jobId));
  }

  @GetMapping("/meal-records")
  FitnessV1Responses.MealRecordPage mealRecords(
      @CookieValue(name = SESSION_COOKIE, required = false) String token) {
    return FitnessV1Responses.mealPage(application.mealRecords(token));
  }

  @PostMapping("/meal-records")
  ResponseEntity<FitnessV1Responses.MealRecord> record(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateMealRecordRequest request) {
    UUID user = application.authenticateSession(token);
    return idempotent(
        user,
        "record",
        key,
        hash(request),
        FitnessV1Responses.MealRecord.class,
        () -> FitnessV1Responses.meal(application.createMealRecord(token, request)),
        meal ->
            ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/v1/app/meal-records/" + meal.mealRecordId())
                .body(meal));
  }

  private <T> ResponseEntity<T> idempotent(
      UUID user,
      String operation,
      String key,
      String requestHash,
      Class<T> responseType,
      Supplier<T> create,
      Function<T, ResponseEntity<T>> response) {
    T prior = replay(user, operation, key, requestHash, responseType);
    if (prior != null) {
      return response.apply(prior);
    }
    try {
      return Objects.requireNonNull(
          fitnessTransaction.execute(
              ignored -> {
                T committed = replay(user, operation, key, requestHash, responseType);
                if (committed != null) {
                  return response.apply(committed);
                }
                T created = create.get();
                save(user, operation, key, requestHash, resourceId(created), created);
                return response.apply(created);
              }));
    } catch (DuplicateKeyException exception) {
      return response.apply(
          replayAfterUniqueConflict(user, operation, key, requestHash, responseType));
    }
  }

  private <T> T replayAfterUniqueConflict(
      UUID user, String operation, String key, String requestHash, Class<T> responseType) {
    T replay = replay(user, operation, key, requestHash, responseType);
    if (replay == null) {
      throw new ConflictException("Idempotency-Key 并发请求未能找到已提交结果");
    }
    return replay;
  }

  private <T> T replay(
      UUID user, String operation, String key, String requestHash, Class<T> responseType) {
    if (key == null || key.isBlank()) {
      throw new InvalidRequestException("Idempotency-Key 必填");
    }
    var found = application.idempotency(user, operation, key);
    if (found.isEmpty()) {
      return null;
    }
    if (!found.get().requestHash().equals(requestHash)) {
      throw new ConflictException("Idempotency-Key 已用于不同请求");
    }
    try {
      return mapper.readValue(found.get().responseJson(), responseType);
    } catch (Exception exception) {
      throw new IllegalStateException("无法读取已提交的幂等响应", exception);
    }
  }

  private void save(
      UUID user,
      String operation,
      String key,
      String requestHash,
      UUID resourceId,
      Object response) {
    try {
      application.saveIdempotency(
          user, operation, key, requestHash, resourceId, mapper.writeValueAsString(response));
    } catch (DuplicateKeyException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("无法保存幂等响应", exception);
    }
  }

  private static UUID resourceId(Object response) {
    if (response instanceof MediaUploadTicket ticket) {
      return ticket.mediaId();
    }
    if (response instanceof FitnessV1Responses.Job job) {
      return job.jobId();
    }
    if (response instanceof FitnessV1Responses.MealRecord meal) {
      return meal.mealRecordId();
    }
    throw new IllegalArgumentException("不支持的幂等响应类型");
  }

  private String hash(Object body) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));
    } catch (Exception exception) {
      throw new IllegalStateException("无法计算请求摘要", exception);
    }
  }
}
