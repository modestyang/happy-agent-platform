package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import happy.jayden.yang.agentbuilder.core.runtime.RunFailureCode;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.runtime.RunResult;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolHandler;
import happy.jayden.yang.agentbuilder.core.tool.ResolvedTool;
import happy.jayden.yang.agentbuilder.core.tool.ToolDescriptor;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolInputException;
import happy.jayden.yang.agentbuilder.core.tool.ToolLifecycleStatus;
import happy.jayden.yang.agentbuilder.core.tool.ToolRiskLevel;
import happy.jayden.yang.agentbuilder.core.tool.ToolSchema;
import happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect;
import happy.jayden.yang.agentbuilder.core.tool.ToolSourceType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class AgentScopeAdapterContractTest extends FrameworkAdapterContract {
  private static final String CHECKSUM = "a".repeat(64);

  @AfterAll
  static void releaseFrameworkRuntime() {
    io.agentscope.core.shutdown.GracefulShutdownManager.getInstance().resetForTesting();
    Schedulers.shutdownNow();
  }

  @Override
  protected ConformanceEvidence conformanceEvidence() {
    var hookOrder = new ArrayList<String>();
    var observedContext =
        new java.util.concurrent.atomic.AtomicReference<
            happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext>();
    var transport = new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done");
    var events =
        new AgentScopeAdapter(transport)
            .run(
                request(
                    hookOrder,
                    (arguments, context) -> {
                      observedContext.set(context);
                      return Map.of("workouts", 1);
                    }))
            .collectList()
            .block();
    var skillTransport = new SkillLoadingModelTransport();
    var skillEvents =
        new AgentScopeAdapter(skillTransport).run(request(new ArrayList<>())).collectList().block();
    var invalid =
        terminal(
            new AgentScopeAdapter(
                    new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done"))
                .run(request(new ArrayList<>(), (arguments, context) -> Map.of("workouts", "bad")))
                .collectList()
                .block());
    var mandatory =
        terminal(
            new AgentScopeAdapter(
                    new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done"))
                .run(
                    requestWithTool(
                        tool((arguments, context) -> Map.of("workouts", 1)),
                        config(),
                        List.of(
                            new RunRequest.Hook(
                                "mandatory",
                                HookDefinition.Phase.PRE_MODEL,
                                1,
                                true,
                                HookDefinition.FailurePolicy.FAIL_CLOSED,
                                ignored -> {
                                  throw new IllegalStateException("mandatory");
                                }))))
                .collectList()
                .block());
    var cancellingTransport = ScriptedModelTransport.neverEnding();
    new AgentScopeAdapter(cancellingTransport)
        .run(request(new ArrayList<>()))
        .take(java.time.Duration.ofMillis(100))
        .blockLast();
    var strictSchema =
        transport.toolSchemas.stream()
            .filter(schema -> schema.getName().equals("lookup"))
            .findFirst()
            .orElseThrow()
            .getParameters();
    return new ConformanceEvidence(
        new AgentScopeAdapter(transport).capabilities(),
        strictSchema,
        transport.systemPrompt(),
        "",
        skillEvents,
        hookOrder,
        events,
        transport.modelArguments(),
        observedContext.get(),
        Map.of(
            happy.jayden.yang.agentbuilder.core.runtime.RunFailureCode.TOOL, invalid,
            happy.jayden.yang.agentbuilder.core.runtime.RunFailureCode.HOOK, mandatory),
        cancellingTransport.cancelled.get() ? 1 : 0);
  }

  private static RunEvent terminal(List<RunEvent> events) {
    return events.get(events.size() - 1);
  }

  private static void assertRelativeOrder(List<RunEvent> events, List<RunEvent.Type> expected) {
    int next = 0;
    for (var event : events) {
      if (next < expected.size() && event.type() == expected.get(next)) {
        next++;
      }
    }
    assertEquals(expected.size(), next, events.stream().map(RunEvent::type).toList().toString());
  }

  @Test
  void emitsOrderedEventsAndKeepsTrustedContextOutOfModelArguments() {
    var hookOrder = new ArrayList<String>();
    var transport = new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done");
    var adapter = new AgentScopeAdapter(transport);

    var events = adapter.run(request(hookOrder)).collectList().block();

    assertRelativeOrder(
        events,
        List.of(
            RunEvent.Type.RUN_STARTED,
            RunEvent.Type.REPLY_STARTED,
            RunEvent.Type.MODEL_DELTA,
            RunEvent.Type.TOOL_STARTED,
            RunEvent.Type.TOOL_RESULT,
            RunEvent.Type.MODEL_DELTA,
            RunEvent.Type.REPLY_ENDED,
            RunEvent.Type.RUN_COMPLETED));
    assertEquals(
        List.of(
            "pre-agent",
            "pre-model",
            "post-model",
            "pre-tool",
            "post-tool",
            "pre-model",
            "post-model",
            "post-agent"),
        hookOrder);
    assertEquals(
        List.of("draft", "done"),
        events.stream()
            .filter(event -> event.type() == RunEvent.Type.MODEL_DELTA)
            .map(event -> (String) event.data().get("text"))
            .toList());
    assertEquals(Map.of("query", "today"), transport.modelArguments());
    assertFalse(transport.modelArguments().containsKey("userId"));
    assertFalse(transport.modelArguments().containsKey("runId"));
    assertEquals(List.of("{\"workouts\":1}"), transport.observedToolResults);
    var convertedTool =
        transport.toolSchemas.stream()
            .filter(schema -> schema.getName().equals("lookup"))
            .findFirst()
            .orElseThrow();
    assertEquals(Boolean.TRUE, convertedTool.getStrict());
    assertEquals(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("query", Map.of("type", "string", "description", "Workout search query")),
            "required",
            List.of("query"),
            "additionalProperties",
            false),
        convertedTool.getParameters());
    assertTrue(transport.systemPrompt().contains("Use the lookup tool."));
    assertTrue(transport.systemPrompt().contains("load that Skill before answering"));
    assertFalse(transport.systemPrompt().contains("Full skill procedure"));
    assertTrue(transport.systemPrompt().contains("always content"));
    assertFalse(transport.systemPrompt().contains("on demand content"));
    assertFalse(transport.toolNames.contains("wait_async_results"));
  }

  @Test
  void downstreamCancellationClosesTheFrameworkRun() {
    var transport = ScriptedModelTransport.neverEnding();
    var adapter = new AgentScopeAdapter(transport);

    adapter.run(request(new ArrayList<>())).take(java.time.Duration.ofMillis(100)).blockLast();

    assertTrue(transport.subscribed.get());
    assertEquals(1, transport.interrupts.get());
    assertTrue(transport.cancelled.get());
    assertTrue(transport.closed.get());
  }

  @Test
  void cancellingImmediatelyAfterRunStartedDoesNotStartTheFramework() {
    var transport = ScriptedModelTransport.neverEnding();

    new AgentScopeAdapter(transport).run(request(new ArrayList<>())).take(1).blockLast();

    assertFalse(transport.subscribed.get());
    assertEquals(0, transport.interrupts.get());
    assertFalse(transport.closed.get());
  }

  @Test
  void mapsToolFailuresToTheNeutralFailureContract() {
    var transport = new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done");
    var adapter = new AgentScopeAdapter(transport);
    var events =
        adapter
            .run(
                request(
                    new ArrayList<>(),
                    (arguments, context) -> {
                      throw new IllegalStateException("database unavailable");
                    }))
            .collectList()
            .block();

    assertEquals(RunEvent.Type.RUN_FAILED, events.get(events.size() - 1).type());
    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event.type() == RunEvent.Type.TOOL_FAILED
                        && "database unavailable".equals(event.data().get("errorMessage"))));
    var result = (RunResult) events.get(events.size() - 1).data().get("result");
    assertEquals(RunFailureCode.TOOL, result.failure().orElseThrow().code());
    assertEquals("Tool lookup failed", result.failure().orElseThrow().message());
  }

  @Test
  void returnsCorrectableToolInputFailuresToTheModelLoop() {
    var transport = new CorrectingToolModelTransport();
    var invocations = new AtomicInteger();
    AgentToolHandler handler =
        new AgentToolHandler() {
          @Override
          public void validate(Map<String, Object> arguments) throws Exception {
            if ("bad".equals(arguments.get("query"))) {
              throw new ToolInputException("query 参数无效");
            }
          }

          @Override
          public Object invoke(Map<String, Object> arguments, ToolExecutionContext context) {
            invocations.incrementAndGet();
            return Map.of("workouts", 1);
          }
        };

    var events =
        new AgentScopeAdapter(transport)
            .run(requestWithTool(tool(handler), config(2), List.of()))
            .collectList()
            .block();

    assertEquals(3, transport.modelCalls.get());
    assertEquals(1, invocations.get(), transport.observedResults.toString());
    assertEquals(ToolResultState.ERROR, transport.invalidResultState);
    assertTrue(transport.invalidResult.contains("\"code\":\"INVALID_ARGUMENT\""));
    assertTrue(transport.invalidResult.contains("\"retryable\":true"));
    assertTrue(events.stream().anyMatch(event -> event.type() == RunEvent.Type.TOOL_FAILED));
    assertTrue(events.stream().anyMatch(event -> event.type() == RunEvent.Type.TOOL_RESULT));
    assertFalse(events.stream().anyMatch(event -> event.type() == RunEvent.Type.RUN_FAILED));
    assertEquals(RunEvent.Type.RUN_COMPLETED, events.get(events.size() - 1).type());
  }

  @Test
  void asksForConfirmationBeforeInvokingAnAlwaysApprovedWriteTool() {
    var invocations = new AtomicInteger();
    var resolvedTool = tool((arguments, context) -> Map.of("saved", invocations.incrementAndGet()));
    var confirmationTool =
        new ResolvedTool(
            resolvedTool.descriptor(),
            resolvedTool.binding(),
            resolvedTool.handler(),
            resolvedTool.usageGuidance(),
            resolvedTool.timeoutMs(),
            resolvedTool.maxCallsPerRun(),
            ApprovalPolicy.ALWAYS,
            resolvedTool.retryPolicy(),
            resolvedTool.resultMode());

    var events =
        new AgentScopeAdapter(
                new ScriptedModelTransport("", "lookup", Map.of("query", "today"), "done"))
            .run(requestWithTool(confirmationTool, config(1), List.of()))
            .collectList()
            .block();

    assertEquals(0, invocations.get());
    var confirmation =
        events.stream()
            .filter(event -> event.type() == RunEvent.Type.CONFIRMATION_REQUIRED)
            .findFirst()
            .orElseThrow();
    assertTrue(confirmation.data().get("toolCalls") instanceof List<?>);
    var toolCalls = (List<?>) confirmation.data().get("toolCalls");
    assertTrue(toolCalls.get(0) instanceof Map<?, ?>);
    var toolCall = (Map<?, ?>) toolCalls.get(0);
    assertEquals(Map.of("query", "today"), toolCall.get("arguments"));
    assertTrue(
        events.stream().anyMatch(event -> event.type() == RunEvent.Type.RUN_WAITING_APPROVAL));
  }

  @Test
  void invokesModelHooksForEveryReasoningLifecycleOccurrence() {
    var hookOrder = new ArrayList<String>();
    var transport = new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done");

    new AgentScopeAdapter(transport).run(request(hookOrder)).collectList().block();

    assertEquals(2, hookOrder.stream().filter("pre-model"::equals).count());
    assertEquals(2, hookOrder.stream().filter("post-model"::equals).count());
  }

  @Test
  void rejectsTheFirstToolBeforeSideEffectsWhenGlobalBudgetIsZero() {
    var invocations = new AtomicInteger();
    var transport = new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done");
    var events =
        new AgentScopeAdapter(transport)
            .run(
                request(
                    new ArrayList<>(),
                    (arguments, context) -> {
                      invocations.incrementAndGet();
                      return Map.of("workouts", 1);
                    },
                    config(0)))
            .collectList()
            .block();

    assertEquals(0, invocations.get());
    assertEquals(RunEvent.Type.RUN_FAILED, events.get(events.size() - 1).type());
    var result = (RunResult) events.get(events.size() - 1).data().get("result");
    assertEquals(RunFailureCode.TOOL, result.failure().orElseThrow().code());
    assertEquals(
        "Tool lookup failed: global tool call limit exceeded",
        result.failure().orElseThrow().message());
  }

  @Test
  void modelCredentialNeverAppearsInStringsOrJacksonSerialization() throws Exception {
    var scopedCopy = new AtomicReference<char[]>();
    var credential = new RunRequest.ModelCredential("credential-do-not-leak".toCharArray());
    var endpoint =
        new RunRequest.ModelEndpoint(URI.create("https://example.test/v1"), "model", credential);

    assertFalse(endpoint.toString().contains("credential-do-not-leak"));
    assertFalse(new ObjectMapper().writeValueAsString(endpoint).contains("credential-do-not-leak"));
    credential.use(
        secret -> {
          scopedCopy.set(secret);
          return null;
        });
    assertTrue(new String(scopedCopy.get()).chars().allMatch(character -> character == 0));
  }

  @Test
  void invalidToolOutputFailsWithoutPublishingAToolResult() {
    var transport = new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done");
    var events =
        new AgentScopeAdapter(transport)
            .run(
                request(
                    new ArrayList<>(),
                    (arguments, context) -> Map.of("workouts", "not-an-integer")))
            .collectList()
            .block();

    assertFalse(events.stream().anyMatch(event -> event.type() == RunEvent.Type.TOOL_RESULT));
    assertEquals(RunEvent.Type.RUN_FAILED, events.get(events.size() - 1).type());
    var result = (RunResult) events.get(events.size() - 1).data().get("result");
    assertEquals(RunFailureCode.TOOL, result.failure().orElseThrow().code());
  }

  @Test
  void serializesSupportedJavaTimeOutputsAsIsoJson() {
    var transport = new RepeatingToolModelTransport(1, false);
    var temporalSchema =
        new ToolSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("date", Map.of("type", "string", "format", "date", "description", "Date")),
                "required",
                List.of("date"),
                "additionalProperties",
                false));
    var resolvedTool =
        tool(
            (arguments, context) -> Map.of("date", LocalDate.of(2026, 8, 5)),
            1_000,
            1,
            RetryPolicy.NONE,
            ToolSideEffect.READ,
            true,
            temporalSchema);
    var events =
        new AgentScopeAdapter(transport)
            .run(requestWithTool(resolvedTool, config(1), List.of()))
            .collectList()
            .block();

    assertEquals(RunEvent.Type.RUN_COMPLETED, events.get(events.size() - 1).type());
    assertTrue(transport.observedToolResults.contains("{\"date\":\"2026-08-05\"}"));
  }

  @Test
  void runtimePatternValidationRequiresTheWholeStringToMatch() {
    var patternSchema =
        new ToolSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string", "pattern", "abc", "description", "Value")),
                "required",
                List.of("value"),
                "additionalProperties",
                false));
    var resolvedTool =
        tool(
            (arguments, context) -> Map.of("value", "xxabcxx"),
            1_000,
            1,
            RetryPolicy.NONE,
            ToolSideEffect.READ,
            true,
            patternSchema);
    var events =
        new AgentScopeAdapter(new RepeatingToolModelTransport(1, false))
            .run(requestWithTool(resolvedTool, config(1), List.of()))
            .collectList()
            .block();

    assertFalse(events.stream().anyMatch(event -> event.type() == RunEvent.Type.TOOL_RESULT));
    assertEquals(RunEvent.Type.RUN_FAILED, events.get(events.size() - 1).type());
  }

  @Test
  void loadsSkillMarkdownThroughTheRealSkillBoxToolBeforeUsingItsTool() {
    var hookOrder = new ArrayList<String>();
    var transport = new SkillLoadingModelTransport();
    var events = new AgentScopeAdapter(transport).run(request(hookOrder)).collectList().block();

    assertEquals(RunEvent.Type.RUN_COMPLETED, events.get(events.size() - 1).type());
    assertTrue(transport.loadedMarkdownObserved.get(), transport.observedToolResults.toString());
    assertEquals(2, hookOrder.stream().filter("pre-tool"::equals).count());
    assertEquals(2, hookOrder.stream().filter("post-tool"::equals).count());
  }

  @Test
  void skillLoadingConsumesTheSameGlobalToolBudgetAsPublishedTools() {
    var invocations = new AtomicInteger();
    var events =
        new AgentScopeAdapter(new SkillLoadingModelTransport())
            .run(
                requestWithTool(
                    tool(
                        (arguments, context) -> {
                          invocations.incrementAndGet();
                          return Map.of("workouts", 1);
                        }),
                    config(1),
                    List.of()))
            .collectList()
            .block();

    assertEquals(0, invocations.get());
    assertEquals(RunEvent.Type.RUN_FAILED, events.get(events.size() - 1).type());
  }

  @Test
  void rejectsModelSpoofingOfTrustedContextFieldsBeforeInvokingTheHandler() {
    var invocations = new AtomicInteger();
    var transport =
        new ScriptedModelTransport(
            "draft", "lookup", Map.of("query", "today", "userId", "attacker"), "done");
    var events =
        new AgentScopeAdapter(transport)
            .run(
                request(
                    new ArrayList<>(),
                    (arguments, context) -> {
                      invocations.incrementAndGet();
                      return Map.of("workouts", 1);
                    }))
            .collectList()
            .block();

    assertEquals(0, invocations.get());
    assertEquals(RunEvent.Type.RUN_FAILED, events.get(events.size() - 1).type());
  }

  @Test
  void optionalHooksExplicitlyFailOpenWhileMandatoryHooksFailClosed() {
    var optionalHooks =
        List.of(
            new RunRequest.Hook(
                "optional-open",
                HookDefinition.Phase.PRE_MODEL,
                1,
                false,
                HookDefinition.FailurePolicy.FAIL_OPEN,
                ignored -> {
                  throw new IllegalStateException("optional");
                }),
            new RunRequest.Hook(
                "optional-continue",
                HookDefinition.Phase.PRE_MODEL,
                2,
                false,
                HookDefinition.FailurePolicy.CONTINUE,
                ignored -> {
                  throw new IllegalStateException("optional");
                }));
    var optionalEvents =
        new AgentScopeAdapter(
                new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done"))
            .run(
                requestWithTool(
                    tool((arguments, context) -> Map.of("workouts", 1)), config(), optionalHooks))
            .collectList()
            .block();
    assertEquals(RunEvent.Type.RUN_COMPLETED, optionalEvents.get(optionalEvents.size() - 1).type());

    var mandatoryHook =
        new RunRequest.Hook(
            "mandatory",
            HookDefinition.Phase.PRE_MODEL,
            1,
            true,
            HookDefinition.FailurePolicy.FAIL_CLOSED,
            ignored -> {
              throw new IllegalStateException("mandatory");
            });
    var mandatoryEvents =
        new AgentScopeAdapter(
                new ScriptedModelTransport("draft", "lookup", Map.of("query", "today"), "done"))
            .run(
                requestWithTool(
                    tool((arguments, context) -> Map.of("workouts", 1)),
                    config(),
                    List.of(mandatoryHook)))
            .collectList()
            .block();
    var mandatoryResult =
        (RunResult) mandatoryEvents.get(mandatoryEvents.size() - 1).data().get("result");
    assertEquals(RunFailureCode.HOOK, mandatoryResult.failure().orElseThrow().code());
  }

  @Test
  void enforcesPerToolCallLimitBeforeASecondInvocation() {
    var invocations = new AtomicInteger();
    var resolvedTool =
        tool(
            (arguments, context) -> {
              invocations.incrementAndGet();
              return Map.of("workouts", 1);
            },
            1_000,
            1,
            RetryPolicy.NONE,
            ToolSideEffect.READ,
            true);
    var events =
        new AgentScopeAdapter(new RepeatingToolModelTransport(2, false))
            .run(requestWithTool(resolvedTool, config(3), List.of()))
            .collectList()
            .block();

    assertEquals(1, invocations.get());
    assertEquals(RunEvent.Type.RUN_FAILED, events.get(events.size() - 1).type());
    var result = (RunResult) events.get(events.size() - 1).data().get("result");
    assertEquals(RunFailureCode.TOOL, result.failure().orElseThrow().code());
    assertEquals(
        "Tool lookup failed: per-tool call limit exceeded",
        result.failure().orElseThrow().message());
  }

  @Test
  void safeRetryRetriesOnceWithinOneReservedToolCall() {
    var invocations = new AtomicInteger();
    var resolvedTool =
        tool(
            (arguments, context) -> {
              if (invocations.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
              }
              return Map.of("workouts", 1);
            },
            1_000,
            1,
            RetryPolicy.SAFE_ONCE,
            ToolSideEffect.READ,
            true);
    var events =
        new AgentScopeAdapter(new RepeatingToolModelTransport(1, false))
            .run(requestWithTool(resolvedTool, config(1), List.of()))
            .collectList()
            .block();

    assertEquals(2, invocations.get());
    assertEquals(RunEvent.Type.RUN_COMPLETED, events.get(events.size() - 1).type());
    assertEquals(
        1, events.stream().filter(event -> event.type() == RunEvent.Type.TOOL_STARTED).count());
  }

  @Test
  void unsafeWriteIsNeverRetried() {
    var invocations = new AtomicInteger();
    var resolvedTool =
        tool(
            (arguments, context) -> {
              invocations.incrementAndGet();
              throw new IllegalStateException("write failed");
            },
            1_000,
            1,
            RetryPolicy.SAFE_TWICE,
            ToolSideEffect.WRITE,
            false);
    new AgentScopeAdapter(new RepeatingToolModelTransport(1, false))
        .run(requestWithTool(resolvedTool, config(1), List.of()))
        .collectList()
        .block();

    assertEquals(1, invocations.get());
  }

  @Test
  void parallelToolCallsCannotOverrunTheGlobalBudget() {
    var invocations = new AtomicInteger();
    var resolvedTool =
        tool((arguments, context) -> Map.of("workouts", invocations.incrementAndGet()));
    var events =
        new AgentScopeAdapter(new RepeatingToolModelTransport(2, true))
            .run(requestWithTool(resolvedTool, config(1), List.of()))
            .collectList()
            .block();

    assertTrue(invocations.get() <= 1);
    assertEquals(RunEvent.Type.RUN_FAILED, events.get(events.size() - 1).type());
  }

  @Test
  void duplicateIdsOnParallelToolCallsCannotBypassTheGlobalBudget() {
    var invocations = new AtomicInteger();
    var events =
        new AgentScopeAdapter(new RepeatingToolModelTransport(2, true, true))
            .run(
                requestWithTool(
                    tool((arguments, context) -> Map.of("workouts", invocations.incrementAndGet())),
                    config(1),
                    List.of()))
            .collectList()
            .block();

    assertTrue(invocations.get() <= 1);
    assertTrue(
        events.get(events.size() - 1).type() == RunEvent.Type.RUN_COMPLETED
            || events.get(events.size() - 1).type() == RunEvent.Type.RUN_FAILED);
  }

  @Test
  void toolTimeoutMapsToTimeout() {
    var resolvedTool =
        tool(
            (arguments, context) -> {
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
              }
              return Map.of("workouts", 1);
            },
            100,
            1,
            RetryPolicy.NONE,
            ToolSideEffect.READ,
            true);
    var events =
        new AgentScopeAdapter(new RepeatingToolModelTransport(1, false))
            .run(requestWithTool(resolvedTool, config(1), List.of()))
            .collectList()
            .block();

    var result = (RunResult) events.get(events.size() - 1).data().get("result");
    assertEquals(RunFailureCode.TIMEOUT, result.failure().orElseThrow().code());
  }

  @Test
  void overallRunTimeoutInterruptsAndClosesTheFramework() {
    var transport = ScriptedModelTransport.neverEnding();
    var events =
        new AgentScopeAdapter(transport)
            .run(
                requestWithTool(
                    tool((arguments, context) -> Map.of("workouts", 1)),
                    configWithRunSeconds(1),
                    List.of()))
            .collectList()
            .block();

    var result = (RunResult) events.get(events.size() - 1).data().get("result");
    assertEquals(RunFailureCode.TIMEOUT, result.failure().orElseThrow().code());
    assertEquals(1, transport.interrupts.get());
    assertTrue(transport.closed.get());
  }

  private static RunRequest request(List<String> hookOrder) {
    var context =
        new ToolExecutionContext("user-1", "run-1", Set.of("fitness:read"), "operation-1");
    AgentToolHandler handler =
        (arguments, actualContext) -> {
          assertEquals(context, actualContext);
          assertEquals(Map.of("query", "today"), arguments);
          return Map.of("workouts", 1);
        };
    return request(hookOrder, handler);
  }

  private static RunRequest request(List<String> hookOrder, AgentToolHandler handler) {
    return request(hookOrder, handler, config());
  }

  private static RunRequest request(
      List<String> hookOrder, AgentToolHandler handler, ResolvedAgentConfig config) {
    var context =
        new ToolExecutionContext("user-1", "run-1", Set.of("fitness:read"), "operation-1");
    return new RunRequest(
        "run-1",
        "user-1",
        "help me",
        config,
        new RunRequest.ModelEndpoint(
            URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            "qwen-plus",
            new RunRequest.ModelCredential("secret".toCharArray())),
        List.of(tool(handler)),
        List.of(
            new RunRequest.Skill(
                "fitness",
                "Use the lookup tool.",
                "Full skill procedure that is loaded only when selected.",
                Map.of("always.md", "always content", "detail.md", "on demand content"),
                Set.of("always.md"),
                Set.of("detail.md"))),
        hooks(hookOrder),
        new RunRequest.Memory(List.of("Earlier goal: keep workouts short."), 1_000),
        context);
  }

  private static RunRequest requestWithTool(
      ResolvedTool resolvedTool, ResolvedAgentConfig config, List<RunRequest.Hook> hooks) {
    var base = request(new ArrayList<>());
    return new RunRequest(
        base.runId(),
        base.userId(),
        base.input(),
        config,
        base.model(),
        List.of(resolvedTool),
        base.skills(),
        hooks,
        base.memory(),
        base.toolExecutionContext());
  }

  private static List<RunRequest.Hook> hooks(List<String> execution) {
    return List.of(
        hook("post-agent", HookDefinition.Phase.POST_AGENT, 1, execution),
        hook("pre-model", HookDefinition.Phase.PRE_MODEL, 2, execution),
        hook("post-model", HookDefinition.Phase.POST_MODEL, 3, execution),
        hook("pre-agent", HookDefinition.Phase.PRE_AGENT, 10, execution),
        hook("post-tool", HookDefinition.Phase.POST_TOOL, 1, execution),
        hook("pre-tool", HookDefinition.Phase.PRE_TOOL, 1, execution));
  }

  private static RunRequest.Hook hook(
      String key, HookDefinition.Phase phase, int order, List<String> execution) {
    return new RunRequest.Hook(
        key,
        phase,
        order,
        true,
        HookDefinition.FailurePolicy.FAIL_CLOSED,
        ignored -> execution.add(key));
  }

  private static ResolvedTool tool(AgentToolHandler handler) {
    return tool(handler, 1_000, 2, RetryPolicy.NONE, ToolSideEffect.READ, true);
  }

  private static ResolvedTool tool(
      AgentToolHandler handler,
      int timeoutMs,
      int maxCallsPerRun,
      RetryPolicy retryPolicy,
      ToolSideEffect sideEffect,
      boolean idempotent) {
    return tool(
        handler,
        timeoutMs,
        maxCallsPerRun,
        retryPolicy,
        sideEffect,
        idempotent,
        new ToolSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "workouts", Map.of("type", "integer", "description", "Matching workout count")),
                "required",
                List.of("workouts"),
                "additionalProperties",
                false)));
  }

  private static ResolvedTool tool(
      AgentToolHandler handler,
      int timeoutMs,
      int maxCallsPerRun,
      RetryPolicy retryPolicy,
      ToolSideEffect sideEffect,
      boolean idempotent,
      ToolSchema outputSchema) {
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
            outputSchema,
            true,
            sideEffect,
            idempotent,
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
        new ToolBinding(new ComponentKey("fitness.lookup"), new ComponentVersion(1), true),
        handler,
        "",
        timeoutMs,
        maxCallsPerRun,
        ApprovalPolicy.NEVER,
        retryPolicy,
        ResultMode.MODEL_CONTEXT);
  }

  private static ResolvedAgentConfig config() {
    return config(2);
  }

  private static ResolvedAgentConfig config(int maxToolCalls) {
    return config(30, maxToolCalls);
  }

  private static ResolvedAgentConfig configWithRunSeconds(int maxRunSeconds) {
    return config(maxRunSeconds, 2);
  }

  private static ResolvedAgentConfig config(int maxRunSeconds, int maxToolCalls) {
    var source = EffectiveValueSource.platformLimit();
    var sources =
        new PublishedResolvedConfigSources(
            source, source, source, source, source, source, source, source, source, source, source,
            source, source);
    return new ResolvedAgentConfig(
        "fitness",
        new RuntimeLimits(maxRunSeconds, maxToolCalls, 2_000, 1_000, BigDecimal.ONE, 1),
        new ModelParameters(new BigDecimal("0.1"), new BigDecimal("0.9"), 1_000),
        RetryPolicy.NONE,
        sources);
  }

  private static final class ScriptedModelTransport implements AgentScopeModelTransport {
    private final String delta;
    private final String toolName;
    private final Map<String, Object> toolArguments;
    private final String completedText;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger interrupts = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private Map<String, Object> modelArguments = Map.of();
    private String systemPrompt = "";
    private List<String> toolNames = List.of();
    private List<io.agentscope.core.model.ToolSchema> toolSchemas = List.of();
    private List<String> observedToolResults = List.of();

    private ScriptedModelTransport(
        String delta, String toolName, Map<String, Object> toolArguments, String completedText) {
      this.delta = delta;
      this.toolName = toolName;
      this.toolArguments = toolArguments;
      this.completedText = completedText;
    }

    private static ScriptedModelTransport neverEnding() {
      return new ScriptedModelTransport("", "", Map.of(), "");
    }

    private Map<String, Object> modelArguments() {
      return modelArguments;
    }

    private String systemPrompt() {
      return systemPrompt;
    }

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages,
        List<io.agentscope.core.model.ToolSchema> tools,
        GenerateOptions options) {
      systemPrompt =
          messages.stream()
              .filter(message -> message.getRole() == MsgRole.SYSTEM)
              .map(Msg::getTextContent)
              .filter(java.util.Objects::nonNull)
              .reduce("", (left, right) -> left + right);
      subscribed.set(true);
      toolNames = tools.stream().map(schema -> schema.getName()).toList();
      toolSchemas = List.copyOf(tools);
      if (delta.isEmpty() && toolName.isEmpty() && completedText.isEmpty()) {
        return Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true));
      }
      if (calls.getAndIncrement() == 0) {
        modelArguments = Map.copyOf(toolArguments);
        return Flux.just(
            ChatResponse.builder()
                .id("response-1")
                .content(List.of(TextBlock.builder().text(delta).build()))
                .build(),
            ChatResponse.builder()
                .id("response-1")
                .content(
                    List.of(
                        ToolUseBlock.builder()
                            .id("tool-call-1")
                            .name(toolName)
                            .input(toolArguments)
                            .content("{\"query\":\"today\"}")
                            .build()))
                .finishReason("tool_calls")
                .build());
      }
      observedToolResults =
          messages.stream()
              .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
              .flatMap(result -> result.getOutput().stream())
              .filter(TextBlock.class::isInstance)
              .map(TextBlock.class::cast)
              .map(TextBlock::getText)
              .toList();
      return Flux.just(
          ChatResponse.builder()
              .id("response-2")
              .content(List.of(TextBlock.builder().text(completedText).build()))
              .finishReason("stop")
              .build());
    }

    @Override
    public String getModelName() {
      return "scripted-model";
    }

    @Override
    public void interrupt() {
      interrupts.incrementAndGet();
    }

    @Override
    public void close() {
      closed.set(true);
      cancelled.set(true);
    }
  }

  private static final class CorrectingToolModelTransport implements AgentScopeModelTransport {
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final List<String> observedResults = new ArrayList<>();
    private String invalidResult = "";
    private ToolResultState invalidResultState;

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages,
        List<io.agentscope.core.model.ToolSchema> tools,
        GenerateOptions options) {
      var turn = modelCalls.getAndIncrement();
      if (turn == 0) {
        return toolCall("invalid-call", Map.of("query", "bad"));
      }
      var results =
          messages.stream()
              .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
              .toList();
      observedResults.addAll(
          results.stream()
              .flatMap(result -> result.getOutput().stream())
              .filter(TextBlock.class::isInstance)
              .map(TextBlock.class::cast)
              .map(TextBlock::getText)
              .toList());
      if (turn == 1) {
        var failed = results.get(results.size() - 1);
        invalidResultState = failed.getState();
        invalidResult =
            failed.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
        return toolCall("corrected-call", Map.of("query", "today"));
      }
      return Flux.just(
          ChatResponse.builder()
              .id("corrected-response")
              .content(List.of(TextBlock.builder().text("已完成").build()))
              .finishReason("stop")
              .build());
    }

    private static Flux<ChatResponse> toolCall(String id, Map<String, Object> input) {
      return Flux.just(
          ChatResponse.builder()
              .id(id)
              .content(
                  List.of(
                      ToolUseBlock.builder()
                          .id(id)
                          .name("lookup")
                          .input(input)
                          .content(new ObjectMapper().valueToTree(input).toString())
                          .build()))
              .finishReason("tool_calls")
              .build());
    }

    @Override
    public String getModelName() {
      return "correcting-model";
    }
  }

  private static final class SkillLoadingModelTransport implements AgentScopeModelTransport {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean loadedMarkdownObserved = new AtomicBoolean();
    private final List<String> observedToolResults = new ArrayList<>();

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages,
        List<io.agentscope.core.model.ToolSchema> tools,
        GenerateOptions options) {
      return switch (calls.getAndIncrement()) {
        case 0 ->
            Flux.just(
                toolCall(
                    "load-1",
                    "load_skill_through_path",
                    Map.of("skillId", "fitness_published-agent-skill", "path", "SKILL.md"),
                    "{\"skillId\":\"fitness_published-agent-skill\",\"path\":\"SKILL.md\"}"));
        case 1 -> {
          observedToolResults.addAll(
              messages.stream()
                  .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                  .flatMap(result -> result.getOutput().stream())
                  .filter(TextBlock.class::isInstance)
                  .map(TextBlock.class::cast)
                  .map(TextBlock::getText)
                  .toList());
          loadedMarkdownObserved.set(
              observedToolResults.stream().anyMatch(text -> text.contains("Full skill procedure")));
          yield Flux.just(
              toolCall("lookup-1", "lookup", Map.of("query", "today"), "{\"query\":\"today\"}"));
        }
        default ->
            Flux.just(
                ChatResponse.builder()
                    .id("final")
                    .content(List.of(TextBlock.builder().text("done").build()))
                    .finishReason("stop")
                    .build());
      };
    }

    private static ChatResponse toolCall(
        String id, String name, Map<String, Object> input, String content) {
      return ChatResponse.builder()
          .id(id)
          .content(
              List.of(
                  ToolUseBlock.builder().id(id).name(name).input(input).content(content).build()))
          .finishReason("tool_calls")
          .build();
    }

    @Override
    public String getModelName() {
      return "skill-loading-model";
    }
  }

  private static final class RepeatingToolModelTransport implements AgentScopeModelTransport {
    private final int toolCalls;
    private final boolean parallel;
    private final boolean reuseCallId;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final List<String> observedToolResults = new ArrayList<>();

    private RepeatingToolModelTransport(int toolCalls, boolean parallel) {
      this(toolCalls, parallel, false);
    }

    private RepeatingToolModelTransport(int toolCalls, boolean parallel, boolean reuseCallId) {
      this.toolCalls = toolCalls;
      this.parallel = parallel;
      this.reuseCallId = reuseCallId;
    }

    @Override
    public Flux<ChatResponse> stream(
        List<Msg> messages,
        List<io.agentscope.core.model.ToolSchema> tools,
        GenerateOptions options) {
      var call = modelCalls.getAndIncrement();
      observedToolResults.addAll(
          messages.stream()
              .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
              .flatMap(result -> result.getOutput().stream())
              .filter(TextBlock.class::isInstance)
              .map(TextBlock.class::cast)
              .map(TextBlock::getText)
              .toList());
      if ((parallel && call == 0) || (!parallel && call < toolCalls)) {
        var count = parallel ? toolCalls : 1;
        var blocks = new ArrayList<io.agentscope.core.message.ContentBlock>();
        for (var index = 0; index < count; index++) {
          var id = reuseCallId ? "duplicate-id" : "lookup-" + call + "-" + index;
          blocks.add(
              ToolUseBlock.builder()
                  .id(id)
                  .name("lookup")
                  .input(Map.of("query", "today"))
                  .content("{\"query\":\"today\"}")
                  .build());
        }
        return Flux.just(
            ChatResponse.builder()
                .id("tools-" + call)
                .content(blocks)
                .finishReason("tool_calls")
                .build());
      }
      return Flux.just(
          ChatResponse.builder()
              .id("final")
              .content(List.of(TextBlock.builder().text("done").build()))
              .finishReason("stop")
              .build());
    }

    @Override
    public String getModelName() {
      return "repeating-tool-model";
    }
  }
}
