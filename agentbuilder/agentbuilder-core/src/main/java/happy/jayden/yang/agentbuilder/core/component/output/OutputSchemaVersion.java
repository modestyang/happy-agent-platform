package happy.jayden.yang.agentbuilder.core.component.output;

import happy.jayden.yang.agentbuilder.core.component.CatalogComponent;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record OutputSchemaVersion(
    ComponentMetadata metadata,
    CatalogMetadata catalogMetadata,
    ClosedObjectSchema schema,
    List<Example> examples,
    String contentChecksum)
    implements CatalogComponent {
  private static final Pattern CHECKSUM = Pattern.compile("^[a-f0-9]{64}$");

  public OutputSchemaVersion {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
    Objects.requireNonNull(schema, "schema");
    examples = List.copyOf(Objects.requireNonNull(examples, "examples"));
    validateExamples(schema, examples);
    if (contentChecksum == null || !CHECKSUM.matcher(contentChecksum).matches())
      throw new IllegalArgumentException("invalid schema checksum");
  }

  public record ClosedObjectSchema(List<Field> fields) {
    public ClosedObjectSchema {
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
      if (fields.isEmpty()) throw new IllegalArgumentException("schema requires fields");
      var names = new LinkedHashSet<String>();
      for (var field : fields)
        if (!names.add(field.name())) throw new IllegalArgumentException("duplicate schema field");
    }
  }

  public record Field(String name, Type type, boolean required, String description) {
    public Field {
      if (name == null
          || !name.matches("^[a-z][a-zA-Z0-9_]{0,79}$")
          || description == null
          || description.isBlank()) throw new IllegalArgumentException("invalid schema field");
      Objects.requireNonNull(type, "type");
    }
  }

  public record Example(String name, List<Value> values) {
    public Example {
      if (name == null || name.isBlank())
        throw new IllegalArgumentException("example name required");
      values = List.copyOf(Objects.requireNonNull(values, "values"));
    }
  }

  public record Value(String field, String value) {
    public Value {
      if (field == null || field.isBlank() || value == null)
        throw new IllegalArgumentException("invalid example value");
    }
  }

  public enum Type {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    ARRAY,
    OBJECT
  }

  private static void validateExamples(ClosedObjectSchema schema, List<Example> examples) {
    var fields =
        schema.fields().stream()
            .collect(java.util.stream.Collectors.toMap(Field::name, item -> item));
    for (var example : examples) {
      var present = new LinkedHashSet<String>();
      for (var value : example.values()) {
        if (!fields.containsKey(value.field()) || !present.add(value.field()))
          throw new IllegalArgumentException("example must contain unique declared schema fields");
      }
      if (fields.values().stream()
          .filter(Field::required)
          .anyMatch(field -> !present.contains(field.name())))
        throw new IllegalArgumentException("example must contain every required schema field");
    }
  }
}
