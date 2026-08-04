package happy.jayden.yang.agentbuilder.core.tool;

import java.util.Map;

@FunctionalInterface
public interface AgentToolHandler {
  Object invoke(Map<String, Object> modelArguments, ToolExecutionContext context) throws Exception;
}
