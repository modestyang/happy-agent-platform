package happy.jayden.yang.agentbuilder.infrastructure.auth;

import happy.jayden.yang.agentbuilder.service.auth.AdminAuthPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC implementation for developer account and console session storage. */
public final class JdbcAdminAuthStore implements AdminAuthPort {
  private final JdbcTemplate jdbc;

  public JdbcAdminAuthStore(DataSource agentDataSource) {
    this.jdbc = new JdbcTemplate(agentDataSource);
  }

  @Override
  public Optional<Account> findAccount(String username) {
    List<Account> accounts =
        jdbc.query(
            "SELECT account_id,username,password_hash FROM agent_admin_accounts WHERE username=?"
                + " AND status='ACTIVE'",
            (rs, row) ->
                new Account(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("username"),
                    rs.getString("password_hash")),
            username);
    return accounts.stream().findFirst();
  }

  @Override
  public void createSession(String tokenHash, UUID accountId, Instant expiresAt) {
    jdbc.update("DELETE FROM agent_admin_sessions WHERE expires_at <= CURRENT_TIMESTAMP");
    jdbc.update(
        "INSERT INTO agent_admin_sessions(session_token_hash,account_id,expires_at) VALUES (?,?,?)",
        tokenHash,
        accountId,
        Timestamp.from(expiresAt));
  }

  @Override
  public Optional<AdminPrincipal> findSession(String tokenHash, Instant now) {
    List<AdminPrincipal> principals =
        jdbc.query(
            "SELECT a.account_id,a.username FROM agent_admin_sessions s JOIN agent_admin_accounts a"
                + " ON a.account_id=s.account_id WHERE s.session_token_hash=?"
                + " AND s.expires_at>? AND a.status='ACTIVE'",
            (rs, row) ->
                new AdminPrincipal(
                    rs.getObject("account_id", UUID.class), rs.getString("username")),
            tokenHash,
            Timestamp.from(now));
    return principals.stream().findFirst();
  }

  @Override
  public void revokeSession(String tokenHash) {
    jdbc.update("DELETE FROM agent_admin_sessions WHERE session_token_hash=?", tokenHash);
  }

  @Override
  public void seedAccount(String username, String passwordHash) {
    jdbc.update(
        "INSERT INTO agent_admin_accounts(account_id,username,password_hash,status) VALUES (?,?,?,'ACTIVE')"
            + " ON CONFLICT (username) DO NOTHING",
        UUID.randomUUID(),
        username,
        passwordHash);
  }
}
