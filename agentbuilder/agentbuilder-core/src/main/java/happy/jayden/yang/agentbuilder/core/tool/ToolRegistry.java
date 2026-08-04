package happy.jayden.yang.agentbuilder.core.tool;

import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import java.util.List;

@FunctionalInterface
public interface ToolRegistry {
  ResolvedToolSet resolve(List<ToolBinding> bindings);
}
