package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import happy.jayden.yang.agentbuilder.core.component.BooleanValue;
import happy.jayden.yang.agentbuilder.core.component.ConfigValue;
import happy.jayden.yang.agentbuilder.core.component.NumberValue;
import happy.jayden.yang.agentbuilder.core.component.StringListValue;
import happy.jayden.yang.agentbuilder.core.component.StringValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ConfigValueJacksonModule extends SimpleModule {
  ConfigValueJacksonModule() {
    super("closed-config-value-types");
    addSerializer(ConfigValue.class, new ConfigValueSerializer());
    addDeserializer(ConfigValue.class, new ConfigValueDeserializer());
  }

  private static final class ConfigValueSerializer extends StdSerializer<ConfigValue> {
    private ConfigValueSerializer() {
      super(ConfigValue.class);
    }

    @Override
    public void serialize(
        ConfigValue value, JsonGenerator generator, SerializerProvider serializers)
        throws IOException {
      generator.writeStartObject();
      if (value instanceof StringValue stringValue) {
        generator.writeStringField("kind", "string");
        generator.writeStringField("value", stringValue.value());
      } else if (value instanceof NumberValue numberValue) {
        generator.writeStringField("kind", "number");
        generator.writeNumberField("value", numberValue.value());
      } else if (value instanceof BooleanValue booleanValue) {
        generator.writeStringField("kind", "boolean");
        generator.writeBooleanField("value", booleanValue.value());
      } else if (value instanceof StringListValue listValue) {
        generator.writeStringField("kind", "stringList");
        generator.writeObjectField("value", listValue.value());
      } else {
        throw new IOException("unsupported closed config value type");
      }
      generator.writeEndObject();
    }
  }

  private static final class ConfigValueDeserializer extends StdDeserializer<ConfigValue> {
    private static final Set<String> FIELDS = Set.of("kind", "value");

    private ConfigValueDeserializer() {
      super(ConfigValue.class);
    }

    @Override
    public ConfigValue deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode node = parser.getCodec().readTree(parser);
      if (!node.isObject()
          || !node.has("kind")
          || !node.has("value")
          || !fieldNames(node).equals(FIELDS))
        throw JsonMappingException.from(parser, "config value must contain only kind and value");
      var kind = node.path("kind").textValue();
      var value = node.path("value");
      if ("string".equals(kind)) return new StringValue(requireText(value, context));
      if ("number".equals(kind) && value.isNumber()) return new NumberValue(value.decimalValue());
      if ("boolean".equals(kind) && value.isBoolean())
        return new BooleanValue(value.booleanValue());
      if ("stringList".equals(kind)) return new StringListValue(requireStringList(value, context));
      throw JsonMappingException.from(parser, "unknown config value kind: " + kind);
    }

    private static Set<String> fieldNames(JsonNode node) {
      var names = new HashSet<String>();
      node.fieldNames().forEachRemaining(names::add);
      return names;
    }

    private static String requireText(JsonNode value, DeserializationContext context)
        throws IOException {
      if (value.isTextual()) return value.textValue();
      throw JsonMappingException.from(context.getParser(), "config string value must be text");
    }

    private static List<String> requireStringList(JsonNode value, DeserializationContext context)
        throws IOException {
      if (!value.isArray())
        throw JsonMappingException.from(
            context.getParser(), "config string-list value must be an array");
      var values = new ArrayList<String>();
      for (var item : value) {
        if (!item.isTextual())
          throw JsonMappingException.from(
              context.getParser(), "config string-list items must be text");
        values.add(item.textValue());
      }
      return values;
    }
  }
}
