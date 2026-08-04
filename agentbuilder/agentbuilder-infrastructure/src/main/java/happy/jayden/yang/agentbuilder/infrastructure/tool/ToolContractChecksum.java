package happy.jayden.yang.agentbuilder.infrastructure.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

final class ToolContractChecksum {

  private final ObjectMapper objectMapper;

  ToolContractChecksum() {
    objectMapper = new ObjectMapper();
    objectMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  String calculate(Map<String, Object> contract) {
    try {
      var canonical = objectMapper.writeValueAsBytes(contract);
      return HexFormat.of().formatHex(sha256().digest(canonical));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Tool contract cannot be canonicalized", exception);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
