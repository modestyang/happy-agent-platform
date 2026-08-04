package happy.jayden.yang.agentbuilder.core.component;

public record ComponentVersion(int value) implements Comparable<ComponentVersion> {
  public ComponentVersion {
    if (value < 1) {
      throw new IllegalArgumentException("version must be at least 1");
    }
  }

  @Override
  public int compareTo(ComponentVersion other) {
    return Integer.compare(value, other.value);
  }
}
