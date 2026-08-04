package happy.jayden.yang.agentbuilder.core.tool;

import happy.jayden.yang.agentbuilder.core.component.ApprovalPolicy;
import happy.jayden.yang.agentbuilder.core.component.ResultMode;
import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import java.util.Objects;

public record ResolvedTool(
    ToolDescriptor descriptor,
    ToolBinding binding,
    AgentToolHandler handler,
    String usageGuidance,
    int timeoutMs,
    int maxCallsPerRun,
    ApprovalPolicy approvalPolicy,
    RetryPolicy retryPolicy,
    ResultMode resultMode) {
  public ResolvedTool {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(handler, "handler");
    usageGuidance = ToolText.require(usageGuidance, 0, 2_000, "usageGuidance", true);
    if (timeoutMs < 100 || timeoutMs > descriptor.maxTimeoutMs()) {
      throw new IllegalArgumentException("timeoutMs exceeds Tool descriptor limits");
    }
    if (maxCallsPerRun < 1 || maxCallsPerRun > descriptor.defaultMaxCallsPerRun()) {
      throw new IllegalArgumentException("maxCallsPerRun exceeds Tool descriptor limits");
    }
    Objects.requireNonNull(approvalPolicy, "approvalPolicy");
    Objects.requireNonNull(retryPolicy, "retryPolicy");
    Objects.requireNonNull(resultMode, "resultMode");
  }
}
