package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Serializes tool results as JSON and validates the supported JSON Schema contract. */
final class ToolOutputCodec {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private ToolOutputCodec() {}

  static String encode(Object value, Map<String, Object> schema) {
    var node = MAPPER.valueToTree(value);
    validate(node, schema, "$result");
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new InvalidToolOutputException("tool output is not JSON serializable", error);
    }
  }

  static void validateInput(Object value, Map<String, Object> schema) {
    validate(MAPPER.valueToTree(value), schema, "$arguments");
  }

  @SuppressWarnings("unchecked")
  private static void validate(JsonNode node, Map<String, Object> schema, String path) {
    if (schema.containsKey("enum")) {
      var allowed = MAPPER.valueToTree(schema.get("enum"));
      if (!allowed.isArray() || !contains(allowed, node)) {
        invalid(path, "is not an allowed enum value");
      }
    }
    if (schema.containsKey("const") && !MAPPER.valueToTree(schema.get("const")).equals(node)) {
      invalid(path, "does not match const");
    }
    var type = schema.get("type");
    if (type instanceof List<?> types) {
      if (types.stream().noneMatch(candidate -> matchesType(node, String.valueOf(candidate)))) {
        invalid(path, "has the wrong type");
      }
    } else if (type != null && !matchesType(node, String.valueOf(type))) {
      invalid(path, "must be " + type);
    }

    if (node.isObject()) {
      var properties =
          schema.get("properties") instanceof Map<?, ?> map
              ? (Map<String, Object>) map
              : Map.<String, Object>of();
      if (schema.get("required") instanceof List<?> required) {
        for (var name : required) {
          if (!node.has(String.valueOf(name))) {
            invalid(path, "is missing required property " + name);
          }
        }
      }
      var fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        var propertySchema = properties.get(field.getKey());
        if (propertySchema instanceof Map<?, ?> propertyMap) {
          validate(
              field.getValue(), (Map<String, Object>) propertyMap, path + "." + field.getKey());
        } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
          invalid(path, "contains undeclared property " + field.getKey());
        }
      }
    }

    if (node.isArray()) {
      if (schema.get("minItems") instanceof Number minimum && node.size() < minimum.intValue()) {
        invalid(path, "has too few items");
      }
      if (schema.get("maxItems") instanceof Number maximum && node.size() > maximum.intValue()) {
        invalid(path, "has too many items");
      }
      if (schema.get("items") instanceof Map<?, ?> itemSchema) {
        for (var index = 0; index < node.size(); index++) {
          validate(node.get(index), (Map<String, Object>) itemSchema, path + "[" + index + "]");
        }
      }
    }

    if (node.isTextual()) {
      var text = node.textValue();
      if (schema.get("minLength") instanceof Number minimum && text.length() < minimum.intValue()) {
        invalid(path, "is too short");
      }
      if (schema.get("maxLength") instanceof Number maximum && text.length() > maximum.intValue()) {
        invalid(path, "is too long");
      }
      if (schema.get("pattern") instanceof String pattern
          && !Pattern.compile(pattern).matcher(text).matches()) {
        invalid(path, "does not match pattern");
      }
    }

    if (node.isNumber()) {
      var number = node.decimalValue();
      compare(number, schema.get("minimum"), path, false, true);
      compare(number, schema.get("maximum"), path, false, false);
      compare(number, schema.get("exclusiveMinimum"), path, true, true);
      compare(number, schema.get("exclusiveMaximum"), path, true, false);
    }
  }

  private static boolean contains(JsonNode array, JsonNode expected) {
    for (var candidate : array) {
      if (candidate.equals(expected)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesType(JsonNode node, String type) {
    return switch (type) {
      case "object" -> node.isObject();
      case "array" -> node.isArray();
      case "string" -> node.isTextual();
      case "integer" -> node.isIntegralNumber();
      case "number" -> node.isNumber();
      case "boolean" -> node.isBoolean();
      case "null" -> node.isNull();
      default -> true;
    };
  }

  private static void compare(
      BigDecimal value, Object bound, String path, boolean exclusive, boolean minimum) {
    if (!(bound instanceof Number number)) {
      return;
    }
    var comparison = value.compareTo(new BigDecimal(number.toString()));
    var invalid =
        minimum
            ? (exclusive ? comparison <= 0 : comparison < 0)
            : (exclusive ? comparison >= 0 : comparison > 0);
    if (invalid) {
      invalid(path, "is outside the allowed numeric range");
    }
  }

  private static void invalid(String path, String message) {
    throw new InvalidToolOutputException(path + " " + message);
  }

  static final class InvalidToolOutputException extends RuntimeException {
    private InvalidToolOutputException(String message) {
      super(message);
    }

    private InvalidToolOutputException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
