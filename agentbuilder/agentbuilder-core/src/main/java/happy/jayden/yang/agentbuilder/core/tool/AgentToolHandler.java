package happy.jayden.yang.agentbuilder.core.tool;

import java.util.Map;

@FunctionalInterface
public interface AgentToolHandler {
  default void validate(Map<String, Object> modelArguments) throws Exception {}

  Object invoke(Map<String, Object> modelArguments, ToolExecutionContext context) throws Exception;
}
