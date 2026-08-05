package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import java.util.List;
import java.util.Objects;

/** Immutable, exact version of defaults for one application. */
public record DefaultProfileVersion(
    ApplicationKey applicationKey,
    DefaultProfileRef profile,
    DefaultValues defaults,
    long revision,
    List<String> tags) {
  public DefaultProfileVersion {
    Objects.requireNonNull(applicationKey, "applicationKey");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(defaults, "defaults");
    if (revision < 1) throw new IllegalArgumentException("revision must be positive");
    tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
  }
}
