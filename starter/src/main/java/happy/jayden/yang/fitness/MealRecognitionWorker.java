package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
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
        .ifPresent(
            job ->
                store.updateRecognitionJob(
                    job.jobId(),
                    runtime.recognize(
                        job.userId(), job.mediaId(), job.mealType(), job.occurredAt())));
  }
}
