package happy.jayden.yang.agentbuilder.core.component.skill;

import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record SkillDefinition(
    ComponentMetadata metadata,
    String markdown,
    List<Resource> resources,
    String contentChecksum,
    ProgressiveDisclosure disclosure,
    List<happy.jayden.yang.agentbuilder.core.component.ComponentRef> requiredTools,
    CatalogMetadata catalogMetadata)
    implements happy.jayden.yang.agentbuilder.core.component.CatalogComponent {
  private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
  private static final Set<String> EXECUTABLE_EXTENSIONS =
      Set.of("jar", "class", "java", "py", "pyw", "pyc", "sh", "bash", "zsh", "ksh", "command");
  private static final java.util.Map<String, String> DECLARATIVE_RESOURCE_TYPES =
      java.util.Map.ofEntries(
          java.util.Map.entry("md", "text/markdown"),
          java.util.Map.entry("json", "application/json"),
          java.util.Map.entry("yaml", "application/yaml"),
          java.util.Map.entry("yml", "application/x-yaml"),
          java.util.Map.entry("png", "image/png"),
          java.util.Map.entry("jpg", "image/jpeg"),
          java.util.Map.entry("jpeg", "image/jpeg"),
          java.util.Map.entry("webp", "image/webp"),
          java.util.Map.entry("gif", "image/gif"),
          java.util.Map.entry("svg", "image/svg+xml"));

  public SkillDefinition {
    Objects.requireNonNull(metadata, "metadata");
    requireText(markdown, "markdown");
    resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
    requireChecksum(contentChecksum, "contentChecksum");
    Objects.requireNonNull(disclosure, "disclosure");
    requiredTools = List.copyOf(Objects.requireNonNull(requiredTools, "requiredTools"));
    requiredTools.forEach(value -> Objects.requireNonNull(value, "requiredTool"));
    Objects.requireNonNull(catalogMetadata, "catalogMetadata");
  }

  public record Resource(String path, String mediaType, String checksum) {
    public Resource {
      requireText(path, "path");
      requireText(mediaType, "mediaType");
      requireChecksum(checksum, "checksum");
      var extension = extension(path);
      if (EXECUTABLE_EXTENSIONS.contains(extension)
          || !mediaType.equalsIgnoreCase(DECLARATIVE_RESOURCE_TYPES.get(extension)))
        throw new IllegalArgumentException(
            "skill resources must be declarative non-executable assets");
    }

    private static String extension(String path) {
      var index = path.lastIndexOf('.');
      return index < 0 ? "" : path.substring(index + 1).toLowerCase();
    }
  }

  public record ProgressiveDisclosure(
      List<String> alwaysIncludedResources, List<String> onDemandResources) {
    public ProgressiveDisclosure {
      alwaysIncludedResources =
          List.copyOf(Objects.requireNonNull(alwaysIncludedResources, "alwaysIncludedResources"));
      onDemandResources =
          List.copyOf(Objects.requireNonNull(onDemandResources, "onDemandResources"));
    }

    public static ProgressiveDisclosure none() {
      return new ProgressiveDisclosure(List.of(), List.of());
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(name + " must not be blank");
  }

  private static void requireChecksum(String value, String name) {
    if (value == null || !SHA256.matcher(value).matches())
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
  }
}
