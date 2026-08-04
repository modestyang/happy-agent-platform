package happy.jayden.yang.agentbuilder.core.tool;

import java.util.List;
import java.util.Objects;

public record ResolvedToolSet(List<ResolvedTool> tools) {
  public ResolvedToolSet {
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
  }
}
