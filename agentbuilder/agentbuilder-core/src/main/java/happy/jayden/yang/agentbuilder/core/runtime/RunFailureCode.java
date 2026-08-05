package happy.jayden.yang.agentbuilder.core.runtime;

/** Stable, framework-neutral failure categories exposed to callers and persistence. */
public enum RunFailureCode {
  VALIDATION,
  CONFIGURATION,
  MODEL,
  TOOL,
  HOOK,
  MEMORY,
  TIMEOUT,
  CANCELLED,
  INTERNAL
}
