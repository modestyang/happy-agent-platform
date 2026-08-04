package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef;
import java.math.BigDecimal;
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

    var applicationValues = applicationDefaults.values();
    var overrideValues = agentOverrides.values();
    var codeSource = componentDefaults.sourceVersion();
    var applicationSource = applicationDefaults.defaultProfileVersion();

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

    var memorySource =
        sourceForOptional(
            overrideValues.memoryPolicy(),
            applicationValues.memoryPolicy(),
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
            memorySource,
            EffectiveValueSource.codeDefault(codeSource),
            EffectiveValueSource.applicationProfile(applicationSource));

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

  private static EffectiveValueSource sourceForOptional(
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

  private record Choice<T>(T value, EffectiveValueSource source) {}
}
