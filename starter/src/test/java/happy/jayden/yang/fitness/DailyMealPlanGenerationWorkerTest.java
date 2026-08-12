package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DailyMealPlanGenerationWorkerTest {

  @Test
  void scheduledDispatchRunsWorkOnTheDedicatedExecutor() {
    AtomicInteger calls = new AtomicInteger();
    DailyMealPlanGenerationWorker worker =
        new DailyMealPlanGenerationWorker(
            () -> {
              calls.incrementAndGet();
              return true;
            },
            Runnable::run);

    worker.dispatchOne();

    assertThat(calls).hasValue(1);
  }
}
