package happy.jayden.yang.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
public class AgentDataSourceConfig {

  @Bean(name = "agentDataSource")
  DataSource agentDataSource(
      @Value("${happy.datasource.agent.url:jdbc:postgresql://localhost:5432/happy_agent}")
          String url,
      @Value("${happy.datasource.agent.username:agent_app}") String username,
      @Value("${happy.datasource.agent.password}") String password) {
    return dataSource(url, username, password, "agent");
  }

  @Bean(name = "agentTransactionManager")
  PlatformTransactionManager agentTransactionManager(
      @org.springframework.beans.factory.annotation.Qualifier("agentDataSource")
          DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean(name = "agentFlyway", initMethod = "migrate")
  @DependsOn("fitnessFlyway")
  Flyway agentFlyway(
      @org.springframework.beans.factory.annotation.Qualifier("agentDataSource")
          DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas("agent")
        .defaultSchema("agent")
        .table("agent_schema_history")
        .locations("classpath:db/agent")
        .createSchemas(false)
        .cleanDisabled(true)
        .load();
  }

  private DataSource dataSource(String url, String username, String password, String schema) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setSchema(schema);
    config.setConnectionInitSql("SET search_path TO " + schema);
    config.setMinimumIdle(0);
    config.setMaximumPoolSize(3);
    return new HikariDataSource(config);
  }
}
