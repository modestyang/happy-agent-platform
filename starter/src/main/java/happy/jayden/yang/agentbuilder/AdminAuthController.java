package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.service.auth.AdminAuthPort.AdminPrincipal;
import happy.jayden.yang.agentbuilder.service.auth.AdminAuthService;
import happy.jayden.yang.config.SessionCookieFactory;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentication endpoint for the developer-only Agent console. */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
  public static final String SESSION_COOKIE = "AGENT_ADMIN_SESSION";
  private final AdminAuthService auth;
  private final SessionCookieFactory sessionCookies;

  public AdminAuthController(AdminAuthService auth, SessionCookieFactory sessionCookies) {
    this.auth = auth;
    this.sessionCookies = sessionCookies;
  }

  @PostMapping("/login")
  ResponseEntity<SessionResponse> login(@RequestBody LoginBody request) {
    if (request == null || request.password() == null)
      throw new AdminAuthService.AdminAuthenticationException();
    var session =
        auth.login(
            new AdminAuthService.LoginRequest(
                request.username(), request.password().toCharArray()));
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            sessionCookies
                .create(SESSION_COOKIE, session.sessionToken(), Duration.ofDays(14))
                .toString())
        .body(new SessionResponse(session.username()));
  }

  @GetMapping("/session")
  SessionResponse session(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
    AdminPrincipal principal = auth.authenticate(sessionToken);
    return new SessionResponse(principal.username());
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
    auth.logout(sessionToken);
    return ResponseEntity.noContent()
        .header(
            HttpHeaders.SET_COOKIE,
            sessionCookies.create(SESSION_COOKIE, "", Duration.ZERO).toString())
        .build();
  }

  public record LoginBody(String username, String password) {}

  public record SessionResponse(String username) {}
}
