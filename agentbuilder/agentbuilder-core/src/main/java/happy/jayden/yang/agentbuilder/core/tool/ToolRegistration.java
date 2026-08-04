package happy.jayden.yang.agentbuilder.core.tool;

import java.util.Objects;

public record ToolRegistration(ToolDescriptor descriptor, AgentToolHandler handler) {
  public ToolRegistration {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(handler, "handler");
  }
}
