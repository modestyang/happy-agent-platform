package happy.jayden.yang.agentbuilder.core.runtime;

/** Framework-neutral lifecycle hook invoked before and after a model run. */
public interface AgentHook {
  String key();

  HookDecision beforeRun(AgentExecutionContext context);

  void afterRun(AgentExecutionContext context, AgentRunResult result);
}
