package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class DailyMealPlanSchedulerTest {

  @Test
  void fiveThirtyShanghaiScheduleInvokesTheSharedGenerationUseCase() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    DailyMealPlanScheduler scheduler = new DailyMealPlanScheduler(calls::incrementAndGet);

    scheduler.generateAtFiveThirty();

    assertThat(calls).hasValue(1);
    Method method = DailyMealPlanScheduler.class.getDeclaredMethod("generateAtFiveThirty");
    Scheduled scheduled = method.getAnnotation(Scheduled.class);
    assertThat(scheduled.cron()).isEqualTo("${happy.fitness.meal-plan.cron:0 30 5 * * *}");
    assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
  }
}
