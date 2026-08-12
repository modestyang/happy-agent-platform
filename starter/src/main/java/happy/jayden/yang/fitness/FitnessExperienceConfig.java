package happy.jayden.yang.fitness;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime;
import happy.jayden.yang.fitness.infrastructure.JdbcAgentProviderStatus;
import happy.jayden.yang.fitness.infrastructure.JdbcFitnessAgentReadStore;
import happy.jayden.yang.fitness.infrastructure.JdbcFitnessStore;
import happy.jayden.yang.fitness.infrastructure.JdbcFitnessUserDirectory;
import happy.jayden.yang.fitness.infrastructure.agent.FitnessTools;
import happy.jayden.yang.fitness.service.FitnessAgentQueryService;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessPorts.AgentProviderStatus;
import happy.jayden.yang.fitness.service.FitnessPorts.CurrentGoalReportGenerationPort;
import happy.jayden.yang.fitness.service.FitnessPorts.DailyMealPlanGenerationPort;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.MealRecognitionPort;
import happy.jayden.yang.fitness.service.FitnessPorts.MediaUploadPort;
import happy.jayden.yang.fitness.service.FitnessPorts.PasswordVerifier;
import happy.jayden.yang.fitness.service.FitnessPorts.TransactionRunner;
import happy.jayden.yang.fitness.service.NutritionTargetEstimator;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class FitnessExperienceConfig {

  @Bean
  @DependsOn("fitnessFlyway")
  JdbcFitnessStore fitnessStore(
      @Qualifier("fitnessDataSource") DataSource dataSource, ObjectMapper objectMapper) {
    return new JdbcFitnessStore(dataSource, objectMapper);
  }

  @Bean
  @DependsOn("fitnessFlyway")
  JdbcFitnessUserDirectory fitnessUserDirectory(
      @Qualifier("fitnessDataSource") DataSource dataSource) {
    return new JdbcFitnessUserDirectory(dataSource);
  }

  @Bean
  @DependsOn("fitnessFlyway")
  JdbcFitnessAgentReadStore fitnessAgentReadStore(
      @Qualifier("fitnessDataSource") DataSource dataSource, ObjectMapper objectMapper) {
    return new JdbcFitnessAgentReadStore(dataSource, objectMapper);
  }

  @Bean
  FitnessAgentQueryService fitnessAgentQueryService(JdbcFitnessAgentReadStore store) {
    return new FitnessAgentQueryService(
        store,
        new NutritionTargetEstimator(),
        java.time.Clock.systemUTC(),
        java.time.ZoneId.of("Asia/Shanghai"));
  }

  @Bean
  @DependsOn("agentFlyway")
  AgentProviderStatus agentProviderStatus(@Qualifier("agentDataSource") DataSource dataSource) {
    return new JdbcAgentProviderStatus(dataSource);
  }

  @Bean
  PasswordVerifier passwordVerifier() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    return new PasswordVerifier() {
      @Override
      public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
      }

      @Override
      public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
      }
    };
  }

  @Bean
  @ConditionalOnProperty(name = "happy.fitness.local-media.enabled", havingValue = "true")
  MediaUploadPort localMediaUploadPort(@Qualifier("fitnessDataSource") DataSource dataSource) {
    return new MealRecognitionRuntime.LocalMediaUploadPort(dataSource);
  }

  @Bean
  @ConditionalOnProperty(
      name = "happy.fitness.local-media.enabled",
      havingValue = "false",
      matchIfMissing = true)
  MediaUploadPort ossMediaUploadPort(
      @Qualifier("fitnessDataSource") DataSource dataSource,
      @Value("${happy.fitness.oss.endpoint:}") String endpoint,
      @Value("${happy.fitness.oss.bucket:}") String bucket,
      @Value("${happy.fitness.oss.access-key-id:}") String accessKeyId,
      @Value("${happy.fitness.oss.access-key-secret:}") String accessKeySecret) {
    return new OssPresignedMediaUploadPort(
        dataSource, java.time.Clock.systemUTC(), endpoint, bucket, accessKeyId, accessKeySecret);
  }

  @Bean
  MealRecognitionPort mealRecognitionPort(
      @Qualifier("agentDataSource") DataSource agentDataSource,
      ObjectMapper mapper,
      MediaUploadPort mediaUploadPort,
      @Value("${happy.agent.workbench.master-key-file:./deploy/secrets/agent-master-key}")
          String masterKeyFile) {
    return new MealRecognitionRuntime(agentDataSource, mapper, masterKeyFile, mediaUploadPort);
  }

  @Bean
  DailyMealPlanGenerationPort dailyMealPlanGenerationPort(
      ObjectMapper mapper, ObjectProvider<PublishedAgentPlaygroundRuntime> runtime) {
    return new MealPlanGenerationRuntime(
        mapper,
        request ->
            runtime
                .getObject()
                .runTask(
                    request.agentKey(),
                    request.userId(),
                    request.requiredSkillKey(),
                    request.input())
                .output());
  }

  @Bean
  CurrentGoalReportGenerationPort currentGoalReportGenerationPort(
      @Qualifier("agentDataSource") DataSource agentDataSource,
      ObjectMapper mapper,
      @Value("${happy.agent.workbench.master-key-file:./deploy/secrets/agent-master-key}")
          String masterKeyFile) {
    return new CurrentGoalReportRuntime(agentDataSource, mapper, masterKeyFile);
  }

  @Bean
  FitnessApplicationService fitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      MediaUploadPort mediaUploadPort,
      DailyMealPlanGenerationPort dailyMealPlanGenerationPort,
      CurrentGoalReportGenerationPort currentGoalReportGenerationPort,
      @Qualifier("fitnessTransactionManager")
          PlatformTransactionManager fitnessTransactionManager) {
    TransactionTemplate transaction = new TransactionTemplate(fitnessTransactionManager);
    TransactionRunner runner =
        new TransactionRunner() {
          @Override
          public <T> T inTransaction(
              happy.jayden.yang.fitness.service.FitnessPorts.TransactionWork<T> work) {
            return transaction.execute(ignored -> work.run());
          }
        };
    return new FitnessApplicationService(
        store,
        passwordVerifier,
        providerStatus,
        mediaUploadPort,
        dailyMealPlanGenerationPort,
        currentGoalReportGenerationPort,
        runner);
  }

  @Bean
  MealRecognitionWorker mealRecognitionWorker(FitnessStore store, MealRecognitionPort runtime) {
    return new MealRecognitionWorker(store, runtime);
  }

  @Bean
  DailyMealPlanGenerationWorker dailyMealPlanGenerationWorker(
      FitnessApplicationService application,
      @Qualifier("dailyMealPlanTaskExecutor") java.util.concurrent.Executor executor) {
    return new DailyMealPlanGenerationWorker(application, executor);
  }

  @Bean(name = "dailyMealPlanTaskExecutor", defaultCandidate = false)
  ThreadPoolTaskExecutor dailyMealPlanTaskExecutor(
      @Value("${happy.fitness.meal-plan.concurrency:3}") int concurrency) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(concurrency);
    executor.setMaxPoolSize(concurrency);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("daily-meal-plan-");
    return executor;
  }

  @Bean
  CurrentGoalReportGenerationWorker currentGoalReportGenerationWorker(
      FitnessApplicationService application) {
    return new CurrentGoalReportGenerationWorker(application);
  }

  @Bean
  DailyMealPlanScheduler dailyMealPlanScheduler(FitnessApplicationService application) {
    return new DailyMealPlanScheduler(application);
  }

  @Bean
  FitnessTools fitnessTools(
      FitnessApplicationService fitnessApplicationService,
      FitnessAgentQueryService fitnessAgentQueryService) {
    return new FitnessTools(fitnessApplicationService, fitnessAgentQueryService);
  }

  @Bean
  @ConditionalOnProperty(
      name = "happy.fitness.local-seed.enabled",
      havingValue = "true",
      matchIfMissing = false)
  LocalExperienceSeed localFitnessSeed(JdbcFitnessStore store) {
    return new LocalExperienceSeed(store);
  }

  static final class LocalExperienceSeed implements ApplicationRunner {
    private final JdbcFitnessStore store;
    private final String passwordHash = new BCryptPasswordEncoder().encode("demo123");

    LocalExperienceSeed(JdbcFitnessStore store) {
      this.store = store;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments arguments) {
      refresh();
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    public void refresh() {
      store.seedLocalExperience(passwordHash);
    }
  }
}
