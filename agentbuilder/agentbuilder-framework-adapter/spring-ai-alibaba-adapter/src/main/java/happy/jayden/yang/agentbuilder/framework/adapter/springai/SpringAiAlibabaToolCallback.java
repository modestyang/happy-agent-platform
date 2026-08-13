package happy.jayden.yang.agentbuilder.framework.adapter.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import happy.jayden.yang.agentbuilder.core.component.ApprovalPolicy;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.tool.ResolvedTool;
import happy.jayden.yang.agentbuilder.core.tool.ToolErrorResponse;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolInputException;
import happy.jayden.yang.agentbuilder.core.tool.ToolSchemaCodec;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** Real Spring AI ToolCallback that keeps trusted state in ToolContext, never model arguments. */
final class SpringAiAlibabaToolCallback implements ToolCallback {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> TRUSTED_ARGUMENT_NAMES =
      Set.of("userId", "runId", "permissions", "operationId");

  private final ResolvedTool tool;
  private final RunRequest request;
  private final SpringAiAlibabaRuntimeBridge.RunBudget budget;
  private final BiConsumer<RunEvent.Type, Map<String, Object>> emitter;
  private final Consumer<Throwable> failure;

  SpringAiAlibabaToolCallback(
      ResolvedTool tool,
      RunRequest request,
      SpringAiAlibabaRuntimeBridge.RunBudget budget,
      BiConsumer<RunEvent.Type, Map<String, Object>> emitter,
      Consumer<Throwable> failure) {
    this.tool = Objects.requireNonNull(tool, "tool");
    this.request = Objects.requireNonNull(request, "request");
    this.budget = Objects.requireNonNull(budget, "budget");
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    this.failure = Objects.requireNonNull(failure, "failure");
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return DefaultToolDefinition.builder()
        .name(tool.descriptor().runtimeName())
        .description(
            tool.usageGuidance().isBlank()
                ? tool.descriptor().description()
                : tool.descriptor().description() + "\n" + tool.usageGuidance())
        .inputSchema(json(tool.descriptor().inputSchema().document()))
        .build();
  }

  @Override
  public String call(String toolInput) {
    return call(toolInput, new ToolContext(Map.of()));
  }

  @Override
  public String call(String toolInput, ToolContext context) {
    try {
      Invocation invocation;
      try {
        invocation = prepare(toolInput, context);
      } catch (ApprovalRequired confirmation) {
        throw confirmation;
      } catch (ToolInputException error) {
        throw error;
      } catch (SpringAiAlibabaAdapter.ToolFailure | SpringAiAlibabaAdapter.HookFailure error) {
        throw error;
      } catch (RuntimeException error) {
        throw new SpringAiAlibabaAdapter.ToolFailure(tool.descriptor().runtimeName(), error);
      }
      var attempt =
          Mono.fromCallable(() -> invokeAttempt(invocation.arguments(), invocation.context()))
              .subscribeOn(Schedulers.boundedElastic())
              .timeout(Duration.ofMillis(tool.timeoutMs()));
      if (safeRetryCount() > 0) {
        attempt =
            attempt.retryWhen(
                Retry.max(safeRetryCount())
                    .filter(
                        error ->
                            !(error instanceof ToolSchemaCodec.InvalidToolValueException)
                                && !(error instanceof ToolInputException)
                                && !(error instanceof SpringAiAlibabaAdapter.HookFailure)));
      }
      var output =
          attempt
              .onErrorMap(
                  error ->
                      error instanceof SpringAiAlibabaAdapter.ToolFailure
                              || error instanceof SpringAiAlibabaAdapter.HookFailure
                              || error instanceof ToolInputException
                          ? error
                          : new SpringAiAlibabaAdapter.ToolFailure(
                              tool.descriptor().runtimeName(), error))
              .block();
      runHooks(HookDefinition.Phase.POST_TOOL);
      var resultData = new LinkedHashMap<String, Object>();
      resultData.put("toolName", tool.descriptor().runtimeName());
      resultData.put("result", output.value());
      resultData.put("encodedResult", output.encoded());
      emitter.accept(RunEvent.Type.TOOL_RESULT, Collections.unmodifiableMap(resultData));
      return output.encoded();
    } catch (ToolInputException error) {
      emitter.accept(
          RunEvent.Type.TOOL_FAILED,
          Map.of(
              "toolName",
              tool.descriptor().runtimeName(),
              "errorMessage",
              ToolErrorResponse.invalidArgument(error).message()));
      return ToolErrorResponse.invalidArgument(error).json();
    } catch (RuntimeException error) {
      failure.accept(error);
      throw error;
    }
  }

