package happy.jayden.yang.agentbuilder.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AesGcmCredentialCipherTest {
  @TempDir java.nio.file.Path temporaryDirectory;

  @Test
  void encryptsWithRandomNinetySixBitIvAndAuthenticatedComponentVersion() throws Exception {
    var keyFile = temporaryDirectory.resolve("master.key");
    Files.writeString(keyFile, Base64.getEncoder().encodeToString(new byte[32]));
    var reference = new ComponentRef(new ComponentKey("provider.main"), new ComponentVersion(3));
    var cipher =
        AesGcmCredentialCipher.fromEnvironment(
            Map.of("HAPPY_AGENT_MASTER_KEY_FILE", keyFile.toString()), reference);

    var first = cipher.encrypt("known-plaintext".toCharArray());
    var second = cipher.encrypt("known-plaintext".toCharArray());

    assertFalse(first.toString().contains("known-plaintext"));
    assertArrayEquals("known-plaintext".toCharArray(), cipher.decrypt(first));
    assertNotEquals(
        Base64.getEncoder().encodeToString(first.iv()),
        Base64.getEncoder().encodeToString(second.iv()));
    assertThrows(
        SecurityException.class,
        () ->
            AesGcmCredentialCipher.fromEnvironment(
                    Map.of("HAPPY_AGENT_MASTER_KEY_FILE", keyFile.toString()),
                    new ComponentRef(new ComponentKey("provider.other"), new ComponentVersion(3)))
                .decrypt(first));
  }

  @Test
  void rejectsMissingEnvironmentAndNonAes256Keys() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            AesGcmCredentialCipher.fromEnvironment(
                Map.of(),
                new ComponentRef(new ComponentKey("provider.main"), new ComponentVersion(1))));
    var shortKey = temporaryDirectory.resolve("short.key");
    Files.writeString(shortKey, Base64.getEncoder().encodeToString(new byte[16]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AesGcmCredentialCipher.fromEnvironment(
                Map.of("HAPPY_AGENT_MASTER_KEY_FILE", shortKey.toString()),
                new ComponentRef(new ComponentKey("provider.main"), new ComponentVersion(1))));
  }
}
