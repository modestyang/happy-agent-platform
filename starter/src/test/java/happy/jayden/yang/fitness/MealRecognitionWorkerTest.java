package happy.jayden.yang.fitness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import happy.jayden.yang.fitness.service.FitnessDtos.ClaimedMealRecognitionJob;
import happy.jayden.yang.fitness.service.FitnessDtos.MealRecognitionResult;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MealRecognitionWorkerTest {
  @Mock private FitnessStore store;
  @Mock private MealRecognitionPort runtime;

  @Test
  void recordsRuntimeThrowableAsTerminalFailureInsteadOfLeavingJobRunning() {
    UUID jobId = UUID.randomUUID();
    ClaimedMealRecognitionJob job =
        new ClaimedMealRecognitionJob(
            jobId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            MealType.LUNCH,
            Instant.now(),
            Instant.parse("2026-08-09T00:00:00Z"));
    when(store.claimNextRecognitionJob()).thenReturn(Optional.of(job));
    when(runtime.recognize(any(), any(), any(), any())).thenThrow(new AssertionError("boom"));

    new MealRecognitionWorker(store, runtime).runOne();

    ArgumentCaptor<MealRecognitionResult> result = ArgumentCaptor.forClass(MealRecognitionResult.class);
    verify(store).updateRecognitionJob(eq(job), result.capture());
    org.assertj.core.api.Assertions.assertThat(result.getValue().status()).isEqualTo("FAILED");
    org.assertj.core.api.Assertions.assertThat(result.getValue().failureCode())
        .isEqualTo("RUNTIME_ERROR");
  }
}
