package happy.jayden.yang.agentbuilder;

import happy.jayden.yang.agentbuilder.core.runtime.AgentExecutionContext;
import happy.jayden.yang.agentbuilder.core.runtime.AgentHook;
import happy.jayden.yang.agentbuilder.core.runtime.ExecutableSkill;
import happy.jayden.yang.agentbuilder.core.runtime.RuntimeCapabilityRegistry;
import happy.jayden.yang.agentbuilder.core.runtime.SkillResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Runtime registry for Fitness-owned hooks and deterministic, Tool-backed skill preparation. */
public final class FitnessSkillRegistry implements RuntimeCapabilityRegistry {
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

  private static final class MealSkill implements ExecutableSkill {
    @Override
    public String key() {
      return "fitness.meal.skill";
    }

    @Override
    public SkillResult execute(AgentExecutionContext context, Map<String, Object> input)
        throws Exception {
      var facts = new LinkedHashMap<String, Object>();
      facts.put("bodyMetrics", context.invokeTool("fitness.profile.query", Map.of()));
      facts.put("recentTraining", context.invokeTool("fitness.workout.query", Map.of()));
      facts.put("mealHistory", context.invokeTool("fitness.meal.query", Map.of()));
      facts.put("recentFeedback", context.invokeTool("fitness.meal.feedback_context", Map.of()));
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
                      List.of("breakfast", "lunch", "dinner", "rationale"),
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
      facts.put("goalAndSafety", context.invokeTool("fitness.profile.query", Map.of()));
      facts.put("recentLoad", context.invokeTool("fitness.workout.query", Map.of()));
      facts.put("exerciseLibrary", context.invokeTool("fitness.plan.generate", Map.of()));
      return new SkillResult(
          key(),
          Map.of(
              "kind",
              "weekly-training-plan",
              "persistence",
              "READ_ONLY",
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
