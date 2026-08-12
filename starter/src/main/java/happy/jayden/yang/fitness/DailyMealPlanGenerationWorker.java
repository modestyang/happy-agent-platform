package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The sole model-invocation entry point for daily plans. Requests and the 05:30 scheduler only
 * enqueue; this worker obtains a fenced durable lease before invoking the runtime.
 */
final class DailyMealPlanGenerationWorker {
  private final BooleanSupplier work;
  private final Executor executor;

  DailyMealPlanGenerationWorker(FitnessApplicationService application, Executor executor) {
    this(application::runNextDailyMealPlanGeneration, executor);
  }

  DailyMealPlanGenerationWorker(BooleanSupplier work, Executor executor) {
    this.work = Objects.requireNonNull(work, "work");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Scheduled(
      fixedDelayString = "${happy.fitness.meal-plan.poll-ms:500}",
      initialDelayString = "${happy.fitness.meal-plan.initial-delay-ms:500}")
  void dispatchOne() {
    try {
      executor.execute(this::runOne);
    } catch (TaskRejectedException ignored) {
      // All bounded worker slots are busy; the durable queue remains available for the next poll.
    }
  }

  void runOne() {
    work.getAsBoolean();
  }
}
