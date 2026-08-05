package happy.jayden.yang.agentbuilder.framework.adapter.springai;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.runtime.RunResult;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Converts one neutral request to an actual Spring AI Alibaba ReactAgent invocation. */
final class SpringAiAlibabaRuntimeBridge {
  static final String TRUSTED_CONTEXT_KEY = "agentbuilder.trusted-tool-context";

  private final RunRequest request;
  private final ChatModel model;
  private final AtomicLong sequence;
  private final Sinks.Many<RunEvent> events = Sinks.many().unicast().onBackpressureBuffer();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean modelProgressEmitted = new AtomicBoolean();
  private final RunBudget budget;
  private volatile ReactAgent agent;
  private volatile RunnableConfig runnableConfig;
  private volatile Disposable subscription;

  SpringAiAlibabaRuntimeBridge(RunRequest request, AtomicLong sequence) {
    this(request, SpringAiAlibabaOpenAiModelFactory.create(request.model()), sequence);
  }

  SpringAiAlibabaRuntimeBridge(RunRequest request, ChatModel model, AtomicLong sequence) {
    this.request = Objects.requireNonNull(request, "request");
    this.model = Objects.requireNonNull(model, "model");
    this.sequence = Objects.requireNonNull(sequence, "sequence");
    this.budget = new RunBudget(request.resolvedConfig().runtimeLimits().maxToolCalls());
  }

  Flux<RunEvent> events() {
    return Flux.defer(
        () -> {
          start();
          return events.asFlux();
        });
  }

