package happy.jayden.yang.agentbuilder.infrastructure.security;

import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.provider.CredentialCipher;
import happy.jayden.yang.agentbuilder.core.component.provider.EncryptedSecret;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmCredentialCipher implements CredentialCipher {
  public static final String MASTER_KEY_FILE = "HAPPY_AGENT_MASTER_KEY_FILE";
  private final SecretKeySpec key;
  private final ComponentRef component;
  private final SecureRandom random = new SecureRandom();

  private AesGcmCredentialCipher(byte[] keyBytes, ComponentRef component) {
    try {
      if (keyBytes.length != 32)
        throw new IllegalArgumentException("master key must be exactly 256 bits");
      this.key = new SecretKeySpec(keyBytes, "AES");
      this.component = Objects.requireNonNull(component, "component");
    } finally {
      Arrays.fill(keyBytes, (byte) 0);
    }
  }

  public static AesGcmCredentialCipher fromEnvironment(
      Map<String, String> environment, ComponentRef component) {
    var keyFile = Objects.requireNonNull(environment, "environment").get(MASTER_KEY_FILE);
    if (keyFile == null || keyFile.isBlank())
      throw new IllegalStateException(MASTER_KEY_FILE + " is required");
    byte[] encoded = null;
    byte[] normalized = null;
    try {
      encoded = Files.readAllBytes(Path.of(keyFile));
      normalized = stripAsciiWhitespace(encoded);
      var decoded = Base64.getDecoder().decode(normalized);
      return new AesGcmCredentialCipher(decoded, component);
    } catch (java.io.IOException | IllegalArgumentException exception) {
      if (exception instanceof IllegalArgumentException invalid) throw invalid;
      throw new IllegalStateException("cannot read master key file", exception);
    } finally {
      if (encoded != null) Arrays.fill(encoded, (byte) 0);
      if (normalized != null) Arrays.fill(normalized, (byte) 0);
    }
  }

  @Override
  public EncryptedSecret encrypt(char[] plaintext) {
    Objects.requireNonNull(plaintext, "plaintext");
    var bytes = SensitiveBuffers.encodeAndClear(plaintext);
    var iv = new byte[12];
    random.nextBytes(iv);
    try {
      var cipher = cipher(Cipher.ENCRYPT_MODE, iv);
      return new EncryptedSecret(component, cipher.doFinal(bytes), iv);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("credential encryption failed", exception);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  @Override
  public char[] decrypt(EncryptedSecret encrypted) {
    Objects.requireNonNull(encrypted, "encrypted");
    if (!component.equals(encrypted.component()))
      throw new SecurityException("credential component identity mismatch");
    try {
      var bytes = cipher(Cipher.DECRYPT_MODE, encrypted.iv()).doFinal(encrypted.ciphertext());
      try {
        return SensitiveBuffers.copyAndClear(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes)));
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
    } catch (GeneralSecurityException exception) {
      throw new SecurityException("credential authentication failed", exception);
    }
  }

  private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(128, iv));
    cipher.updateAAD(
        (component.componentKey().value() + "\u0000" + component.version().value())
            .getBytes(StandardCharsets.UTF_8));
    return cipher;
  }

  private static byte[] stripAsciiWhitespace(byte[] encoded) {
    var normalized = new byte[encoded.length];
    try {
      int length = 0;
      for (var value : encoded)
        if (value != ' ' && value != '\n' && value != '\r' && value != '\t')
          normalized[length++] = value;
      return Arrays.copyOf(normalized, length);
    } finally {
      Arrays.fill(normalized, (byte) 0);
    }
  }
}
