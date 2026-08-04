package happy.jayden.yang.agentbuilder.infrastructure.tool;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import happy.jayden.yang.agentbuilder.core.tool.ToolText;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class ToolContractCanonicalizer {

  private static final Set<String> ORDER_INSENSITIVE =
      Set.of("required", "enum", "tags", "requiredScopes");

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

  byte[] canonicalBytes(Map<String, Object> contract) {
    try {
      var json = objectMapper.writeValueAsString(canonicalValue(contract, null));
      ToolText.requireValidUnicode(json, "canonical Tool contract");
      var encoder =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      ByteBuffer buffer = encoder.encode(CharBuffer.wrap(json));
      var bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Tool contract cannot be canonicalized", exception);
    } catch (java.nio.charset.CharacterCodingException exception) {
      throw new IllegalArgumentException("Tool contract is not valid UTF-8", exception);
    }
  }

  private Object canonicalValue(Object value, String field) {
    if (value instanceof Map<?, ?> map) {
      var result = new TreeMap<String, Object>();
      map.forEach(
          (key, item) -> {
            if (!(key instanceof String stringKey)) {
              throw new IllegalArgumentException("Tool contract keys must be strings");
            }
            ToolText.requireValidUnicode(stringKey, "Tool contract key");
            result.put(stringKey, canonicalValue(item, stringKey));
          });
      return result;
    }
    if (value instanceof List<?> list) {
      var result = new ArrayList<>();
      list.forEach(item -> result.add(canonicalValue(item, null)));
      if (ORDER_INSENSITIVE.contains(field)) {
        result.sort(Comparator.comparing(this::sortKey));
      }
      return List.copyOf(result);
    }
    if (value instanceof BigDecimal decimal) {
      return normalize(decimal);
    }
    if (value instanceof Float || value instanceof Double) {
      return normalize(new BigDecimal(value.toString()));
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger
        || value instanceof Boolean) {
      return value;
    }
    if (value instanceof String text) {
      ToolText.requireValidUnicode(text, "Tool contract text");
      return text;
    }
    throw new IllegalArgumentException(
        "Tool contract contains unsupported canonical value "
            + (value == null ? "null" : value.getClass().getName()));
  }

  private String sortKey(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Tool contract array item cannot be canonicalized", exception);
    }
  }

  private static BigDecimal normalize(BigDecimal value) {
    var normalized = value.stripTrailingZeros();
    return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
  }
}
