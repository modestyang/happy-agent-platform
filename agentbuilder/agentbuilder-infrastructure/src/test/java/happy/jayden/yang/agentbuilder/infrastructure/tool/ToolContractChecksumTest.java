package happy.jayden.yang.agentbuilder.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolContractChecksumTest {

  @Test
  void normalizesBigDecimalScaleToPlainCanonicalNumber() {
    var checksums = new ToolContractChecksum();

    assertEquals(
        checksums.calculate(Map.of("number", new BigDecimal("1.0"))),
        checksums.calculate(Map.of("number", new BigDecimal("1.00"))));
    assertEquals(
        checksums.calculate(Map.of("number", new BigDecimal("1000.0"))),
        checksums.calculate(Map.of("number", new BigDecimal("1E+3"))));
  }

  @Test
  void normalizesOrderInsensitiveRequiredAndEnumArrays() {
    var checksums = new ToolContractChecksum();
    var first = schema(List.of("alpha", "beta"), List.of("LOW", "HIGH"));
    var second = schema(List.of("beta", "alpha"), List.of("HIGH", "LOW"));

    assertEquals(checksums.calculate(first), checksums.calculate(second));
  }

  @Test
  void rejectsInvalidUnicodeBeforeUtf8Encoding() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolContractChecksum().calculate(Map.of("description", "bad\ud800value")));
  }

  private static Map<String, Object> schema(List<String> required, List<String> values) {
    var property = new LinkedHashMap<String, Object>();
    property.put("type", "string");
    property.put("description", "级别");
    property.put("enum", values);
    var result = new LinkedHashMap<String, Object>();
    result.put("type", "object");
    result.put("properties", Map.of("level", property));
    result.put("required", required);
    result.put("additionalProperties", false);
    return result;
  }
}
