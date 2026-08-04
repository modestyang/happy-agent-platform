package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

record ToolMethodDefinition(
    Object bean,
    Method contractMethod,
    Method invocableMethod,
    AgentTool metadata,
    List<ToolMethodParameter> modelParameters,
    int contextParameterIndex) {

  ToolMethodDefinition(
      Object bean,
      Method contractMethod,
      Method invocableMethod,
      AgentTool metadata,
      List<Method> parameterSources) {
    this(
        bean,
        contractMethod,
        invocableMethod,
        metadata,
        modelParameters(contractMethod, parameterSources),
        contextIndex(contractMethod));
  }

  ToolMethodDefinition {
    Objects.requireNonNull(bean, "bean");
    Objects.requireNonNull(contractMethod, "contractMethod");
    Objects.requireNonNull(invocableMethod, "invocableMethod");
    Objects.requireNonNull(metadata, "metadata");
    modelParameters = List.copyOf(Objects.requireNonNull(modelParameters, "modelParameters"));
    if (contractMethod.getParameterCount() != invocableMethod.getParameterCount()) {
      throw new IllegalArgumentException(
          "contract and invocable Tool methods must have equal arity");
    }
  }

  private static List<ToolMethodParameter> modelParameters(
      Method contractMethod, List<Method> parameterSources) {
    Objects.requireNonNull(parameterSources, "parameterSources");
    var sources =
        parameterSources.stream()
            .peek(
                source -> {
                  if (source.getParameterCount() != contractMethod.getParameterCount()) {
                    throw new IllegalArgumentException(
                        "Tool parameter metadata source has incompatible parameters");
                  }
                })
            .sorted(Comparator.comparing(Method::toGenericString))
            .toList();
    var parameters = new ArrayList<ToolMethodParameter>();
    var names = new HashSet<String>();
    for (int index = 0; index < contractMethod.getParameterCount(); index++) {
      var contractParameter = contractMethod.getParameters()[index];
      if (contractParameter.getType() == ToolExecutionContext.class) {
        continue;
      }
      var metadata = mergedParameterMetadata(sources, index, contractMethod);
      if (metadata.name().isBlank()) {
        throw new IllegalArgumentException(
            "method @AgentToolParam.name must be explicit at index "
                + index
                + " on "
                + contractMethod.toGenericString());
      }
      var name = metadata.name();
      if (!names.add(name)) {
        throw new IllegalArgumentException("duplicate model parameter name " + name);
      }
      parameters.add(
          new ToolMethodParameter(
              index,
              name,
              contractParameter.getParameterizedType(),
              contractParameter.getType(),
              metadata));
    }
    return parameters;
  }

  private static AgentToolParam mergedParameterMetadata(
      List<Method> sources, int index, Method contractMethod) {
    var annotated =
        sources.stream()
            .filter(
                source -> source.getParameters()[index].isAnnotationPresent(AgentToolParam.class))
            .toList();
    if (annotated.isEmpty()) {
      throw new IllegalArgumentException(
          "model parameter at index "
              + index
              + " on "
              + contractMethod.toGenericString()
              + " must declare @AgentToolParam");
    }
    var metadata = annotated.get(0).getParameters()[index].getAnnotation(AgentToolParam.class);
    if (annotated.stream()
        .map(source -> source.getParameters()[index].getAnnotation(AgentToolParam.class))
        .anyMatch(candidate -> !metadata.equals(candidate))) {
      var locations =
          annotated.stream()
              .map(Method::toGenericString)
              .distinct()
              .sorted()
              .collect(Collectors.joining(", "));
      throw new IllegalArgumentException(
          "conflicting @AgentToolParam metadata at index "
              + index
              + " for "
              + contractMethod.toGenericString()
              + " from "
              + locations);
    }
    return metadata;
  }

  private static int contextIndex(Method method) {
    var result = -1;
    for (int index = 0; index < method.getParameterCount(); index++) {
      if (method.getParameterTypes()[index] == ToolExecutionContext.class) {
        if (result >= 0) {
          throw new IllegalArgumentException(
              "Tool method can declare at most one execution context");
        }
        result = index;
      }
    }
    return result;
  }
}
