package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the same durable application use case used by manual catch-up generation. */
final class DailyMealPlanScheduler {
  private final Runnable generateDailyPlans;

  DailyMealPlanScheduler(FitnessApplicationService application) {
    this(application::generateScheduledDailyMealPlans);
  }

  DailyMealPlanScheduler(Runnable generateDailyPlans) {
    this.generateDailyPlans = generateDailyPlans;
  }

  /** Current fitness accounts do not carry a per-user timezone, so Asia/Shanghai is explicit. */
  @Scheduled(cron = "${happy.fitness.meal-plan.cron:0 30 5 * * *}", zone = "Asia/Shanghai")
  void generateAtFiveThirty() {
    generateDailyPlans.run();
  }
}
