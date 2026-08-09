package happy.jayden.yang.agentbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.runtime.AgentExecutionContext;
import happy.jayden.yang.agentbuilder.core.runtime.HookDecision;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FitnessSafetyHookTest {

  private final FitnessSafetyHook hook = new FitnessSafetyHook();

  @Test
  void blocksClearAcuteAndOvertrainingSignalsBeforeModelExecution() {
    for (String message :
        java.util.List.of("胸口痛还想继续跑步", "练完头晕得站不稳", "膝盖受伤了继续深蹲", "连续七天不吃饭", "每天练四小时不休息")) {
      HookDecision decision = hook.beforeRun(context(message));

      assertEquals(HookDecision.Action.BLOCK, decision.action(), message);
      assertTrue(decision.message().contains("停止"), message);
    }
  }

  @Test
  void allowsOrdinaryTrainingQuestion() {
    HookDecision decision = hook.beforeRun(context("今天只有三十分钟，帮我安排低冲击训练"));

    assertEquals(HookDecision.Action.ALLOW, decision.action());
    assertTrue(decision.message().isBlank());
  }

  private static AgentExecutionContext context(String message) {
    return new AgentExecutionContext(
        "fitness.coach",
        "run-1",
        "user-1",
        message,
        Set.of(),
        new ToolExecutionContext("user-1", "run-1", Set.of("fitness.read"), "fitness.safety"),
        (toolKey, input, ignored) -> Map.of());
  }
}
