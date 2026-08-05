package happy.jayden.yang.fitness;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.fitness.infrastructure.JdbcAgentProviderStatus;
import happy.jayden.yang.fitness.infrastructure.JdbcFitnessStore;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyUnavailableException;
import happy.jayden.yang.fitness.service.FitnessPorts.AgentProviderStatus;
import happy.jayden.yang.fitness.service.FitnessPorts.AiConversation;
import happy.jayden.yang.fitness.service.FitnessPorts.FitnessStore;
import happy.jayden.yang.fitness.service.FitnessPorts.PasswordVerifier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
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
  AiConversation aiConversation() {
    return (userId, message) -> {
      throw new DependencyUnavailableException();
    };
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
  @ConditionalOnProperty(
      name = "happy.fitness.local-seed.enabled",
      havingValue = "true",
      matchIfMissing = false)
  ApplicationRunner localFitnessSeed(JdbcFitnessStore store) {
    return arguments -> {
      BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
      store.seedLocalExperience(encoder.encode("demo123"));
    };
  }
}
