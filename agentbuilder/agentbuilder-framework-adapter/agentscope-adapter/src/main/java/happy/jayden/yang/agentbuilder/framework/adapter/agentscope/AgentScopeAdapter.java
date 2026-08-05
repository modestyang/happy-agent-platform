package happy.jayden.yang.agentbuilder.framework.adapter.agentscope;

import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig;
import happy.jayden.yang.agentbuilder.core.runtime.AgentFrameworkAdapter;
import happy.jayden.yang.agentbuilder.core.runtime.FrameworkCapabilities;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import happy.jayden.yang.agentbuilder.core.runtime.RunFailure;
import happy.jayden.yang.agentbuilder.core.runtime.RunFailureCode;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.runtime.RunResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Production AgentScope Java adapter exposed only through framework-neutral core contracts. */
public final class AgentScopeAdapter implements AgentFrameworkAdapter {
  private static final FrameworkCapabilities CAPABILITIES =
      new FrameworkCapabilities(true, true, true, true, true, true);

  private final Function<RunRequest.ModelEndpoint, AgentScopeModelTransport> modelFactory;

  public AgentScopeAdapter() {
    this(OpenAiAgentScopeModelTransport::new);
  }

  AgentScopeAdapter(AgentScopeModelTransport modelTransport) {
    this(ignored -> modelTransport);
  }

  AgentScopeAdapter(Function<RunRequest.ModelEndpoint, AgentScopeModelTransport> modelFactory) {
    this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
  }

  @Override
  public String key() {
    return "agentscope";
  }

  @Override
  public FrameworkCapabilities capabilities() {
    return CAPABILITIES;
  }

  @Override
  public void validate(ResolvedAgentConfig resolvedAgentConfig) {
    Objects.requireNonNull(resolvedAgentConfig, "resolvedAgentConfig");
    if (resolvedAgentConfig.runtimeLimits().maxToolCalls() < 0) {
      throw new IllegalArgumentException("maxToolCalls must not be negative");
    }
  }

  @Override
  public Flux<RunEvent> run(RunRequest request) {
    Objects.requireNonNull(request, "request");
    validate(request.resolvedConfig());
    return Flux.defer(
        () -> {
          var sequence = new AtomicLong();
          var bridgeReference = new AtomicReference<AgentScopeRuntimeBridge>();
          return Flux.defer(
                  () -> {
                    var started =
                        event(
                            sequence, RunEvent.Type.RUN_STARTED, Map.of("runId", request.runId()));
                    return Flux.concat(
                        Flux.just(started),
                        Flux.defer(
                            () -> {
                              var bridge =
                                  new AgentScopeRuntimeBridge(
                                      request, modelFactory.apply(request.model()));
                              bridgeReference.set(bridge);
                              return bridge.events().map(signal -> signal.toEvent(sequence));
                            }));
                  })
              .timeout(Duration.ofSeconds(request.resolvedConfig().runtimeLimits().maxRunSeconds()))
              .onErrorResume(
                  error -> {
                    var bridge = bridgeReference.get();
                    if (bridge != null) {
                      bridge.interrupt();
                    }
                    var failure = AgentScopeFailureMapper.map(error);
                    return Flux.just(
                        event(
                            sequence,
                            RunEvent.Type.RUN_FAILED,
                            Map.of("result", RunResult.failed(failure))));
                  })
              .doFinally(
                  ignored -> {
                    var bridge = bridgeReference.get();
                    if (bridge != null) {
                      bridge.close();
                    }
                  });
        });
  }

  private static RunEvent event(AtomicLong sequence, RunEvent.Type type, Map<String, Object> data) {
    return new RunEvent(sequence.incrementAndGet(), type, Instant.now(), data);
  }

  static final class Signal {
    private final RunEvent.Type type;
    private final Map<String, Object> data;

    Signal(RunEvent.Type type, Map<String, Object> data) {
      this.type = Objects.requireNonNull(type, "type");
      this.data = Map.copyOf(Objects.requireNonNull(data, "data"));
    }

    RunEvent toEvent(AtomicLong sequence) {
      return event(sequence, type, data);
    }
  }

  private static final class AgentScopeFailureMapper {
    private AgentScopeFailureMapper() {}

    static RunFailure map(Throwable error) {
      if (find(error, java.util.concurrent.TimeoutException.class) != null) {
        return new RunFailure(RunFailureCode.TIMEOUT, "Agent run timed out", true);
      }
      if (find(error, AgentScopeRuntimeBridge.ToolFailure.class) != null) {
        return new RunFailure(RunFailureCode.TOOL, safeMessage(error), false);
      }
      if (find(error, AgentScopeRuntimeBridge.HookFailure.class) != null) {
        return new RunFailure(RunFailureCode.HOOK, safeMessage(error), false);
      }
      var cause = unwrap(error);
      if (cause instanceof java.util.concurrent.TimeoutException) {
        return new RunFailure(RunFailureCode.TIMEOUT, "Agent run timed out", true);
      }
      if (cause instanceof IllegalArgumentException) {
        return new RunFailure(RunFailureCode.VALIDATION, safeMessage(cause), false);
      }
      var name = cause.getClass().getName().toLowerCase(java.util.Locale.ROOT);
      if (name.contains("model") || name.contains("http") || name.contains("openai")) {
        return new RunFailure(RunFailureCode.MODEL, "Model execution failed", true);
      }
      return new RunFailure(RunFailureCode.INTERNAL, "Framework execution failed", false);
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
      var current = error;
      while (current != null) {
        if (type.isInstance(current)) {
          return type.cast(current);
        }
        current = current.getCause();
      }
      return null;
    }

    private static Throwable unwrap(Throwable error) {
      var current = error;
      while (current.getCause() != null
          && (current instanceof RuntimeException
              || current instanceof java.util.concurrent.ExecutionException)) {
        current = current.getCause();
      }
      return current;
    }

    private static String safeMessage(Throwable error) {
      return error.getMessage() == null || error.getMessage().isBlank()
          ? "Framework execution failed"
          : error.getMessage();
    }
  }
}
