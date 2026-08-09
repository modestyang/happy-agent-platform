package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The sole model-invocation entry point for daily plans. Requests and the 05:30 scheduler only
 * enqueue; this worker obtains a fenced durable lease before invoking the runtime.
 */
final class DailyMealPlanGenerationWorker {
  private final FitnessApplicationService application;

  DailyMealPlanGenerationWorker(FitnessApplicationService application) {
    this.application = application;
  }

  @Scheduled(
      fixedDelayString = "${happy.fitness.meal-plan.poll-ms:500}",
      initialDelayString = "${happy.fitness.meal-plan.initial-delay-ms:500}")
  void runOne() {
    application.runNextDailyMealPlanGeneration();
  }
}
