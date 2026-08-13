package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.runtime.RunResult;
import happy.jayden.yang.agentbuilder.core.tool.ResolvedTool;
import happy.jayden.yang.agentbuilder.core.tool.ToolErrorResponse;
import happy.jayden.yang.agentbuilder.core.tool.ToolInputException;
import happy.jayden.yang.agentbuilder.core.tool.ToolSchemaCodec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
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
  private HarnessAgent agent;
  private Disposable subscription;
  private volatile String completedText = "";

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
    emit(
        RunEvent.Type.CONTEXT_ASSEMBLED,
        Map.of("runId", request.runId(), "memoryEntries", request.memory().entries().size()));
    if (!request.memory().entries().isEmpty()) {
      emit(
          RunEvent.Type.MEMORY_LOADED,
          Map.of("runId", request.runId(), "entryCount", request.memory().entries().size()));
    }
    for (var skill : request.skills()) {
      emit(
          RunEvent.Type.SKILL_DISCOVERED,
          Map.of(
              "runId",
              request.runId(),
              "skillKey",
              skill.key(),
              "description",
              skill.description()));
      emit(RunEvent.Type.SKILL_LOADED, Map.of("runId", request.runId(), "skillKey", skill.key()));
    }
    var hook = new DeterministicHook(request, budget, this::emit);
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
        HarnessAgent.builder()
            .name("run-" + request.runId())
            .sysPrompt(systemPrompt())
            .model(strictSchemaModel())
            .generateOptions(generateOptions)
            .toolkit(toolkit)
            .skillRepository(new PublishedSkillRepository(request.skills()))
            .enableSkills(
                request.skills().stream().map(RunRequest.Skill::key).toArray(String[]::new))
            .hooks(List.of(hook))
            .toolExecutionContext(trustedContext)
            .permissionContext(permissionContext())
            .stateStore(new InMemoryAgentStateStore())
            .workspace(
                Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "happy-agent-platform",
                    "agentscope",
                    request.runId()))
            .maxIters(Math.max(1, request.resolvedConfig().runtimeLimits().maxToolCalls() + 1))
            .disableFilesystemTools()
            .disableShellTool()
            .disableSubagents()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableWorkspaceContext()
            .disableAtPathExpansion()
            .disableToolResultEviction()
            .disableToolsConfig()
            .disableDefaultWorkspaceSkills()
            .build();
    // Async tools and subagents are disabled above, so this Harness helper cannot be used here.
    agent.getToolkit().removeTool("wait_async_results");
    var input =
        Msg.builder()
            .name(request.userId())
            .role(MsgRole.USER)
            .textContent(request.input())
            .build();
    var runtimeContext =
        RuntimeContext.builder()
            .userId(request.userId())
            .sessionId(request.runId())
            .toolExecutionContext(trustedContext)
            .build();
    subscription =
        agent
            .streamEvents(List.of(input), runtimeContext)
            .subscribe(this::accept, this::fail, this::complete);
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

  private String systemPrompt() {
    var prompt =
        new StringBuilder(
            request.systemPrompt().isBlank()
                ? "You are a helpful assistant."
                : request.systemPrompt());
    if (!request.skills().isEmpty()) {
      prompt.append(
          "\n\nWhen the user's request matches a supplied Skill, load that Skill before answering "
              + "and treat its instructions as binding for this turn.");
    }
    for (var skill : request.skills()) {
      prompt.append("\n\nSkill ").append(skill.key()).append(":\n").append(skill.description());
      for (var resource : skill.alwaysIncludedResources()) {
        prompt.append("\n\n").append(skill.resources().get(resource));
      }
    }
    if (!request.memory().entries().isEmpty()) {
      prompt.append("\n\n<conversation_memory>");
      for (var entry : request.memory().entries()) {
        prompt.append("\n").append(entry);
      }
      prompt.append("\n</conversation_memory>");
    }
    return prompt.toString();
  }

  private PermissionContextState permissionContext() {
    var permissions = PermissionContextState.builder().mode(PermissionMode.DEFAULT);
    for (var tool : request.tools()) {
      var behavior =
          switch (tool.approvalPolicy()) {
            case ALWAYS -> PermissionBehavior.ASK;
            case RISK_BASED ->
                switch (tool.descriptor().riskLevel()) {
                  case HIGH, CRITICAL -> PermissionBehavior.ASK;
                  case LOW, MEDIUM -> PermissionBehavior.ALLOW;
                };
            case NEVER -> PermissionBehavior.ALLOW;
          };
      var rule =
          new PermissionRule(
              tool.descriptor().runtimeName(), "", behavior, "published-tool-approval-policy");
      if (behavior == PermissionBehavior.ASK) {
        permissions.addAskRule(tool.descriptor().runtimeName(), rule);
      } else {
        permissions.addAllowRule(tool.descriptor().runtimeName(), rule);
      }
    }
    return permissions.build();
  }

  private void accept(AgentEvent event) {
    if (event instanceof AgentResultEvent result) {
      completedText = result.getResult() == null ? "" : result.getResult().getTextContent();
    }
    if (event instanceof TextBlockDeltaEvent delta && !delta.getDelta().isBlank()) {
      emit(RunEvent.Type.MODEL_DELTA, Map.of("text", delta.getDelta()));
    }
    AgentScopeEventMapper.map(event).forEach(this::emit);
  }

  private void emit(RunEvent.Type type, Map<String, Object> data) {
    emit(new AgentScopeAdapter.Signal(type, data));
  }

  private void emit(AgentScopeAdapter.Signal signal) {
    synchronized (signals) {
      signals.emitNext(signal, Sinks.EmitFailureHandler.FAIL_FAST);
    }
  }

  private void fail(Throwable error) {
    synchronized (signals) {
      signals.emitError(error, Sinks.EmitFailureHandler.FAIL_FAST);
    }
  }

  private void complete() {
    emit(RunEvent.Type.RUN_COMPLETED, Map.of("result", RunResult.completed(completedText)));
    synchronized (signals) {
      signals.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
    }
  }

  private static final class PublishedSkillRepository implements AgentSkillRepository {
    private static final String SOURCE = "published-agent-skill";
    private final Map<String, AgentSkill> skills;

    private PublishedSkillRepository(List<RunRequest.Skill> published) {
      var resolved = new LinkedHashMap<String, AgentSkill>();
      for (var skill : published) {
        var resources = new LinkedHashMap<>(skill.resources());
        resources.putIfAbsent("SKILL.md", skill.markdown());
        resolved.put(
            skill.key(),
            AgentSkill.builder()
                .name(skill.key())
                .description(skill.description())
                .skillContent(skill.markdown())
                .resources(resources)
                .source(SOURCE)
                .build());
      }
      skills = Map.copyOf(resolved);
    }

    @Override
    public AgentSkill getSkill(String name) {
      return skills.get(name);
    }

    @Override
    public List<String> getAllSkillNames() {
      return List.copyOf(skills.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
      return List.copyOf(skills.values());
    }

    @Override
    public boolean save(List<AgentSkill> values, boolean overwrite) {
      return false;
    }

    @Override
    public boolean delete(String name) {
      return false;
    }

    @Override
    public boolean skillExists(String name) {
      return skills.containsKey(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
      return new AgentSkillRepositoryInfo("published", SOURCE, false);
    }

    @Override
    public String getSource() {
      return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
      if (writeable) {
        throw new UnsupportedOperationException("published skills are read-only");
      }
    }

    @Override
    public boolean isWriteable() {
      return false;
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

    String safeRunMessage() {
      return getCause() instanceof ToolCallLimitExceeded
          ? getMessage() + ": " + getCause().getMessage()
          : getMessage();
    }
  }

  static final class ToolCallLimitExceeded extends RuntimeException {
    ToolCallLimitExceeded(String message) {
      super(message);
    }
  }

  static final class HookFailure extends RuntimeException {
    HookFailure(String hookKey, Throwable cause) {
      super("Hook " + hookKey + " failed", cause);
    }
  }

  private static final class StrictAgentTool extends ToolBase {
    private static final Set<String> TRUSTED_ARGUMENT_NAMES =
        Set.of("userId", "runId", "permissions", "operationId");
    private final ResolvedTool tool;
    private final Sinks.Many<AgentScopeAdapter.Signal> signals;
    private final RunBudget budget;

    private StrictAgentTool(
        ResolvedTool tool, Sinks.Many<AgentScopeAdapter.Signal> signals, RunBudget budget) {
      super(
          tool.descriptor().runtimeName(),
          description(tool),
          tool.descriptor().inputSchema().document(),
          isReadOnly(tool),
          false,
          false,
          null,
          false,
          false);
      this.tool = tool;
      this.signals = signals;
      this.budget = budget;
    }

    private static String description(ResolvedTool tool) {
      return tool.usageGuidance().isBlank()
          ? tool.descriptor().description()
          : tool.descriptor().description() + "\n" + tool.usageGuidance();
    }

    private static boolean isReadOnly(ResolvedTool tool) {
      return tool.descriptor().sideEffect()
              == happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect.NONE
          || tool.descriptor().sideEffect()
              == happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect.READ;
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
          .onErrorResume(
              ToolInputException.class,
              error -> {
                emit(
                    RunEvent.Type.TOOL_FAILED,
                    Map.of("toolName", getName(), "errorMessage", errorMessage(error)));
                return Mono.just(
                    ToolResultBlock.error(ToolErrorResponse.invalidArgument(error).json())
                        .withIdAndName(param.getToolUseBlock().getId(), getName()));
              })
          .onErrorMap(
              error -> error instanceof ToolFailure ? error : new ToolFailure(getName(), error))
          .doOnError(
              error -> {
                emit(
                    RunEvent.Type.TOOL_FAILED,
                    Map.of("toolName", getName(), "errorMessage", errorMessage(error)));
                fail(error);
              });
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
        Map<String, Object> input, PermissionContextState state) {
      return Mono.fromCallable(
          () -> {
            try {
              validateInput(Map.copyOf(new LinkedHashMap<>(input)));
              return PermissionDecision.passthrough(getName());
            } catch (ToolInputException error) {
              return PermissionDecision.allow(getName());
            }
          });
    }

    private Mono<ToolResultBlock> invoke(ToolCallParam param, Map<String, Object> arguments) {
      var invocation =
          Mono.fromCallable(
                  () -> {
                    validateInput(arguments);
                    var trusted =
                        param
                            .getContext()
                            .get(
                                happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext
                                    .class);
                    if (trusted == null) {
                      throw new IllegalStateException("trusted tool context is missing");
                    }
                    Object result;
                    try {
                      result = tool.handler().invoke(arguments, trusted);
                    } catch (ToolInputException error) {
                      throw error;
                    } catch (IllegalArgumentException error) {
                      throw new ToolInputException(error.getMessage(), error);
                    }
                    var json =
                        ToolSchemaCodec.encode(result, tool.descriptor().outputSchema().document());
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
                  .filter(
                      error ->
                          !(error instanceof ToolSchemaCodec.InvalidToolValueException)
                              && !(error instanceof ToolInputException)));
    }

    private void validateInput(Map<String, Object> arguments) throws Exception {
      if (!Collections.disjoint(arguments.keySet(), TRUSTED_ARGUMENT_NAMES)) {
        throw new SecurityException(
            "trusted execution context fields are not accepted as model arguments");
      }
      try {
        ToolSchemaCodec.validateInput(arguments, tool.descriptor().inputSchema().document());
        tool.handler().validate(arguments);
      } catch (ToolInputException error) {
        throw error;
      } catch (ToolSchemaCodec.InvalidToolValueException | IllegalArgumentException error) {
        throw new ToolInputException(error.getMessage(), error);
      }
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

    private static String errorMessage(Throwable error) {
      var cause =
          error instanceof ToolFailure && error.getCause() != null ? error.getCause() : error;
      return cause.getMessage() == null || cause.getMessage().isBlank()
          ? "Tool execution failed"
          : cause.getMessage();
    }
  }

  private static final class DeterministicHook implements Hook {
    private final RunRequest request;
    private final RunBudget budget;
    private final java.util.function.Consumer<AgentScopeAdapter.Signal> signalConsumer;

    private DeterministicHook(
        RunRequest request,
        RunBudget budget,
        java.util.function.Consumer<AgentScopeAdapter.Signal> signalConsumer) {
      this.request = request;
      this.budget = budget;
      this.signalConsumer = signalConsumer;
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
                  emit(
                      RunEvent.Type.HOOK_STARTED,
                      Map.of(
                          "runId", request.runId(), "hookKey", hook.key(), "phase", phase.name()));
                  hook.action()
                      .execute(
                          new RunRequest.HookContext(
                              request.runId(), request.userId(), request.input(), phase));
                  emit(
                      RunEvent.Type.HOOK_COMPLETED,
                      Map.of(
                          "runId", request.runId(), "hookKey", hook.key(), "phase", phase.name()));
                } catch (Exception error) {
                  emit(
                      RunEvent.Type.HOOK_FAILED,
                      Map.of(
                          "runId",
                          request.runId(),
                          "hookKey",
                          hook.key(),
                          "phase",
                          phase.name(),
                          "errorMessage",
                          error.getMessage() == null ? "Hook failed" : error.getMessage()));
                  if (hook.failurePolicy() == HookDefinition.FailurePolicy.FAIL_CLOSED) {
                    throw new HookFailure(hook.key(), error);
                  }
                }
              }
            }
            return event;
          });
    }

    private void emit(RunEvent.Type type, Map<String, Object> data) {
      signalConsumer.accept(AgentScopeAdapter.Signal.generic(type, data));
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
    private final Set<String> toolCallIds = ConcurrentHashMap.newKeySet();

    private RunBudget(int maxToolCalls) {
      this.maxToolCalls = maxToolCalls;
    }

    private void reserveGlobal(ToolUseBlock toolUse) {
      if (!toolCallIds.add(toolUse.getId())) {
        throw new ToolFailure(
            toolUse.getName(), new IllegalArgumentException("duplicate tool call id"));
      }
      var used = globalCalls.incrementAndGet();
      if (used > maxToolCalls) {
        throw new ToolFailure(
            toolUse.getName(), new ToolCallLimitExceeded("global tool call limit exceeded"));
      }
    }

    private void reserveTool(String toolName, int maxCalls) {
      var used =
          toolCalls.computeIfAbsent(toolName, ignored -> new AtomicInteger()).incrementAndGet();
      if (used > maxCalls) {
        throw new ToolFailure(toolName, new ToolCallLimitExceeded("per-tool call limit exceeded"));
      }
    }
  }
}