  private void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    var output = new StringBuilder();
    var hooks = hooks();
    agent =
        ReactAgent.builder()
            .name("run-" + request.runId())
            .model(model)
            .chatOptions(chatOptions())
            .tools(toolCallbacks())
            .toolContext(Map.of(TRUSTED_CONTEXT_KEY, request.toolExecutionContext()))
            .systemPrompt(systemPrompt())
            .hooks(hooks)
            .toolExecutionTimeout(
                Duration.ofSeconds(request.resolvedConfig().runtimeLimits().maxRunSeconds()))
            .build();
    runnableConfig = RunnableConfig.builder().threadId(request.runId()).build();
    try {
      subscription =
          agent
              .streamMessages(new UserMessage(request.input()), runnableConfig)
              .doOnNext(
                  message -> {
                    var text = text(message);
                    if (!text.isBlank()) {
                      output.append(text);
                      emit(RunEvent.Type.MODEL_DELTA, Map.of("text", text));
                    }
                  })
              .doOnComplete(
                  () ->
                      emit(
                          RunEvent.Type.RUN_COMPLETED,
                          Map.of("result", RunResult.completed(output.toString()))))
              .subscribe(ignored -> {}, this::fail, events::tryEmitComplete);
    } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException error) {
      fail(error);
    }
  }

  private List<ToolCallback> toolCallbacks() {
    return request.tools().stream()
        .map(tool -> new SpringAiAlibabaToolCallback(tool, request, budget, this::emit, this::fail))
        .map(ToolCallback.class::cast)
        .toList();
  }

  private OpenAiChatOptions chatOptions() {
    var parameters = request.resolvedConfig().modelParameters();
    return OpenAiChatOptions.builder()
        .model(request.model().modelName())
        .temperature(parameters.temperature().doubleValue())
        .topP(parameters.topP().doubleValue())
        .maxTokens(parameters.maxOutputTokens())
        .build();
  }

  private List<com.alibaba.cloud.ai.graph.agent.hook.Hook> hooks() {
    var result = new ArrayList<com.alibaba.cloud.ai.graph.agent.hook.Hook>();
    result.add(new NeutralAgentHook(HookDefinition.Phase.PRE_AGENT, this));
    result.add(new NeutralAgentHook(HookDefinition.Phase.POST_AGENT, this));
    result.add(new NeutralModelHook(HookDefinition.Phase.PRE_MODEL, this));
    result.add(new NeutralModelHook(HookDefinition.Phase.POST_MODEL, this));
    if (!request.skills().isEmpty()) {
      result.add(
          new BudgetedSkillsHook(
              SkillsAgentHook.builder().skillRegistry(new RequestSkillRegistry(request)).build(),
              this));
    }
    return List.copyOf(result);
  }

  private String systemPrompt() {
    var prompt = new StringBuilder("You are a helpful assistant.");
    for (var skill : request.skills()) {
      prompt.append("\n\nSkill ").append(skill.key()).append(":\n").append(skill.description());
      for (var resource : skill.alwaysIncludedResources()) {
        prompt.append("\n\n").append(skill.resources().get(resource));
      }
    }
    if (!request.memory().entries().isEmpty()) {
      prompt.append("\n\nUse the supplied conversation memory when relevant.");
      for (var entry : request.memory().entries()) {
        prompt.append("\n").append(entry);
      }
    }
    return prompt.toString();
  }

  void runHooks(HookDefinition.Phase phase) {
    for (var hook : request.hooks()) {
      if (hook.phase() != phase) {
        continue;
      }
      try {
        hook.action()
            .execute(
                new RunRequest.HookContext(
                    request.runId(), request.userId(), request.input(), phase));
      } catch (Exception error) {
        if (hook.failurePolicy() == HookDefinition.FailurePolicy.FAIL_CLOSED) {
          throw new SpringAiAlibabaAdapter.HookFailure(hook.key(), error);
        }
      }
    }
  }

  void emit(RunEvent.Type type, Map<String, Object> data) {
    events.emitNext(
        SpringAiAlibabaAdapter.event(sequence, type, data), Sinks.EmitFailureHandler.FAIL_FAST);
  }

  private void fail(Throwable error) {
    events.emitError(error, Sinks.EmitFailureHandler.FAIL_FAST);
  }

  void cancel() {
    if (!cancelled.compareAndSet(false, true)) {
      return;
    }
    var activeAgent = agent;
    var activeConfig = runnableConfig;
    if (activeAgent != null && activeConfig != null) {
      activeAgent.interrupt(activeConfig);
    }
    var activeSubscription = subscription;
    if (activeSubscription != null) {
      activeSubscription.dispose();
    }
  }

  private static String text(Message message) {
    return message instanceof org.springframework.ai.chat.messages.AbstractMessage value
        ? value.getText()
        : "";
  }

  private static final class NeutralAgentHook extends AgentHook {
    private final HookDefinition.Phase phase;
    private final SpringAiAlibabaRuntimeBridge bridge;

    private NeutralAgentHook(HookDefinition.Phase phase, SpringAiAlibabaRuntimeBridge bridge) {
      this.phase = phase;
      this.bridge = bridge;
    }

    @Override
    public String getName() {
      return "agentbuilder-" + phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
        com.alibaba.cloud.ai.graph.OverAllState state, RunnableConfig config) {
      if (phase == HookDefinition.Phase.PRE_AGENT) {
        bridge.runHooks(phase);
      }
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(
        com.alibaba.cloud.ai.graph.OverAllState state, RunnableConfig config) {
      if (phase == HookDefinition.Phase.POST_AGENT) {
        bridge.runHooks(phase);
      }
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public int getOrder() {
      return phase == HookDefinition.Phase.PRE_AGENT ? -100 : 100;
    }
  }

  private static final class NeutralModelHook extends ModelHook {
    private final HookDefinition.Phase phase;
    private final SpringAiAlibabaRuntimeBridge bridge;

    private NeutralModelHook(HookDefinition.Phase phase, SpringAiAlibabaRuntimeBridge bridge) {
      this.phase = phase;
      this.bridge = bridge;
    }

    @Override
    public String getName() {
      return "agentbuilder-" + phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(
        com.alibaba.cloud.ai.graph.OverAllState state, RunnableConfig config) {
      if (phase == HookDefinition.Phase.PRE_MODEL) {
        bridge.runHooks(phase);
      }
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(
        com.alibaba.cloud.ai.graph.OverAllState state, RunnableConfig config) {
      if (phase == HookDefinition.Phase.POST_MODEL) {
        bridge.runHooks(phase);
        if (bridge.modelProgressEmitted.compareAndSet(false, true)) {
          bridge.emit(RunEvent.Type.MODEL_DELTA, Map.of("text", ""));
        }
      }
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public int getOrder() {
      return phase == HookDefinition.Phase.PRE_MODEL ? -50 : 50;
    }
  }

  /** Keeps framework-native skill tools inside the same neutral lifecycle as published tools. */
  private static final class BudgetedSkillsHook extends AgentHook {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();
    private final SkillsAgentHook delegate;
    private final SpringAiAlibabaRuntimeBridge bridge;

    private BudgetedSkillsHook(SkillsAgentHook delegate, SpringAiAlibabaRuntimeBridge bridge) {
      this.delegate = delegate;
      this.bridge = bridge;
    }

    @Override
    public String getName() {
      return delegate.getName();
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
        com.alibaba.cloud.ai.graph.OverAllState state, RunnableConfig config) {
      return delegate.beforeAgent(state, config);
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(
        com.alibaba.cloud.ai.graph.OverAllState state, RunnableConfig config) {
      return delegate.afterAgent(state, config);
    }

    @Override
    public List<com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor>
        getModelInterceptors() {
      return delegate.getModelInterceptors();
    }

    @Override
    public List<ToolCallback> getTools() {
      return delegate.getTools().stream().map(this::wrap).toList();
    }

    @Override
    public void setAgentName(String agentName) {
      super.setAgentName(agentName);
      delegate.setAgentName(agentName);
    }

    @Override
    public void setAgent(ReactAgent agent) {
      super.setAgent(agent);
      delegate.setAgent(agent);
    }

    @Override
    public int getOrder() {
      return delegate.getOrder();
    }

    private ToolCallback wrap(ToolCallback callback) {
      return new ToolCallback() {
        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
          return callback.getToolDefinition();
        }

        @Override
        public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
          return callback.getToolMetadata();
        }

        @Override
        public String call(String input) {
          return call(input, new org.springframework.ai.chat.model.ToolContext(Map.of()));
        }

        @Override
        @SuppressWarnings("unchecked")
        public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
          var name = callback.getToolDefinition().name();
          try {
            var value = JSON.readValue(input, Map.class);
            bridge.budget.reserve(name, bridge.budget.maximum());
            bridge.emit(RunEvent.Type.TOOL_STARTED, Map.of("toolName", name, "arguments", value));
            bridge.runHooks(HookDefinition.Phase.PRE_TOOL);
            var result = callback.call(input, context);
            bridge.runHooks(HookDefinition.Phase.POST_TOOL);
            bridge.emit(RunEvent.Type.TOOL_RESULT, Map.of("toolName", name, "result", result));
            return result;
          } catch (SpringAiAlibabaAdapter.HookFailure | SpringAiAlibabaAdapter.ToolFailure error) {
            bridge.fail(error);
            throw error;
          } catch (Exception error) {
            var failure = new SpringAiAlibabaAdapter.ToolFailure(name, error);
            bridge.fail(failure);
            throw failure;
          }
        }
      };
    }
  }

  private static final class RequestSkillRegistry implements SkillRegistry {
    private final Map<String, SkillMetadata> skills;

    private RequestSkillRegistry(RunRequest request) {
      var values = new LinkedHashMap<String, SkillMetadata>();
      for (var skill : request.skills()) {
        var metadata = new InlineSkillMetadata(skill.markdown());
        metadata.setName(skill.key());
        metadata.setDescription(skill.description());
        metadata.setSource("published-agent-skill");
        metadata.setSkillPath(skill.key());
        values.put(skill.key(), metadata);
      }
      skills = Map.copyOf(values);
    }

    @Override
    public Optional<SkillMetadata> get(String name) {
      return Optional.ofNullable(skills.get(name));
    }

    @Override
    public List<SkillMetadata> listAll() {
      return skills.values().stream().sorted(Comparator.comparing(SkillMetadata::getName)).toList();
    }

    @Override
    public boolean contains(String name) {
      return skills.containsKey(name);
    }

    @Override
    public int size() {
      return skills.size();
    }

    @Override
    public void reload() {}

    @Override
    public String readSkillContent(String name) throws IOException {
      var skill = skills.get(name);
      if (skill == null) {
        throw new IOException("unknown skill " + name);
      }
      return skill.getFullContent();
    }

    @Override
    public String getSkillLoadInstructions() {
      return "Use the read_skill tool to load a skill before using its tools.";
    }

    @Override
    public String getRegistryType() {
      return "published-agent-skill";
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
      return new SystemPromptTemplate(getSkillLoadInstructions());
    }
  }

  private static final class InlineSkillMetadata extends SkillMetadata {
    private final String content;

    private InlineSkillMetadata(String content) {
      this.content = content;
    }

    @Override
    public String getFullContent() {
      return content;
    }

    @Override
    public String loadFullContent() {
      return content;
    }
  }

  static final class RunBudget {
    private final int maximum;
    private final AtomicInteger globalCalls = new AtomicInteger();
    private final Map<String, AtomicInteger> toolCalls =
        new java.util.concurrent.ConcurrentHashMap<>();

    RunBudget(int maximum) {
      this.maximum = maximum;
    }

    int maximum() {
      return maximum;
    }

    void reserve(String name, int maximumForTool) {
      if (globalCalls.incrementAndGet() > maximum) {
        throw new SpringAiAlibabaAdapter.ToolFailure(
            name, new IllegalStateException("global tool call limit exceeded"));
      }
      if (toolCalls.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet()
          > maximumForTool) {
        throw new SpringAiAlibabaAdapter.ToolFailure(
            name, new IllegalStateException("per-tool call limit exceeded"));
      }
    }
  }
}
