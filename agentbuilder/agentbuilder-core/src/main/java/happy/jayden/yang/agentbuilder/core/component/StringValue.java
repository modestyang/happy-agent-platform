package happy.jayden.yang.agentbuilder.core.component;

public record StringValue(String value) implements ConfigValue {
  public StringValue {
    TextValidation.requireLength(value, 0, 20_000, "value");
  }
}
