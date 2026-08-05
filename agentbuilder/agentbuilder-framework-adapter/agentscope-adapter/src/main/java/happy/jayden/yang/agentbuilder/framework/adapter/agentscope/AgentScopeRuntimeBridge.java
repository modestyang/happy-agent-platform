package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.runtime.RunResult;
import happy.jayden.yang.agentbuilder.core.tool.ResolvedTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** Package-private conversion boundary. No AgentScope type is exposed by the adapter API. */
final class AgentScopeRuntimeBridge implements AutoCloseable {
  private final RunRequest request;
  private final AgentScopeModelTransport model;
  private final Sinks.Many<AgentScopeAdapter.Signal> signals =
      Sinks.many().unicast().onBackpressureBuffer();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean interrupted = new AtomicBoolean();
  private final AtomicBoolean started = new AtomicBoolean();
  private final RunBudget budget;
  private ReActAgent agent;
  private Disposable subscription;

  AgentScopeRuntimeBridge(RunRequest request, AgentScopeModelTransport model) {
    this.request = Objects.requireNonNull(request, "request");
    this.model = Objects.requireNonNull(model, "model");
    this.budget = new RunBudget(request.resolvedConfig().runtimeLimits().maxToolCalls());
  }

  private void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    var toolkit = toolkit();
    var memory = memory();
    var skillBox = skills(toolkit);
    var hook = new DeterministicHook(request, budget);
    var generateOptions =
        GenerateOptions.builder()
            .temperature(request.resolvedConfig().modelParameters().temperature().doubleValue())
            .topP(request.resolvedConfig().modelParameters().topP().doubleValue())
            .maxTokens(request.resolvedConfig().modelParameters().maxOutputTokens())
            .stream(true)
            .build();
    var trustedContext =
        io.agentscope.core.tool.ToolExecutionContext.builder()
            .register(
                happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext.class,
                request.toolExecutionContext())
            .build();
    agent =
        ReActAgent.builder()
            .name("run-" + request.runId())
            .sysPrompt(systemPrompt())
            .model(strictSchemaModel())
            .generateOptions(generateOptions)
            .toolkit(toolkit)
            .skillBox(skillBox)
            .memory(memory)
            .hooks(List.of(hook))
            .toolExecutionContext(trustedContext)
            .maxIters(Math.max(1, request.resolvedConfig().runtimeLimits().maxToolCalls() + 1))
            .build();
    var input =
        Msg.builder()
            .name(request.userId())
            .role(MsgRole.USER)
            .textContent(request.input())
            .build();
    var options =
        StreamOptions.builder()
            .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
            .incremental(true)
            .includeReasoningChunk(true)
            .includeReasoningResult(false)
            .build();
    subscription =
        agent.stream(List.of(input), options).subscribe(this::accept, this::fail, this::complete);
  }

  Flux<AgentScopeAdapter.Signal> events() {
    return Flux.defer(
        () -> {
          start();
          return signals.asFlux();
        });
  }

  private Toolkit toolkit() {
    var toolkit = new Toolkit();
    for (var tool : request.tools()) {
      toolkit.registerAgentTool(new StrictAgentTool(tool, signals, budget));
    }
    return toolkit;
  }

  private Model strictSchemaModel() {
    var strictByName = new LinkedHashMap<String, Boolean>();
    for (var tool : request.tools()) {
      strictByName.put(tool.descriptor().runtimeName(), tool.descriptor().strictInput());
    }
    return new Model() {
      @Override
      public Flux<io.agentscope.core.model.ChatResponse> stream(
          List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        var converted =
            tools.stream()
                .map(
                    schema ->
                        ToolSchema.builder()
                            .name(schema.getName())
                            .description(schema.getDescription())
                            .parameters(schema.getParameters())
                            .outputSchema(schema.getOutputSchema())
                            .strict(strictByName.getOrDefault(schema.getName(), false))
                            .build())
                .toList();
        return model.stream(messages, converted, options)
            .map(AgentScopeRuntimeBridge::rejectDuplicateToolCallIds);
      }

      @Override
      public String getModelName() {
        return model.getModelName();
      }
    };
  }

  private static io.agentscope.core.model.ChatResponse rejectDuplicateToolCallIds(
      io.agentscope.core.model.ChatResponse response) {
    var ids = new java.util.HashSet<String>();
    for (var block : response.getContent()) {
      if (block instanceof ToolUseBlock toolUse && !ids.add(toolUse.getId())) {
        throw new ToolFailure(
            toolUse.getName(), new IllegalArgumentException("duplicate tool call id"));
      }
    }
    return response;
  }

  private InMemoryMemory memory() {
    var memory = new InMemoryMemory();
    for (var entry : request.memory().entries()) {
      memory.addMessage(Msg.builder().name("memory").role(MsgRole.USER).textContent(entry).build());
    }
    return memory;
  }

  private SkillBox skills(Toolkit toolkit) {
    var skillBox = new SkillBox(toolkit);
    skillBox.setExposeAllSkillMetadata(true);
    for (var skill : request.skills()) {
      skillBox.registerSkill(
          AgentSkill.builder()
              .name(skill.key())
              .description(skill.description())
              .skillContent(skill.markdown())
              .resources(skill.resources())
              .source("published-agent-skill")
              .build());
    }
    if (!request.skills().isEmpty()) {
      skillBox.registerSkillLoadTool();
    }
    return skillBox;
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
    }
    return prompt.toString();
  }

  private void accept(Event event) {
    if (event.getType() == EventType.REASONING) {
      var text = event.getMessage() == null ? "" : event.getMessage().getTextContent();
      if (text != null && !text.isBlank()) {
        emit(RunEvent.Type.MODEL_DELTA, Map.of("text", text));
      }
      return;
    }
    if (event.getType() == EventType.AGENT_RESULT) {
      var text = event.getMessage() == null ? "" : event.getMessage().getTextContent();
      emit(RunEvent.Type.RUN_COMPLETED, Map.of("result", RunResult.completed(text)));
    }
  }

  private void emit(RunEvent.Type type, Map<String, Object> data) {
    synchronized (signals) {
      signals.emitNext(
          new AgentScopeAdapter.Signal(type, data), Sinks.EmitFailureHandler.FAIL_FAST);
    }
  }

  private void fail(Throwable error) {
    synchronized (signals) {
      signals.emitError(error, Sinks.EmitFailureHandler.FAIL_FAST);
    }
  }

  private void complete() {
    synchronized (signals) {
      signals.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    interrupt();
    model.close();
  }

  void interrupt() {
    if (!interrupted.compareAndSet(false, true)) {
      return;
    }
    if (agent != null) {
      agent.interrupt();
    }
    if (subscription != null) {
      subscription.dispose();
    }
    model.interrupt();
  }

  static final class ToolFailure extends RuntimeException {
    ToolFailure(String toolName, Throwable cause) {
      super("Tool " + toolName + " failed", cause);
    }
  }

  static final class HookFailure extends RuntimeException {
    HookFailure(String hookKey, Throwable cause) {
      super("Hook " + hookKey + " failed", cause);
    }
  }

  private static final class StrictAgentTool implements AgentTool {
    private static final Set<String> TRUSTED_ARGUMENT_NAMES =
        Set.of("userId", "runId", "permissions", "operationId");
    private final ResolvedTool tool;
    private final Sinks.Many<AgentScopeAdapter.Signal> signals;
    private final RunBudget budget;

    private StrictAgentTool(
        ResolvedTool tool, Sinks.Many<AgentScopeAdapter.Signal> signals, RunBudget budget) {
      this.tool = tool;
      this.signals = signals;
      this.budget = budget;
    }

    @Override
    public String getName() {
      return tool.descriptor().runtimeName();
    }

    @Override
    public String getDescription() {
      return tool.usageGuidance().isBlank()
          ? tool.descriptor().description()
          : tool.descriptor().description() + "\n" + tool.usageGuidance();
    }

    @Override
    public Map<String, Object> getParameters() {
      return tool.descriptor().inputSchema().document();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
      return tool.descriptor().outputSchema().document();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
      var arguments = Map.copyOf(new LinkedHashMap<>(param.getInput()));
      return Mono.defer(
              () -> {
                budget.reserveTool(getName(), tool.maxCallsPerRun());
                emit(
                    RunEvent.Type.TOOL_STARTED,
                    Map.of("toolName", getName(), "arguments", arguments));
                return invoke(param, arguments);
              })
          .onErrorMap(
              error -> error instanceof ToolFailure ? error : new ToolFailure(getName(), error))
          .doOnError(this::fail);
    }

    private Mono<ToolResultBlock> invoke(ToolCallParam param, Map<String, Object> arguments) {
      var invocation =
          Mono.fromCallable(
                  () -> {
                    if (!Collections.disjoint(arguments.keySet(), TRUSTED_ARGUMENT_NAMES)) {
                      throw new IllegalArgumentException(
                          "trusted execution context fields are not accepted as model arguments");
                    }
                    ToolOutputCodec.validateInput(
                        arguments, tool.descriptor().inputSchema().document());
                    var trusted =
                        param
                            .getContext()
                            .get(
                                happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext
                                    .class);
                    if (trusted == null) {
                      throw new IllegalStateException("trusted tool context is missing");
                    }
                    var result = tool.handler().invoke(arguments, trusted);
                    var json =
                        ToolOutputCodec.encode(result, tool.descriptor().outputSchema().document());
                    emit(
                        RunEvent.Type.TOOL_RESULT, Map.of("toolName", getName(), "result", result));
                    return ToolResultBlock.text(json)
                        .withIdAndName(param.getToolUseBlock().getId(), getName());
                  })
              .subscribeOn(Schedulers.boundedElastic())
              .timeout(Duration.ofMillis(tool.timeoutMs()));
      var retries = safeRetryCount();
      return retries == 0
          ? invocation
          : invocation.retryWhen(
              Retry.max(retries)
                  .filter(error -> !(error instanceof ToolOutputCodec.InvalidToolOutputException)));
    }

    private long safeRetryCount() {
      var safe =
          tool.descriptor().idempotent()
              || tool.descriptor().sideEffect()
                  == happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect.NONE
              || tool.descriptor().sideEffect()
                  == happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect.READ;
      if (!safe) {
        return 0;
      }
      return switch (tool.retryPolicy()) {
        case NONE -> 0;
        case SAFE_ONCE -> 1;
        case SAFE_TWICE -> 2;
      };
    }

    private void emit(RunEvent.Type type, Map<String, Object> data) {
      synchronized (signals) {
        signals.emitNext(
            new AgentScopeAdapter.Signal(type, data), Sinks.EmitFailureHandler.FAIL_FAST);
      }
    }

    private void fail(Throwable error) {
      synchronized (signals) {
        signals.emitError(error, Sinks.EmitFailureHandler.FAIL_FAST);
      }
    }
  }

  private static final class DeterministicHook implements Hook {
    private final RunRequest request;
    private final RunBudget budget;

    private DeterministicHook(RunRequest request, RunBudget budget) {
      this.request = request;
      this.budget = budget;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
      var phase = phase(event);
      if (phase == null) {
        return Mono.just(event);
      }
      return Mono.fromCallable(
          () -> {
            if (event instanceof PreActingEvent preActingEvent) {
              budget.reserveGlobal(preActingEvent.getToolUse());
            }
            for (var hook : request.hooks()) {
              if (hook.phase() == phase) {
                try {
                  hook.action()
                      .execute(
                          new RunRequest.HookContext(
                              request.runId(), request.userId(), request.input(), phase));
                } catch (Exception error) {
                  if (hook.failurePolicy() == HookDefinition.FailurePolicy.FAIL_CLOSED) {
                    throw new HookFailure(hook.key(), error);
                  }
                }
              }
            }
            return event;
          });
    }

    private HookDefinition.Phase phase(HookEvent event) {
      if (event.getType() == HookEventType.PRE_CALL) {
        return HookDefinition.Phase.PRE_AGENT;
      }
      if (event.getType() == HookEventType.PRE_REASONING) {
        return HookDefinition.Phase.PRE_MODEL;
      }
      if (event.getType() == HookEventType.PRE_ACTING) {
        return HookDefinition.Phase.PRE_TOOL;
      }
      if (event.getType() == HookEventType.POST_ACTING) {
        return HookDefinition.Phase.POST_TOOL;
      }
      if (event.getType() == HookEventType.POST_REASONING) {
        return HookDefinition.Phase.POST_MODEL;
      }
      if (event.getType() == HookEventType.POST_CALL) {
        return HookDefinition.Phase.POST_AGENT;
      }
      return null;
    }
  }

  private static final class RunBudget {
    private final int maxToolCalls;
    private final AtomicInteger globalCalls = new AtomicInteger();
    private final Map<String, AtomicInteger> toolCalls = new ConcurrentHashMap<>();

    private RunBudget(int maxToolCalls) {
      this.maxToolCalls = maxToolCalls;
    }

    private void reserveGlobal(ToolUseBlock toolUse) {
      var used = globalCalls.incrementAndGet();
      if (used > maxToolCalls) {
        throw new ToolFailure(
            toolUse.getName(), new IllegalStateException("global tool call limit exceeded"));
      }
    }

    private void reserveTool(String toolName, int maxCalls) {
      var used =
          toolCalls.computeIfAbsent(toolName, ignored -> new AtomicInteger()).incrementAndGet();
      if (used > maxCalls) {
        throw new ToolFailure(toolName, new IllegalStateException("per-tool call limit exceeded"));
      }
    }
  }
}
