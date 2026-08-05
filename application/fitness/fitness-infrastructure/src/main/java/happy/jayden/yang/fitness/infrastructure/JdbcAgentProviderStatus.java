package happy.jayden.yang.fitness.infrastructure;

import happy.jayden.yang.fitness.service.FitnessPorts.AgentProviderStatus;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAgentProviderStatus implements AgentProviderStatus {

  private final JdbcTemplate jdbc;

  public JdbcAgentProviderStatus(DataSource agentDataSource) {
    this.jdbc = new JdbcTemplate(agentDataSource);
  }

  @Override
  public boolean configured() {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM provider_catalog WHERE application_scope IN ('fitness','*') AND status='AVAILABLE'",
            Long.class);
    return count != null && count > 0;
  }
}
