package happy.jayden.yang.agentbuilder.core.runtime;

/** Executes a locally registered Hook selected by a published Agent snapshot. */
@FunctionalInterface
public interface RuntimeHookExecutor {
  void execute(String hookKey, RunRequest.HookContext context) throws Exception;
}
