package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

final class SpringToolMethodDiscovery {

  List<ToolMethodDefinition> discover(Object bean) {
    Objects.requireNonNull(bean, "bean");
    var targetType = ClassUtils.getUserClass(AopUtils.getTargetClass(bean));
    var selected =
        MethodIntrospector.selectMethods(
            targetType,
            (MethodIntrospector.MetadataLookup<AgentTool>)
                method -> AnnotatedElementUtils.findMergedAnnotation(method, AgentTool.class));
    var logical = new LinkedHashMap<String, Candidate>();
    selected.forEach(
        (candidate, foundMetadata) -> {
          var mostSpecific = ClassUtils.getMostSpecificMethod(candidate, targetType);
          var bridged = BridgeMethodResolver.findBridgedMethod(mostSpecific);
          var specificMetadata =
              AnnotatedElementUtils.findMergedAnnotation(bridged, AgentTool.class);
          var source = specificMetadata == null ? candidate : bridged;
          var metadata = specificMetadata == null ? foundMetadata : specificMetadata;
          var normalized = new Candidate(bridged, source, metadata, specificMetadata != null);
          logical.merge(logicalIdentity(bridged), normalized, SpringToolMethodDiscovery::prefer);
        });

    var definitions = new ArrayList<ToolMethodDefinition>();
    logical.values().stream()
        .sorted(Comparator.comparing(value -> value.contractMethod().toGenericString()))
        .forEach(
            candidate ->
                definitions.add(
                    new ToolMethodDefinition(
                        bean,
                        candidate.contractMethod(),
                        candidate.annotationSource(),
                        invocableMethod(bean, targetType, candidate),
                        candidate.metadata())));
    return List.copyOf(definitions);
  }

  private static Candidate prefer(Candidate first, Candidate second) {
    if (first.specificAnnotation() != second.specificAnnotation()) {
      return second.specificAnnotation() ? second : first;
    }
    return first
            .annotationSource()
            .getDeclaringClass()
            .isAssignableFrom(second.annotationSource().getDeclaringClass())
        ? second
        : first;
  }

  private static Method invocableMethod(Object bean, Class<?> targetType, Candidate candidate) {
    try {
      return AopUtils.selectInvocableMethod(candidate.contractMethod(), bean.getClass());
    } catch (IllegalStateException ignored) {
      try {
        return AopUtils.selectInvocableMethod(candidate.annotationSource(), bean.getClass());
      } catch (IllegalStateException secondFailure) {
        var interfaceMethod = compatibleInterfaceMethod(targetType, candidate.contractMethod());
        if (interfaceMethod != null) {
          return AopUtils.selectInvocableMethod(interfaceMethod, bean.getClass());
        }
        throw secondFailure;
      }
    }
  }

  private static Method compatibleInterfaceMethod(Class<?> targetType, Method contractMethod) {
    for (var implemented : ClassUtils.getAllInterfacesForClassAsSet(targetType)) {
      for (var method : implemented.getMethods()) {
        if (!method.getName().equals(contractMethod.getName())
            || method.getParameterCount() != contractMethod.getParameterCount()) {
          continue;
        }
        var compatible = true;
        for (int index = 0; index < method.getParameterCount(); index++) {
          if (!method.getParameterTypes()[index].isAssignableFrom(
              contractMethod.getParameterTypes()[index])) {
            compatible = false;
            break;
          }
        }
        if (compatible) {
          return method;
        }
      }
    }
    return null;
  }

  private static String logicalIdentity(Method method) {
    var parameters =
        java.util.Arrays.stream(method.getParameterTypes())
            .map(Class::getName)
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    return method.getName() + "(" + parameters + ")";
  }

  private record Candidate(
      Method contractMethod,
      Method annotationSource,
      AgentTool metadata,
      boolean specificAnnotation) {}
}
