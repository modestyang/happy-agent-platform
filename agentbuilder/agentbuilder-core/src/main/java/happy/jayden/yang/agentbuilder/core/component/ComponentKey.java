package happy.jayden.yang.agentbuilder.core.component;

public record ComponentKey(String value) implements Comparable<ComponentKey> {
  public ComponentKey {
    ComponentValidation.requireKey(value);
  }

  @Override
  public int compareTo(ComponentKey other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
