package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OssPresignedMediaUploadPortTest {
  @Test void canonicalSignedPutIsHttpsAndDoesNotExposeSecret() {
    String url = OssPresignedMediaUploadPort.signedPutUrl("https://oss.example.test", "photos", "meal/u/m", "image/png", Instant.parse("2026-08-09T00:10:00Z"), "id", "super-secret");
    assertThat(url).startsWith("https://oss.example.test/photos/meal/u/m?OSSAccessKeyId=id&Expires=1786234200&Signature=");
    assertThat(url).doesNotContain("super-secret");
  }
}
