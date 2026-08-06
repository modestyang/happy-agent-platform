package happy.jayden.yang.fitness;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.infrastructure.JdbcAgentProviderStatus;
import happy.jayden.yang.fitness.infrastructure.JdbcFitnessStore;
import happy.jayden.yang.fitness.infrastructure.agent.FitnessTools;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessPorts.AgentProviderStatus;
import happy.jayden.yang.fitness.service.FitnessPorts.AiConversation;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.PasswordVerifier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

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
  @DependsOn("agentFlyway")
  AgentProviderStatus agentProviderStatus(@Qualifier("agentDataSource") DataSource dataSource) {
    return new JdbcAgentProviderStatus(dataSource);
  }

  @Bean
  PasswordVerifier passwordVerifier() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    return encoder::matches;
  }

  @Bean
  AiConversation aiConversation(
      FitnessStore store,
      @Qualifier("agentDataSource") DataSource dataSource,
      ObjectMapper objectMapper,
      @Value("${happy.agent.workbench.master-key-file:./deploy/secrets/agent-master-key}")
          String masterKeyFile) {
    return new AgentRuntimeConversation(store, dataSource, objectMapper, masterKeyFile);
  }

  @Bean
  FitnessApplicationService fitnessApplicationService(
      FitnessStore store,
      PasswordVerifier passwordVerifier,
      AgentProviderStatus providerStatus,
      AiConversation aiConversation) {
    return new FitnessApplicationService(store, passwordVerifier, providerStatus, aiConversation);
  }

  @Bean
  FitnessTools fitnessTools(FitnessApplicationService fitnessApplicationService) {
    return new FitnessTools(fitnessApplicationService);
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
