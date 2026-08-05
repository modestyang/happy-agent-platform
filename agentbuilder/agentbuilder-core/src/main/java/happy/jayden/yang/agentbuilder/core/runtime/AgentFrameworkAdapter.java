package happy.jayden.yang.agentbuilder.core.runtime;

import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig;
import reactor.core.publisher.Flux;

/** Framework-neutral execution boundary implemented by each supported agent runtime. */
public interface AgentFrameworkAdapter {
  String key();

  FrameworkCapabilities capabilities();

  void validate(ResolvedAgentConfig resolvedAgentConfig);

  Flux<RunEvent> run(RunRequest request);
}
