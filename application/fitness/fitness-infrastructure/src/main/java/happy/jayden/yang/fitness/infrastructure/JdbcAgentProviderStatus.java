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
            "SELECT COUNT(*) "
                + "FROM agent_providers c "
                + "JOIN agent_provider_credentials p "
                + "  ON p.provider_key = c.provider_key "
                + "WHERE c.status='ACTIVE'",
            Long.class);
    return count != null && count > 0;
  }
}
