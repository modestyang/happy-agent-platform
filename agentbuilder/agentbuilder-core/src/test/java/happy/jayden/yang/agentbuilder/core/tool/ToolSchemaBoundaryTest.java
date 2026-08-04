package happy.jayden.yang.agentbuilder.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemaBoundaryTest {

  @Test
  void rejectsOpenObjectsUnknownRequiredPropertiesAndUndescribedProperties() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolSchema(object(Map.of("name", string("姓名")), List.of("name"), true)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolSchema(object(Map.of("name", string("姓名")), List.of("missing"), false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolSchema(
                object(
                    Map.of("name", new LinkedHashMap<>(Map.of("type", "string"))),
                    List.of("name"),
                    false)));
  }

  @Test
  void rejectsArraysWithoutItemsAndIllegalSchemaShapes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolSchema(new LinkedHashMap<>(Map.of("type", "array"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolSchema(
                new LinkedHashMap<>(
                    Map.of("type", "string", "properties", Map.of(), "description", "bad"))));
  }

  @Test
  void validatesExamplesAgainstEnumPatternLengthAndNumericBounds() {
    assertDoesNotThrow(
        () ->
            new ToolSchema(
                object(
                    Map.of(
                        "level",
                        property(
                            "string",
                            "级别",
                            Map.of(
                                "enum", List.of("LOW", "HIGH"),
                                "examples", List.of("LOW"))),
                        "code",
                        property(
                            "string",
                            "编码",
                            Map.of(
                                "pattern",
                                "^[A-Z]{2}$",
                                "minLength",
                                2,
                                "maxLength",
                                2,
                                "examples",
                                List.of("AB"))),
                        "score",
                        property(
                            "number",
                            "分数",
                            Map.of(
                                "minimum", BigDecimal.ONE,
                                "maximum", BigDecimal.TEN,
                                "examples", List.of(new BigDecimal("1.00"))))),
                    List.of("level", "code", "score"),
                    false)));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolSchema(
                object(
                    Map.of(
                        "level",
                        property(
                            "string",
                            "级别",
                            Map.of(
                                "enum", List.of("LOW", "HIGH"),
                                "examples", List.of("MEDIUM")))),
                    List.of("level"),
                    false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolSchema(
                object(
                    Map.of(
                        "code",
                        property(
                            "string",
                            "编码",
                            Map.of(
                                "pattern",
                                "^[A-Z]{2}$",
                                "minLength",
                                2,
                                "maxLength",
                                2,
                                "examples",
                                List.of("abc")))),
                    List.of("code"),
                    false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolSchema(
                object(
                    Map.of(
                        "score",
                        property(
                            "number",
                            "分数",
                            Map.of(
                                "minimum", 1,
                                "maximum", 10,
                                "examples", List.of(11)))),
                    List.of("score"),
                    false)));
  }

  @Test
  void validatesObjectExamplesAsStructuredValuesAndRejectsSurrogatesRecursively() {
    var address = object(Map.of("city", string("城市")), List.of("city"), false);
    address.put("description", "地址");
    address.put("examples", List.of(Map.of("city", "上海")));
    assertDoesNotThrow(
        () -> new ToolSchema(object(Map.of("address", address), List.of("address"), false)));

    var disguisedObject = new LinkedHashMap<>(address);
    disguisedObject.put("examples", List.of("{\"city\":\"上海\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolSchema(object(Map.of("address", disguisedObject), List.of("address"), false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolSchema(
                object(Map.of("name", string("bad\ud800description")), List.of("name"), false)));
  }

  private static Map<String, Object> string(String description) {
    return property("string", description, Map.of());
  }

  private static Map<String, Object> property(
      String type, String description, Map<String, Object> extras) {
    var result = new LinkedHashMap<String, Object>();
    result.put("type", type);
    result.put("description", description);
    result.putAll(extras);
    return result;
  }

  private static Map<String, Object> object(
      Map<String, ?> properties, List<String> required, boolean additionalProperties) {
    var result = new LinkedHashMap<String, Object>();
    result.put("type", "object");
    result.put("properties", properties);
    result.put("required", required);
    result.put("additionalProperties", additionalProperties);
    return result;
  }
}
