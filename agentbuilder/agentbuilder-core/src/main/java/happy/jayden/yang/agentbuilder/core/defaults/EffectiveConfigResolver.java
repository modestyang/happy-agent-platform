package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.AgentComponents;
import happy.jayden.yang.agentbuilder.core.component.HookBinding;
import happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef;
import happy.jayden.yang.agentbuilder.core.component.PublishedHookBinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class EffectiveConfigResolver {

  public ResolvedAgentConfig resolve(
      PlatformLimits platformLimits,
      ComponentDefaults componentDefaults,
      ApplicationDefaults applicationDefaults,
      AgentOverrides agentOverrides) {
    Objects.requireNonNull(platformLimits, "platformLimits");
    Objects.requireNonNull(componentDefaults, "componentDefaults");
    Objects.requireNonNull(applicationDefaults, "applicationDefaults");
    Objects.requireNonNull(agentOverrides, "agentOverrides");
    validateDefaultProfile(applicationDefaults, agentOverrides);

    var applicationValues = applicationDefaults.values();
    var overrideValues = agentOverrides.values();
    var codeSource = componentDefaults.sourceVersion();
    var applicationSource = applicationDefaults.defaultProfileVersion().publishedRef();

    var runSeconds =
        capped(
            select(
                overrideValues.maxRunSeconds(),
                applicationValues.maxRunSeconds(),
                Math.toIntExact(componentDefaults.timeout().toSeconds()),
                codeSource,
                applicationSource),
            platformLimits.maxRunSeconds());
    var toolCalls =
        capped(
            select(
                overrideValues.maxToolCalls(),
                applicationValues.maxToolCalls(),
                componentDefaults.maxToolCalls(),
                codeSource,
                applicationSource),
            platformLimits.maxToolCalls());
    var inputTokens =
        capped(
            select(
                overrideValues.maxInputTokens(),
                applicationValues.maxInputTokens(),
                componentDefaults.maxInputTokens(),
                codeSource,
                applicationSource),
            platformLimits.maxInputTokens());
    var outputTokens =
        capped(
            select(
                overrideValues.maxOutputTokens(),
                applicationValues.maxOutputTokens(),
                componentDefaults.maxOutputTokens(),
                codeSource,
                applicationSource),
            platformLimits.maxOutputTokens());
    var cost =
        capped(
            select(
                overrideValues.maxCostUsd(),
                applicationValues.maxCostUsd(),
                componentDefaults.maxCostUsd(),
                codeSource,
                applicationSource),
            platformLimits.maxCostUsd());

    var temperature =
        select(
            overrideValues.modelParameters().temperature(),
            applicationValues.modelParameters().temperature(),
            componentDefaults.temperature(),
            codeSource,
            applicationSource);
    var topP =
        select(
            overrideValues.modelParameters().topP(),
            applicationValues.modelParameters().topP(),
            componentDefaults.topP(),
            codeSource,
            applicationSource);
    var modelOutputTokens =
        capped(
            select(
                overrideValues.modelParameters().maxOutputTokens(),
                applicationValues.modelParameters().maxOutputTokens(),
                componentDefaults.modelMaxOutputTokens(),
                codeSource,
                applicationSource),
            platformLimits.maxOutputTokens());
    var retry =
        select(
            overrideValues.retryPolicy(),
            applicationValues.retryPolicy(),
            componentDefaults.retryPolicy(),
            codeSource,
            applicationSource);

    var platformSource = EffectiveValueSource.platformLimit();
    var sources =
        new PublishedResolvedConfigSources(
            runSeconds.source(),
            toolCalls.source(),
            inputTokens.source(),
            outputTokens.source(),
            cost.source(),
            platformSource,
            temperature.source(),
            topP.source(),
            modelOutputTokens.source(),
            retry.source(),
            componentSource(
                agentOverrides.memoryPolicyVersion(),
                applicationDefaults.memoryPolicyVersion(),
                codeSource,
                applicationSource),
            componentSource(
                agentOverrides.outputSchemaVersion(),
                applicationDefaults.outputSchemaVersion(),
                codeSource,
                applicationSource),
            agentOverrides.defaultProfileVersion().isPresent()
                ? EffectiveValueSource.agentOverride()
                : EffectiveValueSource.applicationProfile(applicationSource));

    return new ResolvedAgentConfig(
        applicationDefaults.applicationScope(),
        new RuntimeLimits(
            runSeconds.value(),
            toolCalls.value(),
            inputTokens.value(),
            outputTokens.value(),
            cost.value(),
            platformLimits.concurrentRuns()),
        new ModelParameters(temperature.value(), topP.value(), modelOutputTokens.value()),
        retry.value(),
        sources);
  }

  public ResolvedAgentDefinition resolveDefinition(
      PlatformLimits platformLimits,
      ComponentDefaults componentDefaults,
      ApplicationDefaults applicationDefaults,
      AgentOverrides agentOverrides,
      AgentComponents baseline) {
    Objects.requireNonNull(baseline, "baseline");
    validateDefaultProfile(applicationDefaults, agentOverrides);
    var codeSource = componentDefaults.sourceVersion();
    var applicationSource = applicationDefaults.defaultProfileVersion().publishedRef();
    var baseConfig =
        resolve(platformLimits, componentDefaults, applicationDefaults, agentOverrides);

    var memory =
        selectComponent(
            agentOverrides.memoryPolicyVersion(),
            applicationDefaults.memoryPolicyVersion(),
            baseline.memoryPolicyVersion(),
            codeSource,
            applicationSource);
    var output =
        selectComponent(
            agentOverrides.outputSchemaVersion(),
            applicationDefaults.outputSchemaVersion(),
            baseline.outputSchemaVersion(),
            codeSource,
            applicationSource);
    var evaluation =
        selectComponent(
            agentOverrides.evaluationSuiteVersion(),
            applicationDefaults.evaluationSuiteVersion(),
            baseline.evaluationSuiteVersion(),
            codeSource,
            applicationSource);
    var defaultProfile =
        new ComponentChoice<>(
            applicationDefaults.defaultProfileVersion(),
            agentOverrides.defaultProfileVersion().isPresent()
                ? EffectiveValueSource.agentOverride()
                : EffectiveValueSource.applicationProfile(applicationSource));

    var hooks =
        resolveHooks(
            baseline.hookBindings(),
            applicationDefaults.values().optionalHookDefaults(),
            agentOverrides.hookBindings(),
            codeSource,
            applicationSource);
    var components =
        new AgentComponents(
            baseline.frameworkVersion(),
            baseline.providerVersion(),
            baseline.modelBinding(),
            baseline.promptVersion(),
            baseline.toolBindings(),
            baseline.skillBindings(),
            hooks.bindings(),
            memory.value(),
            output.value(),
            evaluation.value(),
            defaultProfile.value());

    var oldSources = baseConfig.sources();
    var configSources =
        new PublishedResolvedConfigSources(
            oldSources.runtimeMaxRunSeconds(),
            oldSources.runtimeMaxToolCalls(),
            oldSources.runtimeMaxInputTokens(),
            oldSources.runtimeMaxOutputTokens(),
            oldSources.runtimeMaxCostUsd(),
            oldSources.runtimeConcurrentRuns(),
            oldSources.modelTemperature(),
            oldSources.modelTopP(),
            oldSources.modelMaxOutputTokens(),
            oldSources.retryPolicy(),
            memory.source(),
            output.source(),
            defaultProfile.source());
    var resolvedConfig =
        new ResolvedAgentConfig(
            baseConfig.applicationScope(),
            baseConfig.runtimeLimits(),
            baseConfig.modelParameters(),
            baseConfig.retryPolicy(),
            configSources);

    var sources =
        new ResolvedComponentSources(
            EffectiveValueSource.agentOverride(),
            EffectiveValueSource.agentOverride(),
            EffectiveValueSource.agentOverride(),
            EffectiveValueSource.agentOverride(),
            memory.source(),
            output.source(),
            evaluation.source(),
            defaultProfile.source(),
            baseline.toolBindings().stream()
                .map(
                    binding ->
                        new ResolvedBindingSource(
                            binding.toolKey(),
                            binding.contractVersion(),
                            EffectiveValueSource.agentOverride()))
                .toList(),
            baseline.skillBindings().stream()
                .map(
                    binding ->
                        new ResolvedBindingSource(
                            binding.skillKey(),
                            binding.version(),
                            EffectiveValueSource.agentOverride()))
                .toList(),
            hooks.sources());
    return new ResolvedAgentDefinition(resolvedConfig, components, sources);
  }

  private static <T extends happy.jayden.yang.agentbuilder.core.component.VersionedComponent>
      ComponentChoice<T> selectComponent(
          Optional<T> agent,
          Optional<T> application,
          T baseline,
          PublishedComponentRef codeSource,
          PublishedComponentRef applicationSource) {
    if (agent.isPresent()) {
      var value = agent.orElseThrow();
      return new ComponentChoice<>(value, EffectiveValueSource.agentOverride());
    }
    if (application.isPresent()) {
      var value = application.orElseThrow();
      return new ComponentChoice<>(
          value, EffectiveValueSource.applicationProfile(applicationSource));
    }
    return new ComponentChoice<>(baseline, EffectiveValueSource.codeDefault(codeSource));
  }

  private static ResolvedHooks resolveHooks(
      List<PublishedHookBinding> baseline,
      List<HookBinding> applicationPatches,
      Optional<List<HookBinding>> agentBindings,
      PublishedComponentRef codeSource,
      PublishedComponentRef applicationProfile) {
    var resolved = new ArrayList<>(baseline);
    for (var patch : applicationPatches) {
      replaceHook(resolved, baseline, patch);
    }
    if (agentBindings.isPresent()) {
      resolved.clear();
      for (var binding : agentBindings.orElseThrow()) {
        resolved.add(resolveHook(baseline, binding));
      }
    }
    var sources =
        resolved.stream()
            .map(
                binding -> {
                  EffectiveValueSource source;
                  if (agentBindings.orElse(List.of()).stream()
                      .anyMatch(item -> sameIdentity(item, binding))) {
                    source = EffectiveValueSource.agentOverride();
                  } else if (applicationPatches.stream()
                      .anyMatch(item -> sameIdentity(item, binding))) {
                    source = EffectiveValueSource.applicationProfile(applicationProfile);
                  } else {
                    source = EffectiveValueSource.codeDefault(codeSource);
                  }
                  return new ResolvedBindingSource(binding.hookKey(), binding.version(), source);
                })
            .toList();
    return new ResolvedHooks(List.copyOf(resolved), sources);
  }

  private static void replaceHook(
      List<PublishedHookBinding> resolved, List<PublishedHookBinding> baseline, HookBinding patch) {
    var replacement = resolveHook(baseline, patch);
    for (int index = 0; index < resolved.size(); index++) {
      if (sameIdentity(patch, resolved.get(index))) {
        resolved.set(index, replacement);
        return;
      }
    }
    throw new IllegalArgumentException(
        "optional hook default is not registered: " + patch.hookKey());
  }

  private static PublishedHookBinding resolveHook(
      List<PublishedHookBinding> baseline, HookBinding binding) {
    return baseline.stream()
        .filter(candidate -> sameIdentity(binding, candidate))
        .findFirst()
        .map(
            component ->
                new PublishedHookBinding(
                    binding.hookKey(),
                    binding.version(),
                    binding.enabled(),
                    component.componentChecksum(),
                    binding.config()))
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "hook binding is not registered: " + binding.hookKey()));
  }

  private static boolean sameIdentity(HookBinding left, PublishedHookBinding right) {
    return left.hookKey().equals(right.hookKey()) && left.version().equals(right.version());
  }

  private static Choice<Integer> capped(Choice<Integer> selected, int limit) {
    if (selected.value() > limit) {
      return new Choice<>(limit, EffectiveValueSource.platformLimit());
    }
    return selected;
  }

  private static Choice<BigDecimal> capped(Choice<BigDecimal> selected, BigDecimal limit) {
    if (selected.value().compareTo(limit) > 0) {
      return new Choice<>(limit, EffectiveValueSource.platformLimit());
    }
    return selected;
  }

  private static <T> Choice<T> select(
      Optional<T> agent,
      Optional<T> application,
      T codeDefault,
      PublishedComponentRef codeSource,
      PublishedComponentRef applicationSource) {
    if (agent.isPresent()) {
      return new Choice<>(agent.orElseThrow(), EffectiveValueSource.agentOverride());
    }
    if (application.isPresent()) {
      return new Choice<>(
          application.orElseThrow(), EffectiveValueSource.applicationProfile(applicationSource));
    }
    return new Choice<>(codeDefault, EffectiveValueSource.codeDefault(codeSource));
  }

  private static EffectiveValueSource componentSource(
      Optional<?> agent,
      Optional<?> application,
      PublishedComponentRef codeSource,
      PublishedComponentRef applicationSource) {
    if (agent.isPresent()) {
      return EffectiveValueSource.agentOverride();
    }
    if (application.isPresent()) {
      return EffectiveValueSource.applicationProfile(applicationSource);
    }
    return EffectiveValueSource.codeDefault(codeSource);
  }

  private static void validateDefaultProfile(
      ApplicationDefaults applicationDefaults, AgentOverrides agentOverrides) {
    agentOverrides
        .defaultProfileVersion()
        .ifPresent(
            selected -> {
              if (!selected
                  .publishedRef()
                  .equals(applicationDefaults.defaultProfileVersion().publishedRef())) {
                throw new IllegalArgumentException(
                    "agent defaultProfileVersion must match loaded application defaults");
              }
            });
  }

  private record Choice<T>(T value, EffectiveValueSource source) {}

  private record ComponentChoice<T>(T value, EffectiveValueSource source) {}

  private record ResolvedHooks(
      List<PublishedHookBinding> bindings, List<ResolvedBindingSource> sources) {}
}
