package happy.jayden.yang.agentbuilder.core.component;

import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import java.util.Objects;
import java.util.Optional;

public record PublishedToolBinding(
    ComponentKey toolKey,
    ComponentVersion contractVersion,
    boolean enabled,
    String componentChecksum,
    Optional<String> usageGuidance,
    Optional<Integer> timeoutMs,
    Optional<Integer> maxCallsPerRun,
    Optional<ApprovalPolicy> approvalPolicy,
    Optional<RetryPolicy> retryPolicy,
    Optional<ResultMode> resultMode) {
  public PublishedToolBinding {
    Objects.requireNonNull(toolKey, "toolKey");
    Objects.requireNonNull(contractVersion, "contractVersion");
    ComponentValidation.requireChecksum(componentChecksum);
    usageGuidance = Objects.requireNonNull(usageGuidance, "usageGuidance");
    timeoutMs = Objects.requireNonNull(timeoutMs, "timeoutMs");
    maxCallsPerRun = Objects.requireNonNull(maxCallsPerRun, "maxCallsPerRun");
    approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
    retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    resultMode = Objects.requireNonNull(resultMode, "resultMode");
    timeoutMs.ifPresent(value -> ToolBinding.requireAtLeast(value, 100, "timeoutMs"));
    maxCallsPerRun.ifPresent(value -> ToolBinding.requireAtLeast(value, 1, "maxCallsPerRun"));
  }
}
