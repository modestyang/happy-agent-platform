package happy.jayden.yang.agentbuilder.core.catalog;

import happy.jayden.yang.agentbuilder.core.component.ComponentStatus;
import java.util.Objects;
import java.util.Optional;

/** Search criteria supported by every typed catalog table. */
public record CatalogFilter(
    String applicationScope, Optional<ComponentStatus> status, Optional<String> tag) {
  public CatalogFilter {
    if (applicationScope == null || applicationScope.isBlank())
      throw new IllegalArgumentException("applicationScope must not be blank");
    status = Objects.requireNonNull(status, "status");
    tag = Objects.requireNonNull(tag, "tag");
    tag.ifPresent(
        value -> {
          if (value.isBlank()) throw new IllegalArgumentException("tag must not be blank");
        });
  }

  public static CatalogFilter application(String applicationScope) {
    return new CatalogFilter(applicationScope, Optional.empty(), Optional.empty());
  }
}
