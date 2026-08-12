package happy.jayden.yang.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class SessionCookieFactoryTest {

  @Test
  void productionCookieIsSecureHttpOnlyLaxAndRootScoped() {
    ResponseCookie cookie =
        new SessionCookieFactory(true).create("SESSION", "token", Duration.ofDays(14));

    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
    assertThat(cookie.getPath()).isEqualTo("/");
  }

  @Test
  void localCookieCanRemainNonSecure() {
    assertThat(
            new SessionCookieFactory(false)
                .create("SESSION", "token", Duration.ofDays(14))
                .isSecure())
        .isFalse();
  }
}
