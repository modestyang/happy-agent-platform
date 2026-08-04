package happy.jayden.yang.agentbuilder.core.component;

public sealed interface ConfigValue
    permits StringValue, NumberValue, BooleanValue, StringListValue {}
