package happy.jayden.yang.agentbuilder.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolHandler;
import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ReflectiveAgentToolHandler implements AgentToolHandler {

  private final ToolMethodDefinition method;
  private final ToolDescriptor descriptor;
  private final ToolArgumentMapper argumentMapper;
  private final Set<String> modelArgumentNames;

  ReflectiveAgentToolHandler(
      ToolMethodDefinition method, ToolDescriptor descriptor, ObjectMapper objectMapper) {
    this.method = Objects.requireNonNull(method, "method");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    argumentMapper = new ToolArgumentMapper(Objects.requireNonNull(objectMapper, "objectMapper"));
    modelArgumentNames =
        method.modelParameters().stream()
            .map(ToolMethodParameter::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @Override
  public void validate(Map<String, Object> modelArguments) {
    Objects.requireNonNull(modelArguments, "modelArguments");
    mapArguments(modelArguments);
  }

  @Override
  public Object invoke(Map<String, Object> modelArguments, ToolExecutionContext context)
      throws Exception {
    Objects.requireNonNull(modelArguments, "modelArguments");
    Objects.requireNonNull(context, "context");
    requireScopes(context);
    var arguments = mapArguments(modelArguments);
    if (method.contextParameterIndex() >= 0) {
      arguments[method.contextParameterIndex()] = context;
    }

    try {
      method.invocableMethod().trySetAccessible();
      return method.invocableMethod().invoke(method.bean(), arguments);
    } catch (InvocationTargetException exception) {
      var cause = exception.getCause();
      if (cause instanceof Exception checked) {
        throw checked;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("Tool invocation failed", cause);
    } catch (IllegalAccessException exception) {
      throw new IllegalStateException("Tool method is not invocable", exception);
    }
  }

  private Object[] mapArguments(Map<String, Object> modelArguments) {
    var unexpected = new HashSet<>(modelArguments.keySet());
    unexpected.removeAll(modelArgumentNames);
    if (!unexpected.isEmpty()) {
      throw new IllegalArgumentException("unexpected model Tool arguments " + unexpected);
    }

    var arguments = new Object[method.contractMethod().getParameterCount()];
    for (var parameter : method.modelParameters()) {
      var present = modelArguments.containsKey(parameter.name());
      if (!present && parameter.metadata().required() && parameter.rawType() != Optional.class) {
        throw new IllegalArgumentException(
            "missing required model Tool argument " + parameter.name());
      }
      arguments[parameter.methodIndex()] =
          present
              ? convert(modelArguments.get(parameter.name()), parameter.type())
              : absent(parameter);
    }
    return arguments;
  }

  private Object absent(ToolMethodParameter parameter) {
    if (parameter.rawType() == Optional.class) {
      return Optional.empty();
    }
    if (parameter.rawType().isPrimitive()) {
      throw new IllegalArgumentException(
          "primitive model Tool argument cannot be absent: " + parameter.name());
    }
    return null;
  }

  private Object convert(Object value, Type type) {
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() == Optional.class) {
      return Optional.ofNullable(
          argumentMapper.convert(value, parameterized.getActualTypeArguments()[0]));
    }
    if (value == null) {
      return null;
    }
    return argumentMapper.convert(value, type);
  }

  private void requireScopes(ToolExecutionContext context) {
    if (!context.grantedScopes().containsAll(descriptor.requiredScopes())) {
      var missing = new HashSet<>(descriptor.requiredScopes());
      missing.removeAll(context.grantedScopes());
      throw new SecurityException("Tool execution context is missing required scopes " + missing);
    }
  }
}
