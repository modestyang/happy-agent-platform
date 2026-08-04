package happy.jayden.yang.agentbuilder.core.tool;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class ToolSchemaValidator {

  private static final Set<String> COMMON = Set.of("type", "title", "description", "examples");
  private static final Set<String> OBJECT =
      Set.of(
          "type",
          "title",
          "description",
          "examples",
          "properties",
          "required",
          "additionalProperties");
  private static final Set<String> ARRAY =
      Set.of("type", "title", "description", "examples", "items", "minItems", "maxItems");
  private static final Set<String> STRING =
      Set.of(
          "type",
          "title",
          "description",
          "examples",
          "enum",
          "format",
          "minLength",
          "maxLength",
          "pattern");
  private static final Set<String> NUMBER =
      Set.of("type", "title", "description", "examples", "enum", "minimum", "maximum");

  private ToolSchemaValidator() {}

  static void validate(Map<?, ?> document) {
    validateSchema(document, false, "$schema");
  }

  private static void validateSchema(Map<?, ?> schema, boolean descriptionRequired, String path) {
    var type = text(schema.get("type"), path + ".type");
    var allowed = allowed(type, path);
    for (var key : schema.keySet()) {
      if (!(key instanceof String stringKey) || !allowed.contains(stringKey)) {
        throw new IllegalArgumentException(path + " contains unsupported schema key " + key);
      }
      ToolText.require(stringKey, 1, 120, path + " key");
    }
    if (descriptionRequired) {
      ToolText.require(
          text(schema.get("description"), path + ".description"), 1, 2_000, path + ".description");
    } else if (schema.containsKey("description")) {
      ToolText.require(
          text(schema.get("description"), path + ".description"), 1, 2_000, path + ".description");
    }
    if (schema.containsKey("title")) {
      ToolText.require(text(schema.get("title"), path + ".title"), 1, 160, path + ".title");
    }

    switch (type) {
      case "object" -> validateObject(schema, path);
      case "array" -> validateArray(schema, path);
      case "string" -> validateString(schema, path);
      case "integer", "number" -> validateNumberSchema(schema, path);
      case "boolean" -> validateBoolean(schema, path);
      default -> throw new IllegalArgumentException(path + " has unsupported type " + type);
    }
    validateExamples(schema, path);
  }

  private static void validateObject(Map<?, ?> schema, String path) {
    if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
      throw new IllegalArgumentException(path + ".properties must be an object");
    }
    if (!(schema.get("required") instanceof List<?> required)) {
      throw new IllegalArgumentException(path + ".required must be an array");
    }
    if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
      throw new IllegalArgumentException(path + ".additionalProperties must be false");
    }
    var propertyNames = new HashSet<String>();
    for (var entry : properties.entrySet()) {
      var name = text(entry.getKey(), path + ".properties key");
      ToolText.require(name, 1, 120, path + ".properties key");
      if (!propertyNames.add(name)) {
        throw new IllegalArgumentException(path + " has duplicate property " + name);
      }
      if (!(entry.getValue() instanceof Map<?, ?> propertySchema)) {
        throw new IllegalArgumentException(path + ".properties." + name + " must be a schema");
      }
      validateSchema(propertySchema, true, path + ".properties." + name);
    }
    var requiredNames = new HashSet<String>();
    for (var value : required) {
      var name = text(value, path + ".required item");
      if (!requiredNames.add(name)) {
        throw new IllegalArgumentException(path + ".required must contain unique names");
      }
      if (!propertyNames.contains(name)) {
        throw new IllegalArgumentException(path + ".required references unknown property " + name);
      }
    }
  }

  private static void validateArray(Map<?, ?> schema, String path) {
    if (!(schema.get("items") instanceof Map<?, ?> items)) {
      throw new IllegalArgumentException(path + ".items must be a schema");
    }
    validateSchema(items, false, path + ".items");
    validateNonNegativeBounds(schema, "minItems", "maxItems", path);
  }

  private static void validateString(Map<?, ?> schema, String path) {
    validateNonNegativeBounds(schema, "minLength", "maxLength", path);
    if (schema.containsKey("pattern")) {
      var pattern = text(schema.get("pattern"), path + ".pattern");
      try {
        Pattern.compile(pattern);
      } catch (PatternSyntaxException exception) {
        throw new IllegalArgumentException(path + ".pattern is invalid", exception);
      }
    }
    if (schema.containsKey("format")) {
      ToolText.require(text(schema.get("format"), path + ".format"), 1, 80, path + ".format");
    }
    validateEnum(schema, path, "string");
  }

  private static void validateNumberSchema(Map<?, ?> schema, String path) {
    var minimum = decimal(schema.get("minimum"), path + ".minimum", false);
    var maximum = decimal(schema.get("maximum"), path + ".maximum", false);
    if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(path + ".minimum cannot exceed maximum");
    }
    validateEnum(schema, path, text(schema.get("type"), path + ".type"));
  }

  private static void validateBoolean(Map<?, ?> schema, String path) {
    if (!schema.keySet().stream().allMatch(COMMON::contains)) {
      throw new IllegalArgumentException(path + " contains invalid boolean constraints");
    }
  }

  private static void validateEnum(Map<?, ?> schema, String path, String type) {
    if (!schema.containsKey("enum")) {
      return;
    }
    if (!(schema.get("enum") instanceof List<?> values) || values.isEmpty()) {
      throw new IllegalArgumentException(path + ".enum must be a non-empty array");
    }
    var unique = new HashSet<>();
    for (var value : values) {
      validateScalar(type, value, path + ".enum");
      if (!unique.add(value)) {
        throw new IllegalArgumentException(path + ".enum must contain unique values");
      }
    }
  }

  private static void validateExamples(Map<?, ?> schema, String path) {
    if (!schema.containsKey("examples")) {
      return;
    }
    if (!(schema.get("examples") instanceof List<?> examples) || examples.isEmpty()) {
      throw new IllegalArgumentException(path + ".examples must be a non-empty array");
    }
    for (var example : examples) {
      validateExample(schema, example, path + ".examples");
    }
  }

  private static void validateExample(Map<?, ?> schema, Object value, String path) {
    var type = text(schema.get("type"), path + ".type");
    switch (type) {
      case "object" -> validateObjectExample(schema, value, path);
      case "array" -> validateArrayExample(schema, value, path);
      case "string" -> validateStringExample(schema, value, path);
      case "integer", "number" -> validateNumberExample(schema, value, path, type);
      case "boolean" -> validateScalar("boolean", value, path);
      default -> throw new IllegalArgumentException(path + " has unsupported type " + type);
    }
  }

  private static void validateObjectExample(Map<?, ?> schema, Object value, String path) {
    if (!(value instanceof Map<?, ?> example)) {
      throw new IllegalArgumentException(path + " object example must be a JSON object");
    }
    var properties = (Map<?, ?>) schema.get("properties");
    var required = (List<?>) schema.get("required");
    for (var name : required) {
      if (!example.containsKey(name)) {
        throw new IllegalArgumentException(
            path + " object example misses required property " + name);
      }
    }
    for (var entry : example.entrySet()) {
      var name = text(entry.getKey(), path + " property");
      if (!(properties.get(name) instanceof Map<?, ?> propertySchema)) {
        throw new IllegalArgumentException(
            path + " object example contains unknown property " + name);
      }
      validateExample(propertySchema, entry.getValue(), path + "." + name);
    }
  }

  private static void validateArrayExample(Map<?, ?> schema, Object value, String path) {
    if (!(value instanceof List<?> values)) {
      throw new IllegalArgumentException(path + " array example must be a JSON array");
    }
    var items = (Map<?, ?>) schema.get("items");
    for (var item : values) {
      validateExample(items, item, path + "[]");
    }
    validateCount(values.size(), schema, "minItems", "maxItems", path);
  }

  private static void validateStringExample(Map<?, ?> schema, Object value, String path) {
    validateScalar("string", value, path);
    var string = (String) value;
    ToolText.requireValidUnicode(string, path);
    validateCount(
        string.codePointCount(0, string.length()), schema, "minLength", "maxLength", path);
    if (schema.containsKey("pattern")
        && !Pattern.compile((String) schema.get("pattern")).matcher(string).matches()) {
      throw new IllegalArgumentException(path + " does not match pattern");
    }
    if (schema.containsKey("enum") && !((List<?>) schema.get("enum")).contains(value)) {
      throw new IllegalArgumentException(path + " is not an allowed enum value");
    }
  }

  private static void validateNumberExample(
      Map<?, ?> schema, Object value, String path, String type) {
    validateScalar(type, value, path);
    var number = decimal(value, path, true);
    var minimum = decimal(schema.get("minimum"), path + ".minimum", false);
    var maximum = decimal(schema.get("maximum"), path + ".maximum", false);
    if (minimum != null && number.compareTo(minimum) < 0) {
      throw new IllegalArgumentException(path + " is below minimum");
    }
    if (maximum != null && number.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(path + " exceeds maximum");
    }
    if (schema.containsKey("enum")
        && ((List<?>) schema.get("enum"))
            .stream()
                .map(item -> decimal(item, path + ".enum", true))
                .noneMatch(item -> item.compareTo(number) == 0)) {
      throw new IllegalArgumentException(path + " is not an allowed enum value");
    }
  }

  private static void validateScalar(String type, Object value, String path) {
    var valid =
        switch (type) {
          case "string" -> value instanceof String;
          case "boolean" -> value instanceof Boolean;
          case "number" -> value instanceof Number;
          case "integer" -> isInteger(value);
          default -> false;
        };
    if (!valid) {
      throw new IllegalArgumentException(path + " does not match " + type + " schema");
    }
    if (value instanceof String text) {
      ToolText.requireValidUnicode(text, path);
    }
  }

  private static boolean isInteger(Object value) {
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger) {
      return true;
    }
    return value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0;
  }

  private static void validateNonNegativeBounds(
      Map<?, ?> schema, String minimumName, String maximumName, String path) {
    var minimum = integer(schema.get(minimumName), path + "." + minimumName, false);
    var maximum = integer(schema.get(maximumName), path + "." + maximumName, false);
    if ((minimum != null && minimum < 0) || (maximum != null && maximum < 0)) {
      throw new IllegalArgumentException(path + " bounds cannot be negative");
    }
    if (minimum != null && maximum != null && minimum > maximum) {
      throw new IllegalArgumentException(path + " minimum cannot exceed maximum");
    }
  }

  private static void validateCount(
      int value, Map<?, ?> schema, String minimumName, String maximumName, String path) {
    var minimum = integer(schema.get(minimumName), path + "." + minimumName, false);
    var maximum = integer(schema.get(maximumName), path + "." + maximumName, false);
    if ((minimum != null && value < minimum) || (maximum != null && value > maximum)) {
      throw new IllegalArgumentException(path + " violates size constraints");
    }
  }

  private static Integer integer(Object value, String path, boolean required) {
    if (value == null && !required) {
      return null;
    }
    if (!(value instanceof Integer integer)) {
      throw new IllegalArgumentException(path + " must be an integer");
    }
    return integer;
  }

  private static BigDecimal decimal(Object value, String path, boolean required) {
    if (value == null && !required) {
      return null;
    }
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(path + " must be numeric");
    }
    return new BigDecimal(number.toString());
  }

  private static Set<String> allowed(String type, String path) {
    return switch (type) {
      case "object" -> OBJECT;
      case "array" -> ARRAY;
      case "string" -> STRING;
      case "integer", "number" -> NUMBER;
      case "boolean" -> COMMON;
      default -> throw new IllegalArgumentException(path + " has unsupported type " + type);
    };
  }

  private static String text(Object value, String path) {
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException(path + " must be a string");
    }
    ToolText.requireValidUnicode(text, path);
    return text;
  }
}
