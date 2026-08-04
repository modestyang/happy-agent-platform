package happy.jayden.yang.agentbuilder.core.defaults;

import happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef;
import java.time.Duration;
import java.util.Objects;

public final class ApplicationDefaults {
  private final String applicationScope;
  private final PublishedComponentRef defaultProfileVersion;
  private volatile DefaultValues values;

  public ApplicationDefaults(
      String applicationScope, PublishedComponentRef defaultProfileVersion, DefaultValues values) {
    this.applicationScope = requireScope(applicationScope);
    this.defaultProfileVersion =
        Objects.requireNonNull(defaultProfileVersion, "defaultProfileVersion");
    this.values = Objects.requireNonNull(values, "values");
  }

  public String applicationScope() {
    return applicationScope;
  }

  public PublishedComponentRef defaultProfileVersion() {
    return defaultProfileVersion;
  }

  public DefaultValues values() {
    return values;
  }

  public synchronized void changeTimeout(Duration timeout) {
    values = values.withTimeout(timeout);
  }

  private static String requireScope(String value) {
    Objects.requireNonNull(value, "applicationScope");
    if (value.isBlank() || value.length() > 120) {
      throw new IllegalArgumentException("applicationScope must contain 1 to 120 characters");
    }
    return value;
  }
}
