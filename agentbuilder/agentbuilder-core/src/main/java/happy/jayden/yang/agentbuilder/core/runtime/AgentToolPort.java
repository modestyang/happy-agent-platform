package happy.jayden.yang.agentbuilder.core.runtime;

import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.util.Map;

/** The only data-access boundary available to executable skills. */
@FunctionalInterface
public interface AgentToolPort {
  Object invoke(String toolKey, Map<String, Object> input, ToolExecutionContext context)
      throws Exception;
}
