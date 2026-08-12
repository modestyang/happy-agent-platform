package happy.jayden.yang.fitness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MealPlanGenerationRuntimeTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void generatesThroughTheRequiredMealSkillAndParsesItsJsonOutput() throws Exception {
    var captured = new AtomicReference<MealPlanGenerationRuntime.AgentTaskRequest>();
    var runtime =
        new MealPlanGenerationRuntime(
            mapper,
            request -> {
              captured.set(request);
              return """
                  ```json
                  {"recommendations":[
                    {"mealType":"BREAKFAST","items":[{"name":"燕麦鸡蛋杯","estimatedKcal":320}],"reason":"补充蛋白质和复合碳水"},
                    {"mealType":"LUNCH","items":[{"name":"鸡胸肉杂粮饭","estimatedKcal":520}],"reason":"配合训练保持均衡能量"},
                    {"mealType":"DINNER","items":[{"name":"清蒸鱼时蔬","estimatedKcal":420}],"reason":"晚餐清淡并保证优质蛋白"}
                  ]}
                  ```
                  """;
            });
    UUID userId = UUID.randomUUID();

    var result = runtime.generate(userId, LocalDate.of(2026, 8, 12));

    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.recommendations()).hasSize(3);
    assertThat(captured.get().agentKey()).isEqualTo("fitness.coach");
    assertThat(captured.get().userId()).isEqualTo(userId);
    assertThat(captured.get().requiredSkillKey()).isEqualTo("fitness.meal.skill");
    assertThat(mapper.readTree(captured.get().input()))
        .isEqualTo(mapper.readTree("{\"taskType\":\"DAILY_MEAL_PLAN\",\"date\":\"2026-08-12\"}"));
  }

  @Test
  void rejectsRecommendationsWhoseUserFacingCopyIsOnlyEnglish() throws Exception {
    var runtime = new MealPlanGenerationRuntime(mapper, request -> "");
    var result =
        runtime.result(
            mapper.readTree(
                """
                {"recommendations":[
                  {"mealType":"BREAKFAST","items":[{"name":"Greek yogurt","estimatedKcal":300}],"reason":"High protein breakfast"},
                  {"mealType":"LUNCH","items":[{"name":"Chicken salad","estimatedKcal":500}],"reason":"Balanced lunch"},
                  {"mealType":"DINNER","items":[{"name":"Salmon bowl","estimatedKcal":450}],"reason":"Light dinner"}
                ]}
                """));

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.failureCode()).isEqualTo("INVALID_MODEL_RESPONSE");
  }
}
