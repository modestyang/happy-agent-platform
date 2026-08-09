package happy.jayden.yang.agentbuilder.service.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for the isolated developer-console identity. */
public interface AdminAuthPort {
  Optional<Account> findAccount(String username);

  void createSession(String tokenHash, UUID accountId, Instant expiresAt);

  Optional<AdminPrincipal> findSession(String tokenHash, Instant now);

  void revokeSession(String tokenHash);

  void seedAccount(String username, String passwordHash);

  record Account(UUID accountId, String username, String passwordHash) {}

  record AdminPrincipal(UUID accountId, String username) {}
}
