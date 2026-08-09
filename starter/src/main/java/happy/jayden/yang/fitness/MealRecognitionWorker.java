package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;

/** Durable background runner: browser requests only enqueue jobs. */
public final class MealRecognitionWorker {
  private final FitnessStore store;
  private final MealRecognitionPort runtime;

  public MealRecognitionWorker(FitnessStore store, MealRecognitionPort runtime) {
    this.store = store;
    this.runtime = runtime;
  }

  @Scheduled(
      fixedDelayString = "${happy.fitness.recognition.poll-ms:500}",
      initialDelayString = "${happy.fitness.recognition.initial-delay-ms:500}")
  public void runOne() {
    store
        .claimNextRecognitionJob()
        .ifPresent(job -> store.updateRecognitionJob(job, recognize(job)));
  }

  private MealRecognitionResult recognize(
      happy.jayden.yang.fitness.service.FitnessDtos.ClaimedMealRecognitionJob job) {
    try {
      return runtime.recognize(job.userId(), job.mediaId(), job.mealType(), job.occurredAt());
    } catch (Throwable throwable) {
      String message = throwable.getMessage();
      return new MealRecognitionResult(
          "FAILED", List.of(), "RUNTIME_ERROR", message == null ? "识别任务运行异常" : message);
    }
  }
}
