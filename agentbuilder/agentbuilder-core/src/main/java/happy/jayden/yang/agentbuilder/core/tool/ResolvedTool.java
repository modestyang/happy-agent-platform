package happy.jayden.yang.agentbuilder.core.tool;

import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import java.util.Objects;

public record ResolvedTool(
    ToolDescriptor descriptor, ToolBinding binding, AgentToolHandler handler) {
  public ResolvedTool {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(handler, "handler");
  }
}
