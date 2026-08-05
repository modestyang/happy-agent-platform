package happy.jayden.yang.agentbuilder.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AesGcmSourceContractTest {
  @Test
  void decodedKeyIsPassedDirectlyToSecretKeySpecAndClearedInFinally() throws Exception {
    var source =
        Files.readString(
            Path.of(
                "src/main/java/happy/jayden/yang/agentbuilder/infrastructure/security/AesGcmCredentialCipher.java"));
    assertTrue(source.contains("new SecretKeySpec(keyBytes, \"AES\")"));
    assertFalse(source.contains("new SecretKeySpec(Arrays.copyOf(keyBytes"));
    assertTrue(source.contains("finally {\n      Arrays.fill(keyBytes, (byte) 0);"));
  }
}
