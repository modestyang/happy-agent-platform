package happy.jayden.yang.agentbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.runtime.RuntimeCapabilityRegistry;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistry;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcHookRepository;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcModelRepository;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcPromptRepository;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcProviderRepository;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcSkillRepository;
import happy.jayden.yang.agentbuilder.infrastructure.auth.JdbcAdminAuthStore;
import happy.jayden.yang.agentbuilder.infrastructure.tool.DefaultToolRegistry;
import happy.jayden.yang.agentbuilder.infrastructure.tool.SpringToolCatalogScanner;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.AdminWorkbenchLocalSeed;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcAdminWorkbenchStore;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchService;
import happy.jayden.yang.agentbuilder.service.auth.AdminAuthService;
import happy.jayden.yang.fitness.infrastructure.agent.FitnessTools;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Personal-workbench wiring: catalog, release gate, Tool registry, and Fitness capabilities. */
@Configuration
public class AdminWorkbenchConfig {

  @Bean
  @DependsOn("agentFlyway")
  JdbcAdminWorkbenchStore adminWorkbenchStore(
      @Qualifier("agentDataSource") DataSource dataSource,
      ObjectMapper mapper,
      @Value("${happy.agent.workbench.master-key-file:./deploy/secrets/agent-master-key}")
          String masterKeyFile) {
    return new JdbcAdminWorkbenchStore(dataSource, mapper, Path.of(masterKeyFile));
  }

  @Bean
  AdminWorkbenchService adminWorkbenchService(
      JdbcAdminWorkbenchStore store, RuntimeCapabilityRegistry runtimeCapabilities) {
    return new AdminWorkbenchService(store, runtimeCapabilities);
  }

  @Bean
  @DependsOn("agentFlyway")
  JdbcAdminAuthStore adminAuthStore(@Qualifier("agentDataSource") DataSource dataSource) {
    return new JdbcAdminAuthStore(dataSource);
  }

  @Bean
  AdminAuthService adminAuthService(JdbcAdminAuthStore store) {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    return new AdminAuthService(store, (raw, hash) -> encoder.matches(String.valueOf(raw), hash));
  }

  @Bean
  FitnessSafetyHook fitnessSafetyHook() {
    return new FitnessSafetyHook();
  }

  @Bean
  FitnessSkillRegistry fitnessSkillRegistry(FitnessSafetyHook safetyHook) {
    return new FitnessSkillRegistry(safetyHook);
  }

  @Bean
  JdbcRunTraceRepository runTraceRepository(@Qualifier("agentDataSource") DataSource dataSource) {
    return new JdbcRunTraceRepository(dataSource);
  }

  @Bean
  PublishedAgentPlaygroundRuntime publishedAgentPlaygroundRuntime(
      @Qualifier("agentDataSource") DataSource dataSource,
      ObjectMapper mapper,
      JdbcRunTraceRepository traces,
      @Value("${happy.agent.workbench.master-key-file:./deploy/secrets/agent-master-key}")
          String masterKeyFile) {
    return new PublishedAgentPlaygroundRuntime(dataSource, mapper, Path.of(masterKeyFile), traces);
  }

  @Bean
  SpringToolCatalogScanner springToolCatalogScanner() {
    return new SpringToolCatalogScanner("local-dev", List.of());
  }

  @Bean
  ToolRegistry toolRegistry(SpringToolCatalogScanner scanner, FitnessTools fitnessTools) {
    return new DefaultToolRegistry(scanner.scanRegistrations(List.of(fitnessTools)));
  }

  // Kept as typed catalog adapters for the existing persistence surface.
  @Bean
  JdbcProviderRepository providerCatalog(
      @Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) {
    return new JdbcProviderRepository(dataSource, mapper);
  }

  @Bean
  JdbcModelRepository modelCatalog(
      @Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) {
    return new JdbcModelRepository(dataSource, mapper);
  }

  @Bean
  JdbcSkillRepository skillCatalog(
      @Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) {
    return new JdbcSkillRepository(dataSource, mapper);
  }

  @Bean
  JdbcPromptRepository promptCatalog(
      @Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) {
    return new JdbcPromptRepository(dataSource, mapper);
  }

  @Bean
  JdbcHookRepository hookCatalog(
      @Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) {
    return new JdbcHookRepository(dataSource, mapper);
  }

  @Bean
  @Order(1)
  @ConditionalOnProperty(
      name = "happy.agent.workbench.local-seed.enabled",
      havingValue = "true",
      matchIfMissing = false)
  ApplicationRunner adminWorkbenchLocalSeed(JdbcAdminWorkbenchStore store) {
    return ignored -> new AdminWorkbenchLocalSeed(store).seed();
  }

  @Bean
  @Order(2)
  @ConditionalOnProperty(
      name = "happy.agent.workbench.local-seed.enabled",
      havingValue = "true",
      matchIfMissing = false)
  ApplicationRunner adminAuthLocalSeed(
      JdbcAdminAuthStore store,
      @Value("${happy.agent.workbench.admin.username:admin}") String username,
      @Value("${happy.agent.workbench.admin.password:admin123}") String password) {
    return ignored -> store.seedAccount(username, new BCryptPasswordEncoder().encode(password));
  }

  @Bean
  @Order(3)
  ApplicationRunner runtimeCapabilityReconciler(
      JdbcAdminWorkbenchStore store, FitnessSkillRegistry runtimeCapabilities) {
    return ignored -> store.reconcileRuntimeCapabilities(runtimeCapabilities);
  }
}
