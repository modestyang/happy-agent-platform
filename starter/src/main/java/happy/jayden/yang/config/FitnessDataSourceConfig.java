package happy.jayden.yang.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
public class FitnessDataSourceConfig {

  @Bean(name = "fitnessDataSource")
  DataSource fitnessDataSource(
      @Value("${happy.datasource.fitness.url:jdbc:postgresql://localhost:5432/happy_agent}")
          String url,
      @Value("${happy.datasource.fitness.username:fitness_app}") String username,
      @Value("${happy.datasource.fitness.password:fitness_app_password}") String password) {
    return dataSource(url, username, password, "fitness");
  }

  @Bean(name = "fitnessTransactionManager")
  PlatformTransactionManager fitnessTransactionManager(
      @org.springframework.beans.factory.annotation.Qualifier("fitnessDataSource")
          DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean(name = "fitnessFlyway", initMethod = "migrate")
  Flyway fitnessFlyway(
      @org.springframework.beans.factory.annotation.Qualifier("fitnessDataSource")
          DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas("fitness")
        .defaultSchema("fitness")
        .table("fitness_schema_history")
        .locations("classpath:db/fitness")
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
