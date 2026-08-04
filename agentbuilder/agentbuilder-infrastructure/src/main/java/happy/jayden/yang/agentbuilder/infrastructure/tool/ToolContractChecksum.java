package happy.jayden.yang.agentbuilder.infrastructure.tool;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

final class ToolContractChecksum {

  private final ToolContractCanonicalizer canonicalizer = new ToolContractCanonicalizer();

  String calculate(Map<String, Object> contract) {
    return HexFormat.of().formatHex(sha256().digest(canonicalizer.canonicalBytes(contract)));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
