package happy.jayden.yang.agentbuilder.framework.adapter.springai;

import happy.jayden.yang.agentbuilder.core.component.ApprovalPolicy;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.ResultMode;
import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.defaults.EffectiveValueSource;
import happy.jayden.yang.agentbuilder.core.defaults.ModelParameters;
import happy.jayden.yang.agentbuilder.core.defaults.PublishedResolvedConfigSources;
import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig;
import happy.jayden.yang.agentbuilder.core.defaults.RetryPolicy;
import happy.jayden.yang.agentbuilder.core.defaults.RuntimeLimits;
import happy.jayden.yang.agentbuilder.core.runtime.FrameworkAdapterContract;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolHandler;
import happy.jayden.yang.agentbuilder.core.tool.ResolvedTool;
import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolLifecycleStatus;
import happy.jayden.yang.agentbuilder.core.tool.ToolRiskLevel;
import happy.jayden.yang.agentbuilder.core.tool.ToolSchema;
import happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect;
import happy.jayden.yang.agentbuilder.core.tool.ToolSourceType;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class SpringAiAlibabaAdapterContractTest extends FrameworkAdapterContract {
  private static final String CHECKSUM = "a".repeat(64);

  @Override
  protected ConformanceEvidence conformanceEvidence() {
    var hooks = new ArrayList<String>();
    var model = new ToolCallingChatModel();
    var observedContext = new AtomicReference<ToolExecutionContext>();
    var request =
        request(
            hooks,
            (arguments, context) -> {
              observedContext.set(context);
              return Map.of("workouts", 1);
            },
            false);
    var events = adapter(model).run(request).collectList().block();
    var skillEvents =
        adapter(new SkillThenToolChatModel())
            .run(request(new ArrayList<>(), (arguments, context) -> Map.of("workouts", 1), false))
            .collectList()
            .block();
    var invalid =
        terminal(
            adapter(new ToolCallingChatModel())
                .run(
                    request(
                        new ArrayList<>(),
                        (arguments, context) -> Map.of("workouts", "bad"),
                        false))
                .collectList()
                .block());
    var mandatory =
        terminal(
            adapter(new ToolCallingChatModel())
                .run(
                    request(new ArrayList<>(), (arguments, context) -> Map.of("workouts", 1), true))
                .collectList()
                .block());
    var cancelled = new AtomicBoolean();
    adapter(new NeverEndingChatModel(cancelled))
        .run(request(new ArrayList<>(), (arguments, context) -> Map.of("workouts", 1), false))
        .take(java.time.Duration.ofMillis(100))
        .blockLast();
    return new ConformanceEvidence(
        adapter(model).capabilities(),
        model.strictSchema,
        model.systemPrompt,
        "read_skill",
        skillEvents,
        hooks,
        events,
        model.modelArguments,
        observedContext.get(),
        Map.of(
            happy.jayden.yang.agentbuilder.core.runtime.RunFailureCode.TOOL, invalid,
            happy.jayden.yang.agentbuilder.core.runtime.RunFailureCode.HOOK, mandatory),
        cancelled.get() ? 1 : 0);
  }

  @Test
  void emitsAFrameworkNeutralConformanceReport() {
    org.junit.jupiter.api.Assertions.assertEquals(
        "spring-ai-alibaba", new SpringAiAlibabaAdapter().key());
    org.junit.jupiter.api.Assertions.assertTrue((Integer) report().get("skillEvents") > 0);
  }

  @Test
  void appliesResolvedModelOptionsWithoutInventingAnOutputSchema() {
    var model = new CapturingChatModel();

    var events =
        adapter(model)
            .run(request(new ArrayList<>(), (arguments, context) -> Map.of("workouts", 1), false))
            .collectList()
            .block();

    org.junit.jupiter.api.Assertions.assertEquals(
        RunEvent.Type.RUN_COMPLETED, terminal(events).type(), terminal(events).data().toString());
    var options = model.options;
    org.junit.jupiter.api.Assertions.assertEquals("qwen-plus", options.getModel());
    org.junit.jupiter.api.Assertions.assertEquals(0.1d, options.getTemperature());
    org.junit.jupiter.api.Assertions.assertEquals(0.9d, options.getTopP());
    org.junit.jupiter.api.Assertions.assertEquals(1_000, options.getMaxTokens());
    org.junit.jupiter.api.Assertions.assertNull(
        ((org.springframework.ai.openai.OpenAiChatOptions) options).getResponseFormat());
  }

  @Test
  void safeRetryConsumesOneLogicalBudgetAndEmitsOneStart() {
    var attempts = new AtomicInteger();
    var request =
        request(
            new ArrayList<>(),
            (arguments, context) -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
              }
              return Map.of("workouts", 1);
            },
            false,
            RetryPolicy.SAFE_ONCE,
            1,
            1);

    var events = adapter(new ToolCallingChatModel()).run(request).collectList().block();

    org.junit.jupiter.api.Assertions.assertEquals(2, attempts.get());
    org.junit.jupiter.api.Assertions.assertEquals(
        1, events.stream().filter(event -> event.type() == RunEvent.Type.TOOL_STARTED).count());
    org.junit.jupiter.api.Assertions.assertEquals(
        RunEvent.Type.RUN_COMPLETED, terminal(events).type());
  }

  @Test
  void toolHookFailuresRemainHookFailures() {
    for (var phase : List.of(HookDefinition.Phase.PRE_TOOL, HookDefinition.Phase.POST_TOOL)) {
      var hooks = new ArrayList<>(hooks(new ArrayList<>(), false));
      hooks.add(
          new RunRequest.Hook(
              "reject-tool",
              phase,
              0,
              true,
              HookDefinition.FailurePolicy.FAIL_CLOSED,
              ignored -> {
                throw new IllegalStateException("rejected");
              }));

      var events =
          adapter(new ToolCallingChatModel())
              .run(
                  request(
                      hooks, (arguments, context) -> Map.of("workouts", 1), RetryPolicy.NONE, 2, 2))
              .collectList()
              .block();

      org.junit.jupiter.api.Assertions.assertEquals(
          happy.jayden.yang.agentbuilder.core.runtime.RunFailureCode.HOOK,
          ((happy.jayden.yang.agentbuilder.core.runtime.RunResult)
                  terminal(events).data().get("result"))
              .failure()
              .orElseThrow()
              .code(),
          phase.name());
    }
  }

  @Test
  void rejectsTrustedContextSpoofingBeforeTheHandler() {
    var calls = new AtomicInteger();
    var events =
        adapter(new SpoofingChatModel())
            .run(
                request(
                    new ArrayList<>(),
                    (arguments, context) -> {
                      calls.incrementAndGet();
                      return Map.of("workouts", 1);
                    },
                    false))
            .collectList()
            .block();

    org.junit.jupiter.api.Assertions.assertEquals(0, calls.get());
    org.junit.jupiter.api.Assertions.assertEquals(
        RunEvent.Type.RUN_FAILED, terminal(events).type());
  }

  @Test
  void nativeReadSkillUsesNeutralHooksEventsAndSharedBudget() {
    var execution = new ArrayList<String>();
    var handlerCalls = new AtomicInteger();
    var request =
        request(
            execution,
            (arguments, context) -> {
              handlerCalls.incrementAndGet();
              return Map.of("workouts", 1);
            },
            false,
            RetryPolicy.NONE,
            1,
            2);

    var events = adapter(new SkillThenToolChatModel()).run(request).collectList().block();

    org.junit.jupiter.api.Assertions.assertEquals(0, handlerCalls.get());
    org.junit.jupiter.api.Assertions.assertEquals(
        List.of("read_skill", "read_skill"),
        events.stream()
            .filter(
                event ->
                    event.type() == RunEvent.Type.TOOL_STARTED
                        || event.type() == RunEvent.Type.TOOL_RESULT)
            .map(event -> String.valueOf(event.data().get("toolName")))
            .toList());
    org.junit.jupiter.api.Assertions.assertEquals(
        1, java.util.Collections.frequency(execution, "pre-tool"));
    org.junit.jupiter.api.Assertions.assertEquals(
        1, java.util.Collections.frequency(execution, "post-tool"));
    org.junit.jupiter.api.Assertions.assertEquals(
        RunEvent.Type.RUN_FAILED, terminal(events).type());
  }

  @Test
  void validatesFullJsonSchemaAndEncodesJavaTimeRecords() {
    var output = new WorkoutSummary(List.of("run"), LocalDate.of(2026, 8, 5));
    var tool = complexTool((arguments, context) -> output);
    var request = request(List.of(), tool, hooks(new ArrayList<>(), false), RetryPolicy.NONE, 2);
    var callback =
        new SpringAiAlibabaToolCallback(
            tool,
            request,
            new SpringAiAlibabaRuntimeBridge.RunBudget(2),
            (type, data) -> {},
            ignored -> {});

    var encoded =
        callback.call(
            "{\"tags\":[\"run\"],\"level\":\"hard\",\"score\":2}",
            new org.springframework.ai.chat.model.ToolContext(
                Map.of(
                    SpringAiAlibabaRuntimeBridge.TRUSTED_CONTEXT_KEY,
                    request.toolExecutionContext())));

    org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("2026-08-05"));
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            callback.call(
                "{\"tags\":[],\"level\":\"invalid\",\"score\":11}",
                new org.springframework.ai.chat.model.ToolContext(
                    Map.of(
                        SpringAiAlibabaRuntimeBridge.TRUSTED_CONTEXT_KEY,
                        request.toolExecutionContext()))));
  }

  private static RunEvent terminal(List<RunEvent> events) {
    return events.get(events.size() - 1);
  }

  private static SpringAiAlibabaAdapter adapter(ChatModel model) {
    return new SpringAiAlibabaAdapter(
        (request, sequence) -> new SpringAiAlibabaRuntimeBridge(request, model, sequence));
  }

  private static Map<String, Object> schema(org.springframework.ai.tool.ToolCallback callback) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(callback.getToolDefinition().inputSchema(), Map.class);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static RunRequest request(
      List<String> execution, AgentToolHandler handler, boolean mandatoryFailure) {
    return request(execution, handler, mandatoryFailure, RetryPolicy.NONE, 2, 2);
  }

  private static RunRequest request(
      List<String> execution,
      AgentToolHandler handler,
      boolean mandatoryFailure,
      RetryPolicy retryPolicy,
      int maximumGlobalCalls,
      int maximumToolCalls) {
    return request(
        hooks(execution, mandatoryFailure),
        handler,
        retryPolicy,
        maximumGlobalCalls,
        maximumToolCalls);
  }

  private static RunRequest request(
      List<RunRequest.Hook> hooks,
      AgentToolHandler handler,
      RetryPolicy retryPolicy,
      int maximumGlobalCalls,
      int maximumToolCalls) {
    return request(
        List.of(
            new RunRequest.Skill(
                "fitness",
                "Use the lookup tool.",
                "Full skill procedure",
                Map.of("always.md", "always content", "detail.md", "on demand content"),
                Set.of("always.md"),
                Set.of("detail.md"))),
        tool(handler, retryPolicy, maximumToolCalls),
        hooks,
        retryPolicy,
        maximumGlobalCalls);
  }

  private static RunRequest request(
      List<RunRequest.Skill> skills,
      ResolvedTool tool,
      List<RunRequest.Hook> hooks,
      RetryPolicy retryPolicy,
      int maximumGlobalCalls) {
    var context =
        new ToolExecutionContext("user-1", "run-1", Set.of("fitness:read"), "operation-1");
    return new RunRequest(
        "run-1",
        "user-1",
        "help me",
        config(maximumGlobalCalls),
        new RunRequest.ModelEndpoint(
            URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            "qwen-plus",
            new RunRequest.ModelCredential("secret".toCharArray())),
        List.of(tool),
        skills,
        hooks,
        new RunRequest.Memory(List.of("Earlier goal"), 1_000),
        context);
  }

  private static List<RunRequest.Hook> hooks(List<String> execution, boolean mandatoryFailure) {
    var values = new ArrayList<RunRequest.Hook>();
    for (var phase : HookDefinition.Phase.values()) {
      var key = phase.name().toLowerCase().replace('_', '-');
      values.add(
          new RunRequest.Hook(
              key,
              phase,
              1,
              true,
              HookDefinition.FailurePolicy.FAIL_CLOSED,
              ignored -> {
                execution.add(key);
                if (mandatoryFailure && phase == HookDefinition.Phase.PRE_AGENT) {
                  throw new IllegalStateException("mandatory");
                }
              }));
    }
    return values;
  }

  private static ResolvedTool tool(AgentToolHandler handler) {
    return tool(handler, RetryPolicy.NONE, 2);
  }

  private static ResolvedTool tool(
      AgentToolHandler handler, RetryPolicy retryPolicy, int maximumToolCalls) {
    return new ResolvedTool(
        new ToolDescriptor(
            "fitness.lookup",
            1,
            "lookup",
            "Lookup",
            "Looks up workouts",
            "When asked for workouts",
            "Never for writes",
            "fitness",
            "read",
            List.of(),
            new ToolSchema(
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                        "query", Map.of("type", "string", "description", "Workout search query")),
                    "required",
                    List.of("query"),
                    "additionalProperties",
                    false)),
            new ToolSchema(
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("workouts", Map.of("type", "integer", "description", "Count")),
                    "required",
                    List.of("workouts"),
                    "additionalProperties",
                    false)),
            true,
            ToolSideEffect.READ,
            true,
            ToolRiskLevel.LOW,
            List.of("fitness:read"),
            1_000,
            2_000,
            maximumToolCalls,
            true,
            false,
            ToolSourceType.LOCAL_BEAN,
            CHECKSUM,
            ToolLifecycleStatus.AVAILABLE,
            Optional.empty(),
            "test"),
        new ToolBinding(new ComponentKey("fitness.lookup"), new ComponentVersion(1), true),
        handler,
        "",
        1_000,
        maximumToolCalls,
        ApprovalPolicy.NEVER,
        retryPolicy,
        ResultMode.MODEL_CONTEXT);
  }

  private static ResolvedTool complexTool(AgentToolHandler handler) {
    return new ResolvedTool(
        new ToolDescriptor(
            "fitness.complex",
            1,
            "complex",
            "Complex",
            "Validates complex data",
            "When validating complex workout data",
            "Never for writes",
            "fitness",
            "read",
            List.of(),
            new ToolSchema(
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                        "tags",
                            Map.of(
                                "type",
                                "array",
                                "description",
                                "Workout tags",
                                "minItems",
                                1,
                                "maxItems",
                                2,
                                "items",
                                Map.of(
                                    "type", "string",
                                    "description", "Workout tag",
                                    "minLength", 3)),
                        "level",
                            Map.of(
                                "type", "string",
                                "description", "Difficulty",
                                "enum", List.of("easy", "hard")),
                        "score",
                            Map.of(
                                "type",
                                "number",
                                "description",
                                "Score",
                                "minimum",
                                1,
                                "maximum",
                                10)),
                    "required",
                    List.of("tags", "level", "score"),
                    "additionalProperties",
                    false)),
            new ToolSchema(
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                        "tags",
                            Map.of(
                                "type", "array",
                                "description", "Workout tags",
                                "items", Map.of("type", "string", "description", "Workout tag")),
                        "date", Map.of("type", "string", "description", "Workout date")),
                    "required",
                    List.of("tags", "date"),
                    "additionalProperties",
                    false)),
            true,
            ToolSideEffect.READ,
            true,
            ToolRiskLevel.LOW,
            List.of("fitness:read"),
            1_000,
            2_000,
            2,
            true,
            false,
            ToolSourceType.LOCAL_BEAN,
            CHECKSUM,
            ToolLifecycleStatus.AVAILABLE,
            Optional.empty(),
            "test"),
        new ToolBinding(new ComponentKey("fitness.complex"), new ComponentVersion(1), true),
        handler,
        "",
        1_000,
        2,
        ApprovalPolicy.NEVER,
        RetryPolicy.NONE,
        ResultMode.MODEL_CONTEXT);
  }

  private static ResolvedAgentConfig config() {
    return config(2);
  }

  private static ResolvedAgentConfig config(int maximumGlobalCalls) {
    var source = EffectiveValueSource.platformLimit();
    var sources =
        new PublishedResolvedConfigSources(
            source, source, source, source, source, source, source, source, source, source, source,
            source, source);
    return new ResolvedAgentConfig(
        "fitness",
        new RuntimeLimits(30, maximumGlobalCalls, 2_000, 1_000, BigDecimal.ONE, 1),
        new ModelParameters(new BigDecimal("0.1"), new BigDecimal("0.9"), 1_000),
        RetryPolicy.NONE,
        sources);
  }

  private static final class ToolCallingChatModel implements ChatModel {
    private final AtomicInteger calls = new AtomicInteger();
    private String systemPrompt = "";
    private Map<String, Object> strictSchema = Map.of();
    private Map<String, Object> modelArguments = Map.of();

    @Override
    public ChatResponse call(Prompt prompt) {
      observe(prompt);
      if (calls.incrementAndGet() == 1) {
        return response(
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(
                        new AssistantMessage.ToolCall(
                            "call-1", "function", "lookup", "{\"query\":\"today\"}")))
                .build());
      }
      return response(new AssistantMessage("done"));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
    }

    private void observe(Prompt prompt) {
      systemPrompt = prompt.getSystemMessage() == null ? "" : prompt.getSystemMessage().getText();
      modelArguments = Map.of("query", "today");
      if (prompt.getOptions()
          instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
        strictSchema =
            options.getToolCallbacks().stream()
                .filter(callback -> callback.getToolDefinition().name().equals("lookup"))
                .findFirst()
                .map(SpringAiAlibabaAdapterContractTest::schema)
                .orElse(Map.of());
      }
    }
  }

  private static final class NeverEndingChatModel implements ChatModel {
    private final AtomicBoolean cancelled;

    private NeverEndingChatModel(AtomicBoolean cancelled) {
      this.cancelled = cancelled;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      throw new UnsupportedOperationException("streaming only");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
    }
  }

  private static final class CapturingChatModel implements ChatModel {
    private ChatOptions options;

    @Override
    public ChatResponse call(Prompt prompt) {
      options = prompt.getOptions();
      return response(new AssistantMessage("done"));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
    }
  }

  private static final class SkillThenToolChatModel implements ChatModel {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResponse call(Prompt prompt) {
      return switch (calls.incrementAndGet()) {
        case 1 ->
            response(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "skill-1",
                                "function",
                                "read_skill",
                                "{\"skillName\":\"fitness\"}")))
                    .build());
        case 2 ->
            response(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "tool-1", "function", "lookup", "{\"query\":\"today\"}")))
                    .build());
        default -> response(new AssistantMessage("done"));
      };
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
    }
  }

  private static final class SpoofingChatModel implements ChatModel {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResponse call(Prompt prompt) {
      if (calls.incrementAndGet() == 1) {
        return response(
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(
                        new AssistantMessage.ToolCall(
                            "spoof-1",
                            "function",
                            "lookup",
                            "{\"query\":\"today\",\"userId\":\"attacker\"}")))
                .build());
      }
      return response(new AssistantMessage("done"));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return org.springframework.ai.openai.OpenAiChatOptions.builder().build();
    }
  }

  private record WorkoutSummary(List<String> tags, LocalDate date) {}

  private static ChatResponse response(AssistantMessage message) {
    return new ChatResponse(List.of(new Generation(message)));
  }
}
