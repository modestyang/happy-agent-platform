package happy.jayden.yang.agentbuilder.core.runtime;

/** Explicit runtime features used by publish validation before a run starts. */
public record FrameworkCapabilities(
    boolean tools,
    boolean skills,
    boolean hooks,
    boolean memory,
    boolean streaming,
    boolean cancellation) {}
