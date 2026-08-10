package happy.jayden.yang.agentbuilder.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Application service for the developer-only console identity. */
public final class AdminAuthService {
  private static final Duration SESSION_TTL = Duration.ofDays(14);

  private final AdminAuthPort store;
  private final PasswordVerifier passwordVerifier;
  private final SecureRandom secureRandom = new SecureRandom();

  public AdminAuthService(AdminAuthPort store, PasswordVerifier passwordVerifier) {
    this.store = Objects.requireNonNull(store, "store");
    this.passwordVerifier = Objects.requireNonNull(passwordVerifier, "passwordVerifier");
  }

  public AdminSession login(LoginRequest request) {
    if (request == null || blank(request.username()) || request.password() == null) {
      throw new AdminAuthenticationException();
    }
    var account =
        store.findAccount(request.username().trim()).orElseThrow(AdminAuthenticationException::new);
    try {
      if (!passwordVerifier.matches(request.password(), account.passwordHash())) {
        throw new AdminAuthenticationException();
      }
    } finally {
      java.util.Arrays.fill(request.password(), '\0');
    }
    byte[] tokenBytes = new byte[32];
    secureRandom.nextBytes(tokenBytes);
    String token = HexFormat.of().formatHex(tokenBytes);
    store.createSession(hash(token), account.accountId(), Instant.now().plus(SESSION_TTL));
    return new AdminSession(account.accountId(), account.username(), token);
  }

  public AdminAuthPort.AdminPrincipal authenticate(String sessionToken) {
    if (blank(sessionToken)) throw new AdminAuthenticationException();
    return store
        .findSession(hash(sessionToken), Instant.now())
        .orElseThrow(AdminAuthenticationException::new);
  }

  public void logout(String sessionToken) {
    if (!blank(sessionToken)) store.revokeSession(hash(sessionToken));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record LoginRequest(String username, char[] password) {}

  public record AdminSession(java.util.UUID accountId, String username, String sessionToken) {}

  @FunctionalInterface
  public interface PasswordVerifier {
    boolean matches(char[] rawPassword, String passwordHash);
  }

  public static final class AdminAuthenticationException extends RuntimeException {
    public AdminAuthenticationException() {
      super("Administrator authentication required");
    }
  }
}
