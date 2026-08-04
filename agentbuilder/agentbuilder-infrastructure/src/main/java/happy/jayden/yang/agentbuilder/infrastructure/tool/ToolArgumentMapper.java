package happy.jayden.yang.agentbuilder.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ToolArgumentMapper {

  private final ObjectMapper objectMapper;

  ToolArgumentMapper(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  Object convert(Object value, Type type) {
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() == Optional.class) {
      return Optional.ofNullable(convert(value, parameterized.getActualTypeArguments()[0]));
    }
    if (value == null) {
      return null;
    }
    var normalized = normalize(value, type);
    return objectMapper.convertValue(normalized, objectMapper.constructType(type));
  }

  private Object normalize(Object value, Type type) {
    if (type instanceof GenericArrayType array) {
      return normalizeArray(value, array.getGenericComponentType());
    }
    if (type instanceof ParameterizedType parameterized) {
      var raw = (Class<?>) parameterized.getRawType();
      if (Collection.class.isAssignableFrom(raw)) {
        return normalizeArray(value, parameterized.getActualTypeArguments()[0]);
      }
      return normalizeObject(value, raw);
    }
    if (!(type instanceof Class<?> raw)) {
      return value;
    }
    if (raw.isArray()) {
      return normalizeArray(value, raw.getComponentType());
    }
    if (raw.isRecord() || isDto(raw)) {
      return normalizeObject(value, raw);
    }
    return value;
  }

  private Object normalizeArray(Object value, Type itemType) {
    if (!(value instanceof Collection<?> values)) {
      throw new IllegalArgumentException("array Tool argument must be a JSON array");
    }
    return values.stream().map(item -> normalize(item, itemType)).toList();
  }

  private Object normalizeObject(Object value, Class<?> type) {
    if (!(value instanceof Map<?, ?> values)) {
      return value;
    }
    var fields = modelFields(type);
    var normalized = new LinkedHashMap<String, Object>();
    fields.forEach(
        (modelName, field) -> {
          if (field.required() && !values.containsKey(modelName)) {
            throw new IllegalArgumentException(
                "missing required nested Tool argument " + modelName);
          }
        });
    for (var entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String modelName)) {
        throw new IllegalArgumentException("object Tool argument keys must be strings");
      }
      var field = fields.get(modelName);
      if (field == null) {
        throw new IllegalArgumentException("unexpected nested Tool argument " + modelName);
      }
      normalized.put(field.javaName(), normalize(entry.getValue(), field.type()));
    }
    return normalized;
  }

  private static Map<String, ModelField> modelFields(Class<?> type) {
    var fields = new HashMap<String, ModelField>();
    if (type.isRecord()) {
      for (var component : type.getRecordComponents()) {
        var metadata = component.getAnnotation(AgentToolParam.class);
        var modelName =
            metadata == null || metadata.name().isBlank() ? component.getName() : metadata.name();
        fields.put(
            modelName,
            new ModelField(
                component.getName(),
                component.getGenericType(),
                metadata != null && metadata.required() && component.getType() != Optional.class));
      }
      return fields;
    }
    for (var field : allFields(type)) {
      var metadata = field.getAnnotation(AgentToolParam.class);
      var modelName =
          metadata == null || metadata.name().isBlank() ? field.getName() : metadata.name();
      fields.put(
          modelName,
          new ModelField(
              field.getName(),
              field.getGenericType(),
              metadata != null && metadata.required() && field.getType() != Optional.class));
    }
    return fields;
  }

  private static List<Field> allFields(Class<?> type) {
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
    return fields;
  }

  private static boolean isDto(Class<?> type) {
    if (type.getName().startsWith("java.")) {
      return false;
    }
    return allFields(type).stream()
        .anyMatch(field -> field.isAnnotationPresent(AgentToolParam.class));
  }

  private record ModelField(String javaName, Type type, boolean required) {}
}
