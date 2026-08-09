package happy.jayden.yang.agentbuilder.core.tool;

import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ToolRegistry {
  ResolvedToolSet resolve(List<ToolBinding> bindings);

  /** Invokes the newest registered contract for a Tool through its normal scope guard. */
  default Object invoke(String toolKey, Map<String, Object> input, ToolExecutionContext context)
      throws Exception {
    throw new UnsupportedOperationException("Tool registry does not support direct invocation");
  }
}
