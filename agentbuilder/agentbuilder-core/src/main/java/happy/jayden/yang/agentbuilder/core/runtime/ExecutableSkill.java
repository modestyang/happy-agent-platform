package happy.jayden.yang.agentbuilder.core.runtime;

import java.util.Map;

/** A deterministic capability that enriches an Agent request through bound Tools only. */
public interface ExecutableSkill {
  String key();

  SkillResult execute(AgentExecutionContext context, Map<String, Object> input) throws Exception;
}