  private Invocation prepare(String toolInput, ToolContext context) {
    Map<String, Object> arguments;
    try {
      arguments = arguments(toolInput);
    } catch (IllegalArgumentException error) {
      throw new ToolInputException(error.getMessage(), error);
    }
    if (!Collections.disjoint(arguments.keySet(), TRUSTED_ARGUMENT_NAMES)) {
      throw new SecurityException(
          "trusted execution context fields are not accepted as model arguments");
    }
    budget.reserve(tool.descriptor().runtimeName(), tool.maxCallsPerRun());
    var trusted = context.getContext().get(SpringAiAlibabaRuntimeBridge.TRUSTED_CONTEXT_KEY);
    if (!(trusted instanceof ToolExecutionContext toolExecutionContext)) {
      throw new IllegalStateException("trusted tool context is missing");
    }
    emitter.accept(
        RunEvent.Type.TOOL_STARTED,
        Map.of("toolName", tool.descriptor().runtimeName(), "arguments", arguments));
    try {
      ToolSchemaCodec.validateInput(arguments, tool.descriptor().inputSchema().document());
      tool.handler().validate(arguments);
    } catch (ToolInputException error) {
      throw error;
    } catch (ToolSchemaCodec.InvalidToolValueException | IllegalArgumentException error) {
      throw new ToolInputException(error.getMessage(), error);
    } catch (Exception error) {
      throw new SpringAiAlibabaAdapter.ToolFailure(tool.descriptor().runtimeName(), error);
    }
    if (tool.approvalPolicy() != ApprovalPolicy.NEVER) {
      emitter.accept(
          RunEvent.Type.CONFIRMATION_REQUIRED,
          Map.of(
              "toolName", tool.descriptor().runtimeName(),
              "arguments", arguments,
              "approvalPolicy", tool.approvalPolicy().name()));
      emitter.accept(
          RunEvent.Type.RUN_WAITING_APPROVAL, Map.of("toolName", tool.descriptor().runtimeName()));
      throw new ApprovalRequired(tool.descriptor().runtimeName());
    }
    try {
      runHooks(HookDefinition.Phase.PRE_TOOL);
      return new Invocation(arguments, toolExecutionContext);
    } catch (RuntimeException error) {
      throw error;
    }
  }

  private ToolOutput invokeAttempt(Map<String, Object> arguments, ToolExecutionContext context)
      throws Exception {
    Object value;
    try {
      value = tool.handler().invoke(arguments, context);
    } catch (ToolInputException error) {
      throw error;
    } catch (IllegalArgumentException error) {
      throw new ToolInputException(error.getMessage(), error);
    }
    return new ToolOutput(
        value, ToolSchemaCodec.encode(value, tool.descriptor().outputSchema().document()));
  }

  private void runHooks(HookDefinition.Phase phase) {
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

  private static Map<String, Object> arguments(String value) {
    try {
      var node = JSON.readTree(value);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("tool arguments must be a JSON object");
      }
      return JSON.convertValue(node, Map.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("tool arguments must be valid JSON", error);
    }
  }

  static final class ApprovalRequired extends RuntimeException {
    ApprovalRequired(String toolName) {
      super("Tool requires user confirmation: " + toolName);
    }
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("tool schema cannot be serialized", error);
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

  private record Invocation(Map<String, Object> arguments, ToolExecutionContext context) {}

  private record ToolOutput(Object value, String encoded) {}
}
