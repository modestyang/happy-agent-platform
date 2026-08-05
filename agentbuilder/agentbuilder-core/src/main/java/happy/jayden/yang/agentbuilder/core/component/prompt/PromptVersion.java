package happy.jayden.yang.agentbuilder.core.component.prompt;

import happy.jayden.yang.agentbuilder.core.component.CatalogComponent;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record PromptVersion(
    ComponentMetadata metadata,
    CatalogMetadata catalogMetadata,
    TemplateFormat templateFormat,
    String template,
    List<Variable> variables,
    String contentChecksum)
    implements CatalogComponent {
  private static final Pattern CHECKSUM = Pattern.compile("^[a-f0-9]{64}$");

  public PromptVersion {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
    Objects.requireNonNull(templateFormat, "templateFormat");
    if (template == null || template.isBlank())
      throw new IllegalArgumentException("template must not be blank");
    variables = unique(variables);
    if (contentChecksum == null || !CHECKSUM.matcher(contentChecksum).matches())
      throw new IllegalArgumentException("contentChecksum must be a lowercase SHA-256 value");
  }

  public record Variable(String name, Type type, boolean required) {
    public Variable {
      if (name == null || !name.matches("^[a-z][a-zA-Z0-9_]{0,79}$"))
        throw new IllegalArgumentException("invalid prompt variable name");
      Objects.requireNonNull(type, "type");
    }
  }

  public enum TemplateFormat {
    MARKDOWN,
    MUSTACHE,
    JINJA
  }

  public enum Type {
    STRING,
    NUMBER,
    BOOLEAN,
    JSON
  }

  private static List<Variable> unique(List<Variable> values) {
    var copy = List.copyOf(Objects.requireNonNull(values, "variables"));
    var names = new LinkedHashSet<String>();
    for (var value : copy)
      if (!names.add(value.name())) throw new IllegalArgumentException("duplicate prompt variable");
    return copy;
  }
}
