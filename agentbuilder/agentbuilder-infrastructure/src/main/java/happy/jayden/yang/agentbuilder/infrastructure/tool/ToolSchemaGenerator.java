package happy.jayden.yang.agentbuilder.infrastructure.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolSchema;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class ToolSchemaGenerator {

  private final ObjectMapper exampleMapper =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

  ToolSchema inputSchema(ToolMethodDefinition method) {
    var properties = new LinkedHashMap<String, Object>();
    var required = new ArrayList<String>();
    for (var parameter : method.modelParameters()) {
      var metadata = parameter.metadata();
      var name = parameter.name();
      properties.put(
          name,
          describedSchema(
              parameter.type(),
              metadata,
              new HashSet<>(),
              method.contractMethod().toGenericString()));
      if (metadata.required() && parameter.rawType() != Optional.class) {
        required.add(name);
      }
    }
    return new ToolSchema(objectSchema(properties, required));
  }

  ToolSchema outputSchema(Method method, String outputDescription) {
    if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
      throw new IllegalArgumentException("Tool return type must expose an output schema");
    }
    var schema = schema(method.getGenericReturnType(), new HashSet<>(), method.toGenericString());
    if (!outputDescription.isBlank()) {
      schema.put("description", requireDescription(outputDescription, "outputDescription"));
    } else if (!"object".equals(schema.get("type"))) {
      throw new IllegalArgumentException(
          "outputDescription must describe non-object return type on " + method.toGenericString());
    }
    return new ToolSchema(schema);
  }

  private Map<String, Object> describedSchema(
      Type type, AgentToolParam metadata, Set<Type> visiting, String location) {
    var schema = schema(type, visiting, location);
    schema.put("description", requireDescription(metadata.description(), location));
    if (!metadata.example().isBlank()) {
      schema.put("examples", List.of(exampleValue(schema.get("type"), metadata.example())));
    }
    applyConstraints(schema, metadata, location);
    return schema;
  }

  private Map<String, Object> schema(Type type, Set<Type> visiting, String location) {
    if (type instanceof TypeVariable<?> || type instanceof WildcardType) {
      throw new IllegalArgumentException(
          "unresolved generic Tool type at " + location + ": " + type);
    }
    if (type instanceof GenericArrayType arrayType) {
      return arraySchema(arrayType.getGenericComponentType(), visiting, location);
    }
    if (type instanceof ParameterizedType parameterized) {
      var raw = rawClass(parameterized.getRawType());
      if (raw == Optional.class) {
        return schema(parameterized.getActualTypeArguments()[0], visiting, location);
      }
      if (Collection.class.isAssignableFrom(raw)) {
        return arraySchema(parameterized.getActualTypeArguments()[0], visiting, location);
      }
      if (Map.class.isAssignableFrom(raw)) {
        throw new IllegalArgumentException(
            "Map Tool fields cannot produce a closed strict schema at " + location);
      }
      return objectSchema(raw, visiting, location);
    }

    var raw = rawClass(type);
    if (raw == LocalDateTime.class) {
      throw new IllegalArgumentException(
          "LocalDateTime is not supported for Tool schemas at "
              + location
              + "; use OffsetDateTime or Instant");
    }
    if (raw == String.class
        || raw == char.class
        || raw == Character.class
        || raw == UUID.class
        || raw == Instant.class
        || raw == LocalDate.class
        || raw == OffsetDateTime.class) {
      var result = typed("string");
      if (raw == UUID.class) {
        result.put("format", "uuid");
      } else if (raw == LocalDate.class) {
        result.put("format", "date");
      } else if (raw == Instant.class || raw == OffsetDateTime.class) {
        result.put("format", "date-time");
      }
      return result;
    }
    if (raw == boolean.class || raw == Boolean.class) {
      return typed("boolean");
    }
    if (raw == byte.class
        || raw == Byte.class
        || raw == short.class
        || raw == Short.class
        || raw == int.class
        || raw == Integer.class
        || raw == long.class
        || raw == Long.class
        || raw == BigInteger.class) {
      return typed("integer");
    }
    if (raw == float.class
        || raw == Float.class
        || raw == double.class
        || raw == Double.class
        || raw == BigDecimal.class) {
      return typed("number");
    }
    if (raw.isEnum()) {
      var result = typed("string");
      result.put(
          "enum",
          java.util.Arrays.stream(raw.getEnumConstants())
              .map(value -> ((Enum<?>) value).name())
              .toList());
      return result;
    }
    if (raw.isArray()) {
      return arraySchema(raw.getComponentType(), visiting, location);
    }
    if (Map.class.isAssignableFrom(raw)) {
      throw new IllegalArgumentException(
          "Map Tool fields cannot produce a closed strict schema at " + location);
    }
    if (Collection.class.isAssignableFrom(raw)) {
      throw new IllegalArgumentException(
          "Collection Tool fields must declare an element type at " + location);
    }
    return objectSchema(raw, visiting, location);
  }

  private Map<String, Object> arraySchema(Type itemType, Set<Type> visiting, String location) {
    var result = typed("array");
    result.put("items", schema(itemType, visiting, location + "[]"));
    return result;
  }

  private Map<String, Object> objectSchema(Class<?> type, Set<Type> visiting, String location) {
    if (!visiting.add(type)) {
      throw new IllegalArgumentException("recursive Tool DTO is not supported at " + location);
    }
    try {
      var properties = new LinkedHashMap<String, Object>();
      var required = new ArrayList<String>();
      if (type.isRecord()) {
        for (var component : type.getRecordComponents()) {
          addRecordProperty(component, properties, required, visiting, location);
        }
      } else {
        var fields = allModelFields(type);
        validateConstructibleBean(type, fields, location);
        for (var field : fields) {
          addFieldProperty(field, properties, required, visiting, location);
        }
      }
      return objectSchema(properties, required);
    } finally {
      visiting.remove(type);
    }
  }

  private void addRecordProperty(
      RecordComponent component,
      Map<String, Object> properties,
      List<String> required,
      Set<Type> visiting,
      String location) {
    var metadata = component.getAnnotation(AgentToolParam.class);
    if (metadata == null) {
      throw new IllegalArgumentException(
          "record component "
              + component.getName()
              + " at "
              + location
              + " must declare @AgentToolParam");
    }
    addProperty(
        component.getName(),
        component.getGenericType(),
        metadata,
        properties,
        required,
        visiting,
        location);
  }

  private void addFieldProperty(
      Field field,
      Map<String, Object> properties,
      List<String> required,
      Set<Type> visiting,
      String location) {
    var metadata = field.getAnnotation(AgentToolParam.class);
    if (metadata == null) {
      throw new IllegalArgumentException(
          "field " + field.getName() + " at " + location + " must declare @AgentToolParam");
    }
    addProperty(
        field.getName(),
        field.getGenericType(),
        metadata,
        properties,
        required,
        visiting,
        location);
  }

  private void addProperty(
      String javaName,
      Type type,
      AgentToolParam metadata,
      Map<String, Object> properties,
      List<String> required,
      Set<Type> visiting,
      String location) {
    var name = parameterName(metadata, javaName);
    if (properties.putIfAbsent(name, describedSchema(type, metadata, visiting, location)) != null) {
      throw new IllegalArgumentException(
          "duplicate Tool DTO property name " + name + " at " + location);
    }
    if (metadata.required() && rawClass(type) != Optional.class) {
      required.add(name);
    }
  }

  private static List<Field> allModelFields(Class<?> type) {
    var fields = new ArrayList<Field>();
    for (var current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      java.util.Arrays.stream(current.getDeclaredFields())
          .filter(field -> !field.isSynthetic())
          .filter(field -> !Modifier.isStatic(field.getModifiers()))
          .filter(field -> !Modifier.isTransient(field.getModifiers()))
          .forEach(fields::add);
    }
    fields.sort(Comparator.comparing(Field::getName));
    return fields;
  }

  private static void validateConstructibleBean(
      Class<?> type, List<Field> fields, String location) {
    if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
      throw new IllegalArgumentException(
          "Tool DTO "
              + type.getName()
              + " at "
              + location
              + " must be a concrete non-record class");
    }
    for (var field : fields) {
      if (!isWritableBeanProperty(type, field)) {
        throw new IllegalArgumentException(
            "field " + field.getName() + " at " + location + " must be a writable bean property");
      }
    }
    try {
      type.getConstructor();
    } catch (NoSuchMethodException exception) {
      throw new IllegalArgumentException(
          "Tool DTO "
              + type.getName()
              + " at "
              + location
              + " must have a public no-argument constructor",
          exception);
    }
  }

  private static boolean isWritableBeanProperty(Class<?> type, Field field) {
    if (Modifier.isPublic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
      return true;
    }
    var setterName =
        "set" + Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
    return java.util.Arrays.stream(type.getMethods())
        .anyMatch(
            method ->
                method.getName().equals(setterName)
                    && method.getParameterCount() == 1
                    && method.getGenericParameterTypes()[0].equals(field.getGenericType()));
  }

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, List<String> required) {
    var result = typed("object");
    result.put("properties", properties);
    result.put("required", required);
    result.put("additionalProperties", false);
    return result;
  }

  private static Map<String, Object> typed(String type) {
    var result = new LinkedHashMap<String, Object>();
    result.put("type", type);
    return result;
  }

  private static void applyConstraints(
      Map<String, Object> schema, AgentToolParam metadata, String location) {
    var type = schema.get("type");
    if (metadata.minLength() < -1 || metadata.maxLength() < -1) {
      throw new IllegalArgumentException("length constraints cannot be below -1 at " + location);
    }
    if (metadata.minLength() >= 0 || metadata.maxLength() >= 0 || !metadata.pattern().isBlank()) {
      if (!"string".equals(type)) {
        throw new IllegalArgumentException(
            "string constraints require a string field at " + location);
      }
      if (metadata.minLength() >= 0) {
        schema.put("minLength", metadata.minLength());
      }
      if (metadata.maxLength() >= 0) {
        schema.put("maxLength", metadata.maxLength());
      }
      if (metadata.minLength() >= 0
          && metadata.maxLength() >= 0
          && metadata.minLength() > metadata.maxLength()) {
        throw new IllegalArgumentException("minLength cannot exceed maxLength at " + location);
      }
      if (!metadata.pattern().isBlank()) {
        try {
          Pattern.compile(metadata.pattern());
        } catch (PatternSyntaxException exception) {
          throw new IllegalArgumentException("invalid pattern at " + location, exception);
        }
        schema.put("pattern", metadata.pattern());
      }
    }
    var hasMinimum = metadata.minimum() != Long.MIN_VALUE;
    var hasMaximum = metadata.maximum() != Long.MAX_VALUE;
    if (hasMinimum || hasMaximum) {
      if (!"integer".equals(type) && !"number".equals(type)) {
        throw new IllegalArgumentException(
            "numeric constraints require a number field at " + location);
      }
      if (hasMinimum) {
        schema.put("minimum", metadata.minimum());
      }
      if (hasMaximum) {
        schema.put("maximum", metadata.maximum());
      }
      if (hasMinimum && hasMaximum && metadata.minimum() > metadata.maximum()) {
        throw new IllegalArgumentException("minimum cannot exceed maximum at " + location);
      }
    }
  }

  private Object exampleValue(Object type, String value) {
    try {
      if ("integer".equals(type)) {
        return Long.parseLong(value);
      }
      if ("number".equals(type)) {
        return new BigDecimal(value);
      }
      if ("boolean".equals(type)) {
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
          throw new IllegalArgumentException("boolean example must be true or false");
        }
        return Boolean.parseBoolean(value);
      }
      if ("object".equals(type) || "array".equals(type)) {
        var parsed = exampleMapper.readValue(value, Object.class);
        if ("object".equals(type) && !(parsed instanceof Map<?, ?>)
            || "array".equals(type) && !(parsed instanceof List<?>)) {
          throw new IllegalArgumentException(type + " example must be structured JSON");
        }
        return parsed;
      }
      return value;
    } catch (NumberFormatException | JsonProcessingException exception) {
      throw new IllegalArgumentException("example does not match schema type " + type, exception);
    }
  }

  private static String parameterName(AgentToolParam metadata, String javaName) {
    var value = metadata.name().isBlank() ? javaName : metadata.name();
    if (value.isBlank() || value.length() > 120) {
      throw new IllegalArgumentException("Tool parameter name must contain 1 to 120 characters");
    }
    return value;
  }

  private static String requireDescription(String value, String location) {
    if (value == null || value.isBlank() || value.length() > 2_000) {
      throw new IllegalArgumentException(
          "description must contain 1 to 2000 characters at " + location);
    }
    return value;
  }

  private static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> raw) {
      return raw;
    }
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() instanceof Class<?> raw) {
      return raw;
    }
    throw new IllegalArgumentException("Tool schema type is not concrete: " + type);
  }
}
