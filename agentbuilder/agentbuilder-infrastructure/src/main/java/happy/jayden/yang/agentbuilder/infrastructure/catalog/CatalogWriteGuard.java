package happy.jayden.yang.agentbuilder.infrastructure.catalog;

import happy.jayden.yang.agentbuilder.core.component.ComponentStatus;
import java.util.Optional;
import java.util.function.Supplier;

final class CatalogWriteGuard {
  private CatalogWriteGuard() {}

  static int updated(int rows) {
    if (rows == 0) throw new OptimisticCatalogLockException("stale catalog revision");
    if (rows != 1) throw new IllegalStateException("catalog write affected " + rows + " rows");
    return rows;
  }

  static int updatedDraft(int rows, Supplier<Optional<ComponentStatus>> persistedStatus) {
    if (rows == 1) return rows;
    if (rows != 0) throw new IllegalStateException("catalog write affected " + rows + " rows");
    var status = persistedStatus.get();
    if (status.isPresent() && status.orElseThrow() != ComponentStatus.DRAFT)
      throw new ImmutableCatalogVersionException("published catalog versions are immutable");
    throw new OptimisticCatalogLockException("stale catalog revision");
  }
}
