package happy.jayden.yang.fitness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime.TaskConfigurationException;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.PublishedAgentPlaygroundRuntime.TaskExecutionException;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.StreamingChatClient;
import happy.jayden.yang.fitness.service.FitnessDtos.DailyMealPlanGenerationResult;
import happy.jayden.yang.fitness.service.FitnessDtos.GeneratedMealRecommendation;
import happy.jayden.yang.fitness.service.FitnessDtos.MealItemDto;
import happy.jayden.yang.fitness.service.FitnessDtos.MealType;
import happy.jayden.yang.fitness.service.FitnessPorts.DailyMealPlanGenerationPort;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Validates the output of the strictly selected daily-meal background Agent Skill. */
public final class MealPlanGenerationRuntime implements DailyMealPlanGenerationPort {
  static final String AGENT_KEY = "fitness.coach";
  static final String SKILL_KEY = "fitness.meal.skill";
  private final ObjectMapper mapper;
  private final AgentTaskRunner tasks;

  public MealPlanGenerationRuntime(ObjectMapper mapper, AgentTaskRunner tasks) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
  }

  @Override
  public DailyMealPlanGenerationResult generate(UUID userId, LocalDate date) {
    try {
      String input =
          mapper.writeValueAsString(Map.of("taskType", "DAILY_MEAL_PLAN", "date", date.toString()));
      String raw = tasks.run(new AgentTaskRequest(AGENT_KEY, userId, SKILL_KEY, input));
      String visible = StreamingChatClient.visibleJsonContent(raw);
      if (visible.isBlank()) throw new IllegalArgumentException("empty task output");
      return result(mapper.readTree(visible));
    } catch (TaskConfigurationException exception) {
      return failed("DEPENDENCY_NOT_CONFIGURED", exception.getMessage());
    } catch (TaskExecutionException exception) {
      return failed("DEPENDENCY_UNAVAILABLE", exception.getMessage());
    } catch (IOException | IllegalArgumentException exception) {
      return failed("INVALID_MODEL_RESPONSE", "三餐 Agent 返回不符合约束");
    }
  }

  DailyMealPlanGenerationResult result(JsonNode response) {
    try {
      return new DailyMealPlanGenerationResult(
          "SUCCEEDED", parseRecommendations(response), null, null);
    } catch (IllegalArgumentException exception) {
      return failed("INVALID_MODEL_RESPONSE", "三餐生成模型返回不符合约束");
    }
  }

  List<GeneratedMealRecommendation> parseRecommendations(JsonNode response) {
    if (!response.isObject() || response.size() != 1 || !response.has("recommendations")) {
      throw new IllegalArgumentException("response shape");
    }
    JsonNode values = response.get("recommendations");
    if (!values.isArray() || values.size() != 3)
      throw new IllegalArgumentException("recommendations");
    java.util.Set<MealType> types = java.util.EnumSet.noneOf(MealType.class);
    List<GeneratedMealRecommendation> parsed = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isObject()
          || value.size() != 3
          || !value.has("mealType")
          || !value.has("items")
          || !value.has("reason")
          || !value.get("mealType").isTextual()
          || !value.get("reason").isTextual()) throw new IllegalArgumentException("recommendation");
      MealType type = MealType.valueOf(value.get("mealType").textValue());
      if (!types.add(type) || type == MealType.SNACK)
        throw new IllegalArgumentException("meal type");
      String reason = value.get("reason").textValue();
      if (reason.isBlank()
          || reason.codePointCount(0, reason.length()) > 500
          || !containsHan(reason)) {
        throw new IllegalArgumentException("reason");
      }
      JsonNode items = value.get("items");
      if (!items.isArray() || items.isEmpty() || items.size() > 30)
        throw new IllegalArgumentException("items");
      List<MealItemDto> foods = new ArrayList<>();
      for (JsonNode food : items) {
        if (!food.isObject()
            || food.size() != 2
            || !food.has("name")
            || !food.has("estimatedKcal")
            || !food.get("name").isTextual()
            || !food.get("estimatedKcal").isInt()) throw new IllegalArgumentException("food");
        String name = food.get("name").textValue();
        int kcal = food.get("estimatedKcal").intValue();
        if (name.isBlank()
            || name.codePointCount(0, name.length()) > 120
            || !containsHan(name)
            || kcal < 0
            || kcal > 20_000) {
          throw new IllegalArgumentException("food values");
        }
        foods.add(new MealItemDto(name, kcal));
      }
      parsed.add(new GeneratedMealRecommendation(type, foods, reason));
    }
    return parsed;
  }

  private static boolean containsHan(String value) {
    return value
        .codePoints()
        .anyMatch(
            codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
  }

  private static DailyMealPlanGenerationResult failed(String code, String message) {
    return new DailyMealPlanGenerationResult("FAILED", List.of(), code, message);
  }

  @FunctionalInterface
  public interface AgentTaskRunner {
    String run(AgentTaskRequest request);
  }

  public record AgentTaskRequest(
      String agentKey, UUID userId, String requiredSkillKey, String input) {}
}
