package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.core.runtime.AgentExecutionContext;
import happy.jayden.yang.agentbuilder.core.runtime.AgentHook;
import happy.jayden.yang.agentbuilder.core.runtime.ExecutableSkill;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.runtime.RuntimeCapabilityRegistry;
import happy.jayden.yang.agentbuilder.core.runtime.RuntimeHookExecutor;
import happy.jayden.yang.agentbuilder.core.runtime.SkillResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Runtime registry for Fitness-owned hooks and deterministic, Tool-backed skill preparation. */
public final class FitnessSkillRegistry implements RuntimeCapabilityRegistry, RuntimeHookExecutor {
  private final Map<String, ExecutableSkill> skills;
  private final Map<String, AgentHook> hooks;

  public FitnessSkillRegistry(FitnessSafetyHook safetyHook) {
    Objects.requireNonNull(safetyHook, "safetyHook");
    skills =
        Map.of(
            "fitness.meal.skill", new MealSkill(),
            "fitness.plan.skill", new PlanSkill());
    hooks = Map.of(safetyHook.key(), safetyHook);
  }

  @Override
  public boolean hasHandler(String componentType, String componentKey) {
    if ("SKILL".equals(componentType)) return skills.containsKey(componentKey);
    if ("HOOK".equals(componentType)) return hooks.containsKey(componentKey);
    return false;
  }

  public Optional<ExecutableSkill> skill(String key) {
    return Optional.ofNullable(skills.get(key));
  }

  public Optional<AgentHook> hook(String key) {
    return Optional.ofNullable(hooks.get(key));
  }

  @Override
  public void execute(String hookKey, RunRequest.HookContext context) {
    var hook =
        hook(hookKey).orElseThrow(() -> new IllegalArgumentException("unknown Hook: " + hookKey));
    var execution =
        new AgentExecutionContext(
            "published-agent",
            context.runId(),
            context.userId(),
            context.input(),
            java.util.Set.of(),
            new happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext(
                context.userId(), context.runId(), java.util.Set.of(), "runtime-hook"),
            (toolKey, input, toolContext) -> {
              throw new UnsupportedOperationException("Hook cannot invoke Tools in this phase");
            });
    var decision = hook.beforeRun(execution);
    if (decision.action()
        == happy.jayden.yang.agentbuilder.core.runtime.HookDecision.Action.BLOCK) {
      throw new IllegalStateException(decision.message());
    }
  }

  private static final class MealSkill implements ExecutableSkill {
    @Override
    public String key() {
      return "fitness.meal.skill";
    }

    @Override
    public SkillResult execute(AgentExecutionContext context, Map<String, Object> input)
        throws Exception {
      var facts = new LinkedHashMap<String, Object>();
      facts.put("profile", context.invokeTool("fitness.user.profile.query", Map.of()));
      facts.put("goal", context.invokeTool("fitness.goal.current.query", Map.of()));
      facts.put("body", context.invokeTool("fitness.body.latest.query", Map.of()));
      facts.put("preferences", context.invokeTool("fitness.nutrition.preferences.query", Map.of()));
      facts.put("recentTraining", context.invokeTool("fitness.workout.summary.query", Map.of()));
      facts.put("mealSummary", context.invokeTool("fitness.meal.summary.query", Map.of()));
      facts.put("mealHistory", context.invokeTool("fitness.meal.history.query", Map.of()));
      facts.put(
          "recommendations", context.invokeTool("fitness.meal.recommendations.query", Map.of()));
      facts.put("recentFeedback", context.invokeTool("fitness.meal.feedback.query", Map.of()));
      facts.put(
          "nutritionTargets", context.invokeTool("fitness.nutrition.targets.estimate", Map.of()));
      return new SkillResult(
          key(),
          Map.of(
              "kind", "three-meal-plan",
              "requiredMeals", List.of("BREAKFAST", "LUNCH", "DINNER"),
              "facts", facts,
              "input", Map.copyOf(input),
              "outputContract",
                  Map.of(
                      "type",
                      "object",
                      "required",
                      List.of("recommendations"),
                      "mealTypes",
                      List.of("BREAKFAST", "LUNCH", "DINNER"),
                      "language",
                      "zh-CN",
                      "additionalProperties",
                      false)));
    }
  }

  private static final class PlanSkill implements ExecutableSkill {
    @Override
    public String key() {
      return "fitness.plan.skill";
    }

    @Override
    public SkillResult execute(AgentExecutionContext context, Map<String, Object> input)
        throws Exception {
      var facts = new LinkedHashMap<String, Object>();
      facts.put("goal", context.invokeTool("fitness.goal.current.query", Map.of()));
      facts.put("constraints", context.invokeTool("fitness.training.constraints.query", Map.of()));
      facts.put("body", context.invokeTool("fitness.body.latest.query", Map.of()));
      facts.put("recentLoad", context.invokeTool("fitness.workout.summary.query", Map.of()));
      facts.put("schedule", context.invokeTool("fitness.workout.schedule.query", Map.of()));
      facts.put(
          "exerciseCandidates", context.invokeTool("fitness.exercise.candidates.query", Map.of()));
      return new SkillResult(
          key(),
          Map.of(
              "kind",
              "weekly-training-plan",
              "persistence",
              "AGENT_DECIDES",
              "facts",
              facts,
              "input",
              Map.copyOf(input),
              "outputContract",
              Map.of(
                  "type",
                  "object",
                  "required",
                  List.of("days", "safetyNotes", "progression"),
                  "additionalProperties",
                  false)));
    }
  }
}
