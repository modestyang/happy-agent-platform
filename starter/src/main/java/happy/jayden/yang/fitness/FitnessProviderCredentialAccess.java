package happy.jayden.yang.fitness;

import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.provider.EncryptedSecret;
import happy.jayden.yang.agentbuilder.infrastructure.security.AesGcmCredentialCipher;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads a provider credential without coupling meal recognition to workbench runtime classes. */
final class FitnessProviderCredentialAccess {
  private final JdbcTemplate jdbc;
  private final Path masterKeyFile;

  FitnessProviderCredentialAccess(DataSource agentDataSource, Path masterKeyFile) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(agentDataSource, "agentDataSource"));
    this.masterKeyFile = Objects.requireNonNull(masterKeyFile, "masterKeyFile").toAbsolutePath();
  }

  Optional<char[]> readApiKey(String providerKey) {
    byte[][] encrypted =
        jdbc.query(
                "SELECT credential_ciphertext,credential_iv FROM agent_provider_credentials"
                    + " WHERE provider_key=?",
                (rs, row) -> new byte[][] {rs.getBytes(1), rs.getBytes(2)},
                providerKey)
            .stream()
            .findFirst()
            .orElse(null);
    if (encrypted == null) return Optional.empty();
    try {
      ComponentRef ref = new ComponentRef(new ComponentKey(providerKey), new ComponentVersion(1));
      AesGcmCredentialCipher cipher =
          AesGcmCredentialCipher.fromEnvironment(
              Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, masterKeyFile.toString()), ref);
      char[] plain = cipher.decrypt(new EncryptedSecret(ref, encrypted[0], encrypted[1]));
      try {
        return Optional.of(Arrays.copyOf(plain, plain.length));
      } finally {
        Arrays.fill(plain, '\0');
      }
    } finally {
      Arrays.fill(encrypted[0], (byte) 0);
      Arrays.fill(encrypted[1], (byte) 0);
    }
  }

  /** Decrypts only credential bytes captured in an immutable published Agent version. */
  char[] decryptPublishedSnapshot(
      String providerKey, int credentialKeyVersion, String ciphertextBase64, String ivBase64) {
    if (credentialKeyVersion < 1) throw new IllegalArgumentException("credential key version");
    byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
    byte[] iv = Base64.getDecoder().decode(ivBase64);
    try {
      ComponentRef ref =
          new ComponentRef(new ComponentKey(providerKey), new ComponentVersion(credentialKeyVersion));
      AesGcmCredentialCipher cipher =
          AesGcmCredentialCipher.fromEnvironment(
              Map.of(AesGcmCredentialCipher.MASTER_KEY_FILE, masterKeyFile.toString()), ref);
      return cipher.decrypt(new EncryptedSecret(ref, ciphertext, iv));
    } finally {
      Arrays.fill(ciphertext, (byte) 0);
      Arrays.fill(iv, (byte) 0);
    }
  }
}
