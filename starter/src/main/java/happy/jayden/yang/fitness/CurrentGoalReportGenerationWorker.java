package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import org.springframework.scheduling.annotation.Scheduled;

/** The only model-invocation entry point for a current-goal report. */
final class CurrentGoalReportGenerationWorker {
  private final FitnessApplicationService application;

  CurrentGoalReportGenerationWorker(FitnessApplicationService application) {
    this.application = application;
  }

  @Scheduled(
      fixedDelayString = "${happy.fitness.current-goal-report.poll-ms:500}",
      initialDelayString = "${happy.fitness.current-goal-report.initial-delay-ms:500}")
  void runOne() {
    application.runNextCurrentGoalReportGeneration();
  }
}
