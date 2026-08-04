package happy.jayden.yang.agentbuilder.core.component;

import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import java.util.Objects;
import java.util.Optional;

public record ToolBinding(
    ComponentKey toolKey,
    ComponentVersion contractVersion,
    boolean enabled,
    Optional<String> usageGuidance,
    Optional<Integer> timeoutMs,
    Optional<Integer> maxCallsPerRun,
    Optional<ApprovalPolicy> approvalPolicy,
    Optional<RetryPolicy> retryPolicy,
    Optional<ResultMode> resultMode) {
  public ToolBinding {
    Objects.requireNonNull(toolKey, "toolKey");
    Objects.requireNonNull(contractVersion, "contractVersion");
    usageGuidance = Objects.requireNonNull(usageGuidance, "usageGuidance");
    timeoutMs = Objects.requireNonNull(timeoutMs, "timeoutMs");
    maxCallsPerRun = Objects.requireNonNull(maxCallsPerRun, "maxCallsPerRun");
    approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
    retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    resultMode = Objects.requireNonNull(resultMode, "resultMode");
    usageGuidance.ifPresent(
        value -> TextValidation.requireLength(value, 0, 2_000, "usageGuidance"));
    timeoutMs.ifPresent(value -> requireAtLeast(value, 100, "timeoutMs"));
    maxCallsPerRun.ifPresent(value -> requireAtLeast(value, 1, "maxCallsPerRun"));
  }

  public ToolBinding(ComponentKey toolKey, ComponentVersion contractVersion, boolean enabled) {
    this(
        toolKey,
        contractVersion,
        enabled,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public static PublishedToolBinding published(
      String toolKey, int contractVersion, boolean enabled, String componentChecksum) {
    return new PublishedToolBinding(
        new ComponentKey(toolKey),
        new ComponentVersion(contractVersion),
        enabled,
        componentChecksum,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  static void requireAtLeast(int value, int minimum, String field) {
    if (value < minimum) {
      throw new IllegalArgumentException(field + " must be at least " + minimum);
    }
  }
}
