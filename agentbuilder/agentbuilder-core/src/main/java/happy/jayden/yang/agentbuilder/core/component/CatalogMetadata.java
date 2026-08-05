package happy.jayden.yang.agentbuilder.core.component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable presentation, provenance, and compatibility metadata shared by catalog definitions. */
public record CatalogMetadata(
    String displayName,
    String description,
    String category,
    List<String> tags,
    List<ComponentRef> compatibleFrameworks,
    Source source,
    Audit audit,
    long revision) {
  public CatalogMetadata {
    text(displayName, 120, "displayName");
    text(description, 4_000, "description");
    text(category, 80, "category");
    tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    if (tags.size() > 20) throw new IllegalArgumentException("tags cannot exceed 20");
    tags.forEach(tag -> text(tag, 80, "tag"));
    compatibleFrameworks =
        List.copyOf(Objects.requireNonNull(compatibleFrameworks, "compatibleFrameworks"));
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(audit, "audit");
    if (revision < 1) throw new IllegalArgumentException("revision must be positive");
  }

  public boolean supports(ComponentRef framework) {
    return compatibleFrameworks.contains(framework);
  }

  public record Source(SourceType type, String locator) {
    public Source {
      Objects.requireNonNull(type, "type");
      text(locator, 500, "locator");
    }
  }

  public record Audit(String createdBy, Instant createdAt) {
    public Audit {
      text(createdBy, 120, "createdBy");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  public enum SourceType {
    INTERNAL,
    IMPORTED,
    REGISTRY,
    MANUAL
  }

  private static void text(String value, int max, String field) {
    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max)
      throw new IllegalArgumentException(
          field + " must be non-blank and at most " + max + " characters");
  }
}
