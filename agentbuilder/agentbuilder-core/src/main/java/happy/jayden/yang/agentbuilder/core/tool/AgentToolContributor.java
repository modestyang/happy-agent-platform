package happy.jayden.yang.agentbuilder.core.tool;

import java.util.Collection;

@FunctionalInterface
public interface AgentToolContributor {
  Collection<ToolRegistration> contributeTools();
}
