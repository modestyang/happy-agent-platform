package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

final class SpringToolMethodDiscovery {

  List<ToolMethodDefinition> discover(Object bean) {
    Objects.requireNonNull(bean, "bean");
    var targetType = ClassUtils.getUserClass(AopUtils.getTargetClass(bean));
    var hierarchyMethods = hierarchyMethods(targetType);
    var groups = new LinkedHashMap<String, List<AnnotatedMethod>>();
    for (var annotated : annotatedMethods(targetType)) {
      var contractMethod = mostSpecificMethod(annotated.source(), targetType);
      groups
          .computeIfAbsent(logicalIdentity(contractMethod), ignored -> new ArrayList<>())
          .add(annotated);
    }

    var definitions = new ArrayList<ToolMethodDefinition>();
    groups.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              var annotated = sorted(entry.getValue());
              var metadata = mergeMetadata(annotated, entry.getKey());
              var contractMethod =
                  annotated.stream()
                      .map(value -> mostSpecificMethod(value.source(), targetType))
                      .sorted(Comparator.comparing(Method::toGenericString))
                      .findFirst()
                      .orElseThrow();
              var parameterSources =
                  hierarchyMethods.stream()
                      .filter(
                          method ->
                              logicalIdentity(mostSpecificMethod(method, targetType))
                                  .equals(entry.getKey()))
                      .sorted(Comparator.comparing(Method::toGenericString))
                      .toList();
              definitions.add(
                  new ToolMethodDefinition(
                      bean,
                      contractMethod,
                      invocableMethod(bean, targetType, contractMethod, parameterSources),
                      metadata,
                      parameterSources));
            });
    return List.copyOf(definitions);
  }

  private static List<AnnotatedMethod> annotatedMethods(Class<?> targetType) {
    var discovered = new LinkedHashMap<Method, List<AgentTool>>();
    for (var type : hierarchyTypes(targetType)) {
      MethodIntrospector.selectMethods(
              type,
              (MethodIntrospector.MetadataLookup<AgentTool>)
                  method -> AnnotatedElementUtils.findMergedAnnotation(method, AgentTool.class))
          .forEach(
              (method, metadata) -> {
                if (!declaresToolMetadata(method)) {
                  return;
                }
                var values = discovered.computeIfAbsent(method, ignored -> new ArrayList<>());
                if (values.stream().noneMatch(metadata::equals)) {
                  values.add(metadata);
                }
              });
    }
    var result = new ArrayList<AnnotatedMethod>();
    discovered.forEach(
        (method, values) ->
            values.forEach(metadata -> result.add(new AnnotatedMethod(method, metadata))));
    result.sort(
        Comparator.comparing((AnnotatedMethod value) -> value.source().toGenericString())
            .thenComparing(value -> value.metadata().toString()));
    return List.copyOf(result);
  }

  private static boolean declaresToolMetadata(Method method) {
    return java.util.Arrays.stream(method.getDeclaredAnnotations())
        .anyMatch(
            annotation ->
                annotation.annotationType() == AgentTool.class
                    || AnnotatedElementUtils.findMergedAnnotation(
                            annotation.annotationType(), AgentTool.class)
                        != null);
  }

  private static List<Class<?>> hierarchyTypes(Class<?> targetType) {
    var result = new LinkedHashSet<Class<?>>();
    collectHierarchy(targetType, result);
    return result.stream().sorted(Comparator.comparing(Class::getName)).toList();
  }

  private static void collectHierarchy(Class<?> type, Set<Class<?>> result) {
    if (type == null || type == Object.class || !result.add(type)) {
      return;
    }
    java.util.Arrays.stream(type.getInterfaces())
        .sorted(Comparator.comparing(Class::getName))
        .forEach(value -> collectHierarchy(value, result));
    collectHierarchy(type.getSuperclass(), result);
  }

  private static List<Method> hierarchyMethods(Class<?> targetType) {
    return hierarchyTypes(targetType).stream()
        .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods()))
        .filter(method -> !method.isSynthetic())
        .distinct()
        .sorted(Comparator.comparing(Method::toGenericString))
        .toList();
  }

  private static AgentTool mergeMetadata(List<AnnotatedMethod> methods, String logicalIdentity) {
    var metadata = methods.get(0).metadata();
    if (methods.stream().anyMatch(method -> !metadata.equals(method.metadata()))) {
      var sources =
          methods.stream()
              .map(method -> method.source().getDeclaringClass().getName())
              .distinct()
              .sorted()
              .collect(Collectors.joining(", "));
      throw new IllegalArgumentException(
          "conflicting @AgentTool metadata for " + logicalIdentity + " from " + sources);
    }
    return metadata;
  }

  private static List<AnnotatedMethod> sorted(List<AnnotatedMethod> methods) {
    return methods.stream()
        .sorted(
            Comparator.comparing((AnnotatedMethod value) -> value.source().toGenericString())
                .thenComparing(value -> value.metadata().toString()))
        .toList();
  }

  private static Method mostSpecificMethod(Method method, Class<?> targetType) {
    return BridgeMethodResolver.findBridgedMethod(
        ClassUtils.getMostSpecificMethod(method, targetType));
  }

  private static Method invocableMethod(
      Object bean, Class<?> targetType, Method contractMethod, List<Method> hierarchyMethods) {
    var candidates = new ArrayList<Method>();
    candidates.add(contractMethod);
    candidates.addAll(hierarchyMethods);
    IllegalStateException lastFailure = null;
    for (var candidate : candidates) {
      try {
        return AopUtils.selectInvocableMethod(candidate, bean.getClass());
      } catch (IllegalStateException failure) {
        lastFailure = failure;
      }
    }
    var interfaceMethod = compatibleInterfaceMethod(targetType, contractMethod);
    if (interfaceMethod != null) {
      return AopUtils.selectInvocableMethod(interfaceMethod, bean.getClass());
    }
    throw Objects.requireNonNull(lastFailure, "no invocable Tool method candidate");
  }

  private static Method compatibleInterfaceMethod(Class<?> targetType, Method contractMethod) {
    return ClassUtils.getAllInterfacesForClassAsSet(targetType).stream()
        .sorted(Comparator.comparing(Class::getName))
        .flatMap(implemented -> java.util.Arrays.stream(implemented.getMethods()))
        .filter(method -> method.getName().equals(contractMethod.getName()))
        .filter(method -> method.getParameterCount() == contractMethod.getParameterCount())
        .filter(method -> compatibleParameters(method, contractMethod))
        .findFirst()
        .orElse(null);
  }

  private static boolean compatibleParameters(Method candidate, Method contractMethod) {
    for (int index = 0; index < candidate.getParameterCount(); index++) {
      if (!candidate.getParameterTypes()[index].isAssignableFrom(
          contractMethod.getParameterTypes()[index])) {
        return false;
      }
    }
    return true;
  }

  private static String logicalIdentity(Method method) {
    var parameters =
        java.util.Arrays.stream(method.getParameterTypes())
            .map(Class::getName)
            .collect(Collectors.joining(","));
    return method.getName() + "(" + parameters + ")";
  }

  private record AnnotatedMethod(Method source, AgentTool metadata) {}
}
