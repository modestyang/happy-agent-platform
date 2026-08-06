package happy.jayden.yang.agentbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.AdminWorkbenchLocalSeed;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcAdminWorkbenchStore;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchService;
import java.nio.file.Path;
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
  @ConditionalOnProperty(
      name = "happy.agent.workbench.local-seed.enabled",
      havingValue = "true",
      matchIfMissing = false)
  ApplicationRunner adminWorkbenchLocalSeed(JdbcAdminWorkbenchStore store) {
    return ignored -> new AdminWorkbenchLocalSeed(store).seed();
  }
}
