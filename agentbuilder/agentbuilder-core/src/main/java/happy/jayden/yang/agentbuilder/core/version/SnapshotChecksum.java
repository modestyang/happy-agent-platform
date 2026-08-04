package happy.jayden.yang.agentbuilder.core.version;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SnapshotChecksum {
  private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

  private SnapshotChecksum() {}

  public static String sha256(String value) {
    Objects.requireNonNull(value, "value");
    try {
      var encoder =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      var encoded = encoder.encode(CharBuffer.wrap(value));
      var bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (CharacterCodingException invalidUnicode) {
      throw new IllegalArgumentException(
          "value must contain only Unicode scalar values", invalidUnicode);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }

  static void requireChecksum(String value) {
    Objects.requireNonNull(value, "checksum");
    if (!SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException("checksum must be a 64-character lowercase SHA-256 value");
    }
  }
}
