package happy.jayden.yang.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookieFactory {
  private final boolean secure;

  public SessionCookieFactory(@Value("${happy.security.secure-cookies:false}") boolean secure) {
    this.secure = secure;
  }

  public ResponseCookie create(String name, String value, Duration maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}
