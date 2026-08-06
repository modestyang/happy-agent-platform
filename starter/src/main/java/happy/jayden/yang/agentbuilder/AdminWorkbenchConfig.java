package happy.jayden.yang.agentbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.AdminWorkbenchLocalSeed;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcAdminWorkbenchStore;
import happy.jayden.yang.agentbuilder.infrastructure.tool.DefaultToolRegistry;
import happy.jayden.yang.agentbuilder.infrastructure.tool.SpringToolCatalogScanner;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcHookRepository;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcModelRepository;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcPromptRepository;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcProviderRepository;
import happy.jayden.yang.agentbuilder.infrastructure.catalog.JdbcSkillRepository;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchService;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistry;
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
  AdminWorkbenchService adminWorkbenchService(JdbcAdminWorkbenchStore store) {
    return new AdminWorkbenchService(store);
  }

  @Bean
  SpringToolCatalogScanner springToolCatalogScanner() {
    return new SpringToolCatalogScanner("local-dev", List.of());
  }

  @Bean
  ToolRegistry toolRegistry(SpringToolCatalogScanner scanner, FitnessTools fitnessTools) {
    return new DefaultToolRegistry(scanner.scanRegistrations(List.of(fitnessTools)));
  }

  @Bean JdbcProviderRepository providerCatalog(@Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) { return new JdbcProviderRepository(dataSource, mapper); }
  @Bean JdbcModelRepository modelCatalog(@Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) { return new JdbcModelRepository(dataSource, mapper); }
  @Bean JdbcSkillRepository skillCatalog(@Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) { return new JdbcSkillRepository(dataSource, mapper); }
  @Bean JdbcPromptRepository promptCatalog(@Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) { return new JdbcPromptRepository(dataSource, mapper); }
  @Bean JdbcHookRepository hookCatalog(@Qualifier("agentDataSource") DataSource dataSource, ObjectMapper mapper) { return new JdbcHookRepository(dataSource, mapper); }

  @Bean
  @ConditionalOnProperty(
      name = "happy.agent.workbench.local-seed.enabled",
      havingValue = "true",
      matchIfMissing = false)
  ApplicationRunner adminWorkbenchLocalSeed(JdbcAdminWorkbenchStore store) {
    return ignored -> new AdminWorkbenchLocalSeed(store).seed();
  }
}
