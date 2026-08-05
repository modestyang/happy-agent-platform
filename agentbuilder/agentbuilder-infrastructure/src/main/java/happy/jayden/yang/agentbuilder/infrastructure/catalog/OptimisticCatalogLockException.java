package happy.jayden.yang.agentbuilder.infrastructure.catalog;

public final class OptimisticCatalogLockException extends RuntimeException {
  public OptimisticCatalogLockException(String message) {
    super(message);
  }
}
