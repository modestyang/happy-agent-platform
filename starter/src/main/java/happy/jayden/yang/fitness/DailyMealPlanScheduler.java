package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;

/** At 05:30, enqueue durable work; a separately leased worker performs model invocation. */
final class DailyMealPlanScheduler implements ApplicationRunner {
  private final Runnable generateDailyPlans;

  DailyMealPlanScheduler(FitnessApplicationService application) {
    this(application::enqueueScheduledDailyMealPlans);
  }

  DailyMealPlanScheduler(Runnable generateDailyPlans) {
    this.generateDailyPlans = generateDailyPlans;
  }

  /** Current fitness accounts do not carry a per-user timezone, so Asia/Shanghai is explicit. */
  @Scheduled(cron = "${happy.fitness.meal-plan.cron:0 30 5 * * *}", zone = "Asia/Shanghai")
  void generateAtFiveThirty() {
    generateDailyPlans.run();
  }

  @Override
  public void run(ApplicationArguments arguments) {
    generateDailyPlans.run();
  }
}
