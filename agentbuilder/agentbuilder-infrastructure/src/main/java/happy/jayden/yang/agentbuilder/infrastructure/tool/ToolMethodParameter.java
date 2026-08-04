package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import java.lang.reflect.Type;
import java.util.Objects;

record ToolMethodParameter(
    int methodIndex, String name, Type type, Class<?> rawType, AgentToolParam metadata) {
  ToolMethodParameter {
    if (methodIndex < 0) {
      throw new IllegalArgumentException("methodIndex must not be negative");
    }
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(rawType, "rawType");
    Objects.requireNonNull(metadata, "metadata");
  }
}
