package happy.jayden.yang.fitness;

import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginRequest;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginResponse;
import happy.jayden.yang.fitness.service.FitnessDtos.LoginResult;
import happy.jayden.yang.fitness.service.FitnessDtos.RegisterRequest;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/local")
public class LocalAuthController {

  public static final String SESSION_COOKIE = "FITNESS_SESSION";
  private final FitnessApplicationService application;

  public LocalAuthController(FitnessApplicationService application) {
    this.application = application;
  }

  @PostMapping("/login")
  ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
    return loginResponse(application.login(request));
  }

  @PostMapping("/register")
  ResponseEntity<LoginResponse> register(@RequestBody RegisterRequest request) {
    return loginResponse(application.register(request));
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(
      @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
    application.logout(sessionToken);
    ResponseCookie expired =
        ResponseCookie.from(SESSION_COOKIE, "")
            .httpOnly(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ZERO)
            .build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
  }

  private ResponseEntity<LoginResponse> loginResponse(LoginResult login) {
    ResponseCookie cookie =
        ResponseCookie.from(SESSION_COOKIE, login.sessionToken())
            .httpOnly(true)
            .secure(false)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ofDays(14))
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(new LoginResponse(login.user()));
  }
}
