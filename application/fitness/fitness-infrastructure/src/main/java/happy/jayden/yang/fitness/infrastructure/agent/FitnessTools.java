package happy.jayden.yang.fitness.infrastructure.agent;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolRiskLevel;
import happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapData;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.math.BigDecimal;

/** Local, user-scoped tools exposed by the fitness application to an Agent runtime. */
public final class FitnessTools {
  private final FitnessApplicationService fitness;

  public FitnessTools(FitnessApplicationService fitness) {
    this.fitness = Objects.requireNonNull(fitness, "fitness");
  }

  @AgentTool(
      key = "fitness.profile.query",
      version = 1,
      runtimeName = "fitness_profile_query",
      displayName = "读取用户档案",
      description = "读取当前用户的目标、基础资料和最近身体指标。",
      whenToUse = "需要基于用户当前目标或体重腰围给出建议时使用。",
      whenNotToUse = "不要用于查询其他用户或修改用户资料。",
      applicationKey = "fitness",
      group = "profile",
      tags = {"健身", "档案"},
      requiredScopes = {"fitness.read"})
  public ProfileResult profile(ToolExecutionContext context) {
    var data = load(context);
    return new ProfileResult(
        data.user().nickname(),
        data.goal().name(),
        data.goal().startWeightJin().toPlainString(),
        data.goal().targetWeightJin().toPlainString(),
        data.bodyRecords().stream().limit(1).map(item -> item.weightJin()).findFirst().orElse(null));
  }

  @AgentTool(
      key = "fitness.workout.query",
      version = 1,
      runtimeName = "fitness_workout_query",
      displayName = "读取训练记录",
      description = "读取当前用户当天训练计划和累计完成次数。",
      whenToUse = "需要判断今天训练内容或近期训练执行情况时使用。",
      whenNotToUse = "不要用于生成或修改训练计划。",
      applicationKey = "fitness",
      group = "workout",
      tags = {"健身", "训练"},
      requiredScopes = {"fitness.read"})
  public WorkoutResult workout(ToolExecutionContext context) {
    var data = load(context);
    var plan = data.plan();
    return new WorkoutResult(
        plan == null ? null : plan.title(),
        plan == null ? 0 : plan.estimatedMinutes(),
        plan == null ? List.of() : plan.exercises().stream().map(item -> item.name()).toList(),
        data.completedWorkoutCount());
  }

  @AgentTool(
      key = "fitness.meal.query",
      version = 1,
      runtimeName = "fitness_meal_query",
      displayName = "读取饮食记录",
      description = "读取当前用户最近饮食记录和今日三餐推荐。",
      whenToUse = "需要给出下一餐建议或复盘饮食记录时使用。",
      whenNotToUse = "不要用于写入饮食记录。",
      applicationKey = "fitness",
      group = "meal",
      tags = {"健身", "饮食"},
      requiredScopes = {"fitness.read"})
  public MealResult meal(ToolExecutionContext context) {
    var data = load(context);
    return new MealResult(
        data.meals().stream().limit(5).map(item -> item.items().stream().map(food -> food.name()).toList()).toList(),
        data.mealRecommendations().stream()
            .map(item -> item.mealType().name() + ":" + item.items().stream().map(food -> food.name()).toList())
            .toList());
  }

  @AgentTool(
      key = "fitness.plan.generate",
      version = 1,
      runtimeName = "fitness_plan_generate",
      displayName = "生成训练计划建议",
      description = "基于当前目标和当天计划生成确定性的训练建议，不写入数据库。",
      whenToUse = "用户需要一份可执行的今日训练建议时使用。",
      whenNotToUse = "不要用于持久化计划或替代已发布的写入型计划工具。",
      applicationKey = "fitness",
      group = "plan",
      tags = {"健身", "训练计划"},
      sideEffect = ToolSideEffect.NONE,
      risk = ToolRiskLevel.LOW,
      requiredScopes = {"fitness.read"})
  public PlanSuggestionResult planSuggestion(ToolExecutionContext context) {
    var data = load(context);
    var plan = data.plan();
    if (plan == null) return new PlanSuggestionResult("今天暂无训练计划，建议进行 15 分钟低冲击有氧。", List.of());
    return new PlanSuggestionResult(
        "今天推荐完成「" + plan.title() + "」，预计 " + plan.estimatedMinutes() + " 分钟。",
        plan.exercises().stream().map(item -> item.name()).toList());
  }

  private BootstrapData load(ToolExecutionContext context) {
    return fitness.loadForTool(UUID.fromString(context.userId()));
  }

  public record ProfileResult(
      @AgentToolParam(description = "用户昵称") String nickname,
      @AgentToolParam(description = "当前目标名称") String goalName,
      @AgentToolParam(description = "起始体重，单位斤") String startWeightJin,
      @AgentToolParam(description = "目标体重，单位斤") String targetWeightJin,
      @AgentToolParam(description = "最新体重，单位斤", required = false) BigDecimal latestWeightJin) {}

  public record WorkoutResult(
      @AgentToolParam(description = "当天训练标题", required = false) String planTitle,
      @AgentToolParam(description = "预计训练时长，分钟") int estimatedMinutes,
      @AgentToolParam(description = "训练动作名称") List<String> exercises,
      @AgentToolParam(description = "累计完成训练次数") long completedWorkoutCount) {}

  public record MealResult(
      @AgentToolParam(description = "最近餐食中的食物名称") List<List<String>> recentMeals,
      @AgentToolParam(description = "今日餐食推荐") List<String> todayRecommendations) {}

  public record PlanSuggestionResult(
      @AgentToolParam(description = "训练建议摘要") String summary,
      @AgentToolParam(description = "建议动作名称") List<String> exercises) {}
}
