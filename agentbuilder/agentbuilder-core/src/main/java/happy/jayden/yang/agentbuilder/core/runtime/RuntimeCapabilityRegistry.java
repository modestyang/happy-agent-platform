package happy.jayden.yang.agentbuilder.core.runtime;

/**
 * Runtime handlers discoverable by the current application process.
 *
 * <p>The catalog is intentionally not the source of truth for executable behavior: a component may
 * be marked {@code AVAILABLE} in storage only when this registry also has its matching handler.
 * Keeping this boundary in core lets the control plane validate readiness without taking a
 * dependency on a business application module.
 */
@FunctionalInterface
public interface RuntimeCapabilityRegistry {

  /** Returns whether the running process can execute the capability identified by type and key. */
  boolean hasHandler(String componentType, String componentKey);
}
