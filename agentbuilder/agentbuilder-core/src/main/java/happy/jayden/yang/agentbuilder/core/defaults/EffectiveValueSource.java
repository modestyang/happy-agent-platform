package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef;
import java.util.Objects;
import java.util.Optional;

public record EffectiveValueSource(
    ValueSource source, Optional<PublishedComponentRef> sourceVersion) {
  public EffectiveValueSource {
    Objects.requireNonNull(source, "source");
    sourceVersion = Objects.requireNonNull(sourceVersion, "sourceVersion");
  }

  public static EffectiveValueSource platformLimit() {
    return new EffectiveValueSource(ValueSource.PLATFORM_LIMIT, Optional.empty());
  }

  public static EffectiveValueSource agentOverride() {
    return new EffectiveValueSource(ValueSource.AGENT_OVERRIDE, Optional.empty());
  }

  public static EffectiveValueSource agentOverride(PublishedComponentRef version) {
    return new EffectiveValueSource(ValueSource.AGENT_OVERRIDE, Optional.of(version));
  }

  public static EffectiveValueSource codeDefault(PublishedComponentRef version) {
    return new EffectiveValueSource(ValueSource.CODE_DEFAULT, Optional.of(version));
  }

  public static EffectiveValueSource applicationProfile(PublishedComponentRef version) {
    return new EffectiveValueSource(ValueSource.APPLICATION_PROFILE, Optional.of(version));
  }
}
