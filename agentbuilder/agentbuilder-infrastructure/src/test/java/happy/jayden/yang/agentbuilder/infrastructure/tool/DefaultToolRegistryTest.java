package happy.jayden.yang.agentbuilder.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.component.ApprovalPolicy;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.ResultMode;
import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolRiskLevel;
import happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultToolRegistryTest {

  @Test
  void resolvesEnabledBindingOverridesAndReturnsExecutableHandler() throws Exception {
    var registration = scanner().scanRegistration(new QueryTools());
    var registry = new DefaultToolRegistry(List.of(registration));
    var binding =
        binding(
            "fitness.query",
            1,
            true,
            Optional.of("仅在用户询问训练时调用"),
            Optional.of(2_000),
            Optional.of(2),
            Optional.of(ApprovalPolicy.NEVER),
            Optional.of(RetryPolicy.SAFE_ONCE),
            Optional.of(ResultMode.MODEL_CONTEXT));

    var resolved = registry.resolve(List.of(binding)).tools().get(0);

    assertEquals("仅在用户询问训练时调用", resolved.usageGuidance());
    assertEquals(2_000, resolved.timeoutMs());
    assertEquals(2, resolved.maxCallsPerRun());
    assertEquals(ApprovalPolicy.NEVER, resolved.approvalPolicy());
    assertEquals(RetryPolicy.SAFE_ONCE, resolved.retryPolicy());
    assertEquals(ResultMode.MODEL_CONTEXT, resolved.resultMode());
    assertEquals(
        "legs:user-7",
        resolved
            .handler()
            .invoke(
                Map.of("query", "legs"),
                new ToolExecutionContext(
                    "user-7", "run-1", Set.of("workout:read"), "operation-1")));
  }

  @Test
  void rejectsUnknownAndDuplicateBindingsAndSkipsDisabledBindings() {
    var registration = scanner().scanRegistration(new QueryTools());
    var registry = new DefaultToolRegistry(List.of(registration));
    var known = new ToolBinding(key("fitness.query"), version(1), true);

    assertThrows(
        IllegalArgumentException.class,
        () -> registry.resolve(List.of(new ToolBinding(key("fitness.missing"), version(1), true))));
    assertThrows(IllegalArgumentException.class, () -> registry.resolve(List.of(known, known)));
    assertTrue(
        registry
            .resolve(List.of(new ToolBinding(key("fitness.query"), version(1), false)))
            .tools()
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DefaultToolRegistry(List.of(registration, registration)));
  }

  @Test
  void rejectsOverridesThatExceedDescriptorCeilingsOrExecutionSemantics() {
    var queryRegistry =
        new DefaultToolRegistry(List.of(scanner().scanRegistration(new QueryTools())));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            queryRegistry.resolve(
                List.of(
                    binding(
                        "fitness.query",
                        1,
                        true,
                        Optional.empty(),
                        Optional.of(5_001),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            queryRegistry.resolve(
                List.of(
                    binding(
                        "fitness.query",
                        1,
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(4),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            queryRegistry.resolve(
                List.of(
                    binding(
                        "fitness.query",
                        1,
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ResultMode.RETURN_DIRECT)))));

    var dangerousRegistry =
        new DefaultToolRegistry(List.of(scanner().scanRegistration(new DangerousTools())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            dangerousRegistry.resolve(
                List.of(
                    binding(
                        "fitness.delete",
                        1,
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ApprovalPolicy.NEVER),
                        Optional.empty(),
                        Optional.empty()))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            dangerousRegistry.resolve(
                List.of(
                    binding(
                        "fitness.delete",
                        1,
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ApprovalPolicy.ALWAYS),
                        Optional.of(RetryPolicy.SAFE_ONCE),
                        Optional.empty()))));
  }

  @Test
  void executableHandlerRejectsMissingScopesAndUnexpectedModelArguments() {
    var registration = scanner().scanRegistration(new QueryTools());

    assertThrows(
        SecurityException.class,
        () ->
            registration
                .handler()
                .invoke(
                    Map.of("query", "legs"),
                    new ToolExecutionContext("user-7", "run-1", Set.of(), "operation-1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registration
                .handler()
                .invoke(
                    Map.of("query", "legs", "userId", "attacker"),
                    new ToolExecutionContext(
                        "user-7", "run-1", Set.of("workout:read"), "operation-1")));
  }

  private static SpringToolCatalogScanner scanner() {
    return new SpringToolCatalogScanner("build-registry", List.of());
  }

  private static ToolBinding binding(
      String key,
      int version,
      boolean enabled,
      Optional<String> usage,
      Optional<Integer> timeout,
      Optional<Integer> maxCalls,
      Optional<ApprovalPolicy> approval,
      Optional<RetryPolicy> retry,
      Optional<ResultMode> resultMode) {
    return new ToolBinding(
        key(key), version(version), enabled, usage, timeout, maxCalls, approval, retry, resultMode);
  }

  private static ComponentKey key(String value) {
    return new ComponentKey(value);
  }

  private static ComponentVersion version(int value) {
    return new ComponentVersion(value);
  }

  static final class QueryTools {
    @AgentTool(
        key = "fitness.query",
        version = 1,
        runtimeName = "query_workouts",
        displayName = "查询训练",
        description = "读取当前用户训练信息",
        whenToUse = "用户询问训练时",
        whenNotToUse = "修改训练时",
        applicationKey = "fitness",
        group = "workout",
        sideEffect = ToolSideEffect.READ,
        idempotent = true,
        requiredScopes = "workout:read",
        defaultTimeoutMs = 1_000,
        maxTimeoutMs = 5_000,
        defaultMaxCallsPerRun = 3,
        outputDescription = "查询结果")
    String query(
        @AgentToolParam(name = "query", description = "查询词", example = "legs") String query,
        ToolExecutionContext context) {
      return query + ":" + context.userId();
    }
  }

  static final class DangerousTools {
    @AgentTool(
        key = "fitness.delete",
        version = 1,
        runtimeName = "delete_workout",
        displayName = "删除训练",
        description = "删除当前用户训练记录",
        whenToUse = "用户确认删除时",
        whenNotToUse = "只读查询时",
        applicationKey = "fitness",
        group = "workout",
        sideEffect = ToolSideEffect.WRITE,
        risk = ToolRiskLevel.HIGH,
        outputDescription = "删除结果")
    String delete() {
      return "deleted";
    }
  }
}
