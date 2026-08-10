package happy.jayden.yang.agentbuilder.core.tool;

import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ToolRegistry {
  ResolvedToolSet resolve(List<ToolBinding> bindings);

  /** Returns the runtime contracts that may be exposed to a model or an operator console. */
  default List<ToolDescriptor> descriptors() {
    return List.of();
  }

  /** Invokes the newest registered contract for a Tool through its normal scope guard. */
  default Object invoke(String toolKey, Map<String, Object> input, ToolExecutionContext context)
      throws Exception {
    throw new UnsupportedOperationException("Tool registry does not support direct invocation");
  }
}
