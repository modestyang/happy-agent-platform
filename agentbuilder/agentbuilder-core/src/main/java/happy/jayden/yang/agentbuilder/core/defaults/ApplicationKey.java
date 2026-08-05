package happy.jayden.yang.agentbuilder.core.defaults;

public record ApplicationKey(String value) {
  public ApplicationKey {
    if (value == null || value.isBlank() || value.length() > 120)
      throw new IllegalArgumentException("application key must be 1-120 characters");
  }

  @Override
  public String toString() {
    return value;
  }
}
