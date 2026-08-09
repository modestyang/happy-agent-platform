package happy.jayden.yang.fitness;

import static happy.jayden.yang.fitness.LocalAuthController.SESSION_COOKIE;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecognitionJobRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMealRecordRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.CreateMediaUploadTicketRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.MediaUploadTicket;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyNotConfiguredException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

  public FitnessV1Controller(
      FitnessApplicationService application,
      MediaUploadPort media,
      ObjectMapper mapper) {
    this.application = application;
    this.media = media;
    this.mapper = mapper;
  }

  @PostMapping("/media-upload-tickets")
  ResponseEntity<MediaUploadTicket> ticket(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateMediaUploadTicketRequest request) {
    return idempotent(
        token,
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
    application.completeMediaUpload(token, mediaId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/media-uploads/{mediaId}/complete")
  ResponseEntity<Void> completeUpload(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @PathVariable("mediaId") UUID mediaId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody java.util.Map<String, Object> body) {
    if (key == null || key.isBlank()) throw new InvalidRequestException("Idempotency-Key 必填");
    if (body.size() != 1 || !"DIRECT_UPLOAD_COMPLETED".equals(body.get("confirmation"))) {
      throw new InvalidRequestException("完成上传确认不合法");
    }
    application.completeMediaUpload(token, mediaId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/meal-recognition-jobs")
  ResponseEntity<FitnessV1Responses.Job> create(
      @CookieValue(name = SESSION_COOKIE, required = false) String token,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateMealRecognitionJobRequest request) {
    return idempotent(
        token,
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
    return idempotent(
        token,
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
      String token,
      String operation,
      String key,
      String requestHash,
      Class<T> responseType,
      Supplier<T> create,
      Function<T, ResponseEntity<T>> response) {
    T value =
        application.idempotently(
            token,
            operation,
            key,
            requestHash,
            create,
            FitnessV1Controller::resourceId,
            this::write,
            json -> read(json, responseType));
    return response.apply(value);
  }

  private <T> T read(String json, Class<T> responseType) {
    try {
      return mapper.readValue(json, responseType);
    } catch (Exception exception) {
      throw new IllegalStateException("无法读取已提交的幂等响应", exception);
    }
  }

  private String write(Object response) {
    try {
      return mapper.writeValueAsString(response);
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
