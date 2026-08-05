package happy.jayden.yang.agentbuilder.core.catalog;

import happy.jayden.yang.agentbuilder.core.component.CatalogComponent;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import java.util.List;
import java.util.Optional;

/** Exact-version persistence port for one catalog aggregate family. */
public interface TypedCatalogRepository<T extends CatalogComponent> {
  void create(String applicationScope, T aggregate);

  Optional<T> find(ComponentKey key, ComponentVersion version);

  List<T> list(CatalogFilter filter);

  void update(T replacement, long expectedRevision);
}
