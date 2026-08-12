package happy.jayden.yang.agentbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.runtime.AgentExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FitnessSkillRegistryTest {

  @Test
  void mealSkillBuildsStructuredThreeMealInputOnlyFromAuthorizedTools() throws Exception {
    var calls = new ArrayList<String>();
    var registry = new FitnessSkillRegistry(new FitnessSafetyHook());
    var context = context(calls);

    var result =
        registry
            .skill("fitness.meal.skill")
            .orElseThrow()
            .execute(context, Map.of("date", "2026-08-09"));

    assertEquals("fitness.meal.skill", result.key());
    assertEquals("three-meal-plan", result.value().get("kind"));
    assertEquals(
        List.of(
            "fitness.user.profile.query",
            "fitness.goal.current.query",
            "fitness.body.latest.query",
            "fitness.nutrition.preferences.query",
            "fitness.workout.summary.query",
            "fitness.meal.summary.query",
            "fitness.meal.history.query",
            "fitness.meal.recommendations.query",
            "fitness.meal.feedback.query",
            "fitness.nutrition.targets.estimate"),
        calls);
    assertEquals(List.of("BREAKFAST", "LUNCH", "DINNER"), result.value().get("requiredMeals"));
    assertTrue(result.value().containsKey("facts"));
  }

  @Test
  void planSkillBuildsStructuredWeeklyPlanInputOnlyFromAuthorizedTools() throws Exception {
    var calls = new ArrayList<String>();
    var registry = new FitnessSkillRegistry(new FitnessSafetyHook());

    var result =
        registry
            .skill("fitness.plan.skill")
            .orElseThrow()
            .execute(context(calls), Map.of("availableDays", List.of("MONDAY", "WEDNESDAY")));

    assertEquals("fitness.plan.skill", result.key());
    assertEquals("weekly-training-plan", result.value().get("kind"));
    assertEquals(
        List.of(
            "fitness.goal.current.query",
            "fitness.training.constraints.query",
            "fitness.body.latest.query",
            "fitness.workout.summary.query",
            "fitness.workout.schedule.query",
            "fitness.exercise.candidates.query"),
        calls);
    assertEquals("AGENT_DECIDES", result.value().get("persistence"));
    assertTrue(result.value().containsKey("facts"));
  }

  @Test
  void skillsDoNotPassUndeclaredDaysArgumentsToZeroArgumentTools() throws Exception {
    var receivedArguments = new LinkedHashMap<String, Map<String, Object>>();
    var context =
        new AgentExecutionContext(
            "fitness.coach",
            "run-1",
            "user-1",
            "请帮我生成计划",
            Set.of(
                "fitness.user.profile.query",
                "fitness.goal.current.query",
                "fitness.training.constraints.query",
                "fitness.nutrition.preferences.query",
                "fitness.body.latest.query",
                "fitness.workout.summary.query",
                "fitness.workout.schedule.query",
                "fitness.exercise.candidates.query",
                "fitness.meal.summary.query",
                "fitness.meal.history.query",
                "fitness.meal.recommendations.query",
                "fitness.meal.feedback.query",
                "fitness.nutrition.targets.estimate"),
            new ToolExecutionContext("user-1", "run-1", Set.of("fitness.read"), "fitness.skill"),
            (toolKey, input, ignored) -> {
              receivedArguments.put(toolKey, Map.copyOf(input));
              return Map.of("tool", toolKey);
            });
    var registry = new FitnessSkillRegistry(new FitnessSafetyHook());

    registry.skill("fitness.meal.skill").orElseThrow().execute(context, Map.of());
    registry.skill("fitness.plan.skill").orElseThrow().execute(context, Map.of());

    assertEquals(Map.of(), receivedArguments.get("fitness.user.profile.query"));
    assertEquals(Map.of(), receivedArguments.get("fitness.meal.history.query"));
    assertEquals(Map.of(), receivedArguments.get("fitness.meal.feedback.query"));
    assertEquals(Map.of(), receivedArguments.get("fitness.workout.schedule.query"));
    assertEquals(Map.of(), receivedArguments.get("fitness.exercise.candidates.query"));
  }

  private static AgentExecutionContext context(List<String> calls) {
    return new AgentExecutionContext(
        "fitness.coach",
        "run-1",
        "user-1",
        "请帮我生成计划",
        Set.of(
            "fitness.user.profile.query",
            "fitness.goal.current.query",
            "fitness.training.constraints.query",
            "fitness.nutrition.preferences.query",
            "fitness.body.latest.query",
            "fitness.workout.summary.query",
            "fitness.workout.schedule.query",
            "fitness.exercise.candidates.query",
            "fitness.meal.summary.query",
            "fitness.meal.history.query",
            "fitness.meal.recommendations.query",
            "fitness.meal.feedback.query",
            "fitness.nutrition.targets.estimate"),
        new ToolExecutionContext("user-1", "run-1", Set.of("fitness.read"), "fitness.skill"),
        (toolKey, input, ignored) -> {
          calls.add(toolKey);
          return Map.copyOf(
              new LinkedHashMap<>(Map.of("tool", toolKey, "data", "real-tool-result")));
        });
  }
}
