package happy.jayden.yang.agentbuilder.core.component.framework;

import happy.jayden.yang.agentbuilder.core.component.CatalogComponent;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import java.util.List;
import java.util.Objects;

/** Exact-version framework adapter capabilities, separate from model capabilities. */
public record FrameworkAdapterDefinition(
    ComponentMetadata metadata,
    String displayName,
    String description,
    List<String> tags,
    boolean supportsTools,
    boolean supportsSkills,
    boolean supportsHooks,
    CatalogMetadata catalogMetadata)
    implements CatalogComponent {
  public FrameworkAdapterDefinition {
    Objects.requireNonNull(metadata, "metadata");
    text(displayName, "displayName");
    text(description, "description");
    tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
  }

  private static void text(String value, String name) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(name + " must not be blank");
  }
}
