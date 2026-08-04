package happy.jayden.yang.agentbuilder.core.component;

import java.util.Objects;
import java.util.regex.Pattern;

public record ConfigEntry(String path, ConfigValue value) implements Comparable<ConfigEntry> {
  private static final Pattern PATH = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.-]{0,255}$");

  public ConfigEntry {
    TextValidation.requireUnicodeScalar(path, "path");
    Objects.requireNonNull(value, "value");
    if (!PATH.matcher(path).matches()) {
      throw new IllegalArgumentException("invalid config path: " + path);
    }
  }

  @Override
  public int compareTo(ConfigEntry other) {
    return path.compareTo(other.path);
  }
}
