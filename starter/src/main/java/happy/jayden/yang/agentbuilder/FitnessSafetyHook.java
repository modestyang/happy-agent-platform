package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.core.runtime.AgentExecutionContext;
import happy.jayden.yang.agentbuilder.core.runtime.AgentHook;
import happy.jayden.yang.agentbuilder.core.runtime.AgentRunResult;
import happy.jayden.yang.agentbuilder.core.runtime.HookDecision;
import java.util.Locale;

/** Deterministic, fail-closed health guard that runs before any model request. */
public final class FitnessSafetyHook implements AgentHook {
  private static final String SAFETY_MESSAGE = "请先停止训练和节食，保持安全休息；若症状持续、加重或涉及急性伤痛，请尽快联系医生或急救服务。";

  @Override
  public String key() {
    return "fitness.safety";
  }

  @Override
  public HookDecision beforeRun(AgentExecutionContext context) {
    String message = context.message().toLowerCase(Locale.ROOT);
    if (acuteSignal(message) || extremeDietSignal(message) || overtrainingSignal(message)) {
      return HookDecision.block(SAFETY_MESSAGE);
    }
    return HookDecision.allow();
  }

  @Override
  public void afterRun(AgentExecutionContext context, AgentRunResult result) {
    // The guard is intentionally stateless. A future audit sink can consume this neutral hook API.
  }

  private static boolean acuteSignal(String message) {
    return message.matches(".*(?:胸口痛|胸痛|胸闷|心口痛|头晕|眩晕|站不稳|受伤|扭伤|骨折).*");
  }

  private static boolean extremeDietSignal(String message) {
    return message.matches(".*(?:不吃饭|绝食|极端节食|断食.{0,6}(?:天|周)|每天.{0,12}(?:卡|kcal)).*");
  }

  private static boolean overtrainingSignal(String message) {
    return message.matches(".*(?:每天练.{0,8}(?:小时|h)|连续.{0,8}(?:天|周).{0,10}(?:训练|练)).*");
  }
}
