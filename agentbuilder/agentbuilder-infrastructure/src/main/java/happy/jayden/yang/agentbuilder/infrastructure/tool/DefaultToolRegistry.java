package happy.jayden.yang.agentbuilder.infrastructure.tool;

import happy.jayden.yang.agentbuilder.core.component.ApprovalPolicy;
import happy.jayden.yang.agentbuilder.core.component.ResultMode;
import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolHandler;
import happy.jayden.yang.agentbuilder.core.tool.ResolvedTool;
import happy.jayden.yang.agentbuilder.core.tool.ResolvedToolSet;
import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolLifecycleStatus;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistration;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistry;
import happy.jayden.yang.agentbuilder.core.tool.ToolRiskLevel;
import happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultToolRegistry implements ToolRegistry {

  private final Map<String, ToolRegistration> registrations;
  private final Map<String, ToolRegistration> newestByToolKey;

  public DefaultToolRegistry(Collection<ToolRegistration> registrations) {
    Objects.requireNonNull(registrations, "registrations");
    var index = new HashMap<String, ToolRegistration>();
    var newest = new HashMap<String, ToolRegistration>();
    var runtimeNames = new HashMap<String, ToolDescriptor>();
    for (var registration : registrations) {
      Objects.requireNonNull(registration, "registrations item");
      if (registration.descriptor().status() != ToolLifecycleStatus.AVAILABLE) {
        throw new IllegalArgumentException(
            "Tool registration is not AVAILABLE: " + identity(registration.descriptor()));
      }
      if (index.putIfAbsent(identity(registration.descriptor()), registration) != null) {
        throw new IllegalArgumentException(
            "duplicate Tool registration " + identity(registration.descriptor()));
      }
      var runtimeCollision =
          runtimeNames.putIfAbsent(
              registration.descriptor().runtimeName(), registration.descriptor());
      if (runtimeCollision != null) {
        throw new IllegalArgumentException(
            "duplicate runtimeName "
                + registration.descriptor().runtimeName()
                + " for "
                + identity(runtimeCollision)
                + " and "
                + identity(registration.descriptor()));
      }
      newest.merge(
          registration.descriptor().toolKey(),
          registration,
          (existing, candidate) ->
              existing.descriptor().contractVersion() >= candidate.descriptor().contractVersion()
                  ? existing
                  : candidate);
    }
    this.registrations = Map.copyOf(index);
    this.newestByToolKey = Map.copyOf(newest);
  }

  @Override
  public ResolvedToolSet resolve(List<ToolBinding> bindings) {
    Objects.requireNonNull(bindings, "bindings");
    var identities = new HashSet<String>();
    var resolved = new java.util.ArrayList<ResolvedTool>();
    for (var binding : bindings) {
      Objects.requireNonNull(binding, "bindings item");
      var identity = identity(binding);
      if (!identities.add(identity)) {
        throw new IllegalArgumentException("duplicate Tool binding " + identity);
      }
      var registration = registrations.get(identity);
      if (registration == null) {
        throw new IllegalArgumentException("unknown Tool binding " + identity);
      }
      if (binding.enabled()) {
        resolved.add(resolve(registration, binding));
      }
    }
    return new ResolvedToolSet(resolved);
  }

  @Override
  public List<ToolDescriptor> descriptors() {
    return newestByToolKey.values().stream()
        .map(ToolRegistration::descriptor)
        .sorted(Comparator.comparing(ToolDescriptor::toolKey))
        .toList();
  }

  @Override
  public Object invoke(String toolKey, Map<String, Object> input, ToolExecutionContext context)
      throws Exception {
    Objects.requireNonNull(toolKey, "toolKey");
    Objects.requireNonNull(input, "input");
    var registration = newestByToolKey.get(toolKey);
    if (registration == null) {
      throw new IllegalArgumentException("unknown Tool " + toolKey);
    }
    return secured(registration).invoke(Map.copyOf(input), context);
  }

  private static ResolvedTool resolve(ToolRegistration registration, ToolBinding binding) {
    var descriptor = registration.descriptor();
    var timeout = binding.timeoutMs().orElse(descriptor.defaultTimeoutMs());
    if (timeout > descriptor.maxTimeoutMs()) {
      throw new IllegalArgumentException("Tool binding timeout exceeds descriptor maximum");
    }
    var maxCalls = binding.maxCallsPerRun().orElse(descriptor.defaultMaxCallsPerRun());
    if (maxCalls > descriptor.defaultMaxCallsPerRun()) {
      throw new IllegalArgumentException("Tool binding maxCallsPerRun exceeds descriptor maximum");
    }
    var minimumApproval = minimumApproval(descriptor);
    var approval = binding.approvalPolicy().orElse(minimumApproval);
    if (approval.ordinal() < minimumApproval.ordinal()) {
      throw new IllegalArgumentException("Tool binding approvalPolicy weakens descriptor risk");
    }
    var retry = binding.retryPolicy().orElse(RetryPolicy.NONE);
    if (!descriptor.idempotent() && retry != RetryPolicy.NONE) {
      throw new IllegalArgumentException("non-idempotent Tool cannot enable retry");
    }
    var resultMode =
        binding
            .resultMode()
            .orElse(
                descriptor.returnDirect() ? ResultMode.RETURN_DIRECT : ResultMode.MODEL_CONTEXT);
    if (descriptor.returnDirect() != (resultMode == ResultMode.RETURN_DIRECT)) {
      throw new IllegalArgumentException("Tool binding resultMode conflicts with returnDirect");
    }
    return new ResolvedTool(
        descriptor,
        binding,
        secured(registration),
        binding.usageGuidance().orElse(descriptor.whenToUse()),
        timeout,
        maxCalls,
        approval,
        retry,
        resultMode);
  }

  private static ApprovalPolicy minimumApproval(ToolDescriptor descriptor) {
    if (descriptor.riskLevel() == ToolRiskLevel.HIGH
        || descriptor.riskLevel() == ToolRiskLevel.CRITICAL
        || descriptor.sideEffect() == ToolSideEffect.WRITE
        || descriptor.sideEffect() == ToolSideEffect.EXTERNAL_WRITE) {
      return ApprovalPolicy.ALWAYS;
    }
    return descriptor.riskLevel() == ToolRiskLevel.MEDIUM
        ? ApprovalPolicy.RISK_BASED
        : ApprovalPolicy.NEVER;
  }

  private static AgentToolHandler secured(ToolRegistration registration) {
    return (arguments, context) -> {
      Objects.requireNonNull(context, "context");
      if (!context.grantedScopes().containsAll(registration.descriptor().requiredScopes())) {
        var missing = new HashSet<>(registration.descriptor().requiredScopes());
        missing.removeAll(context.grantedScopes());
        throw new SecurityException("Tool execution context is missing required scopes " + missing);
      }
      return registration.handler().invoke(arguments, context);
    };
  }

  private static String identity(ToolDescriptor descriptor) {
    return descriptor.toolKey() + "@" + descriptor.contractVersion();
  }

  private static String identity(ToolBinding binding) {
    return binding.toolKey().value() + "@" + binding.contractVersion().value();
  }
}
