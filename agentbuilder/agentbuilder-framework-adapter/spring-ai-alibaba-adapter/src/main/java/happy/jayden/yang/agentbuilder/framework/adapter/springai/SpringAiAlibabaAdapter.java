package happy.jayden.yang.agentbuilder.framework.adapter.springai;

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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

/** Spring AI Alibaba runtime implementation; the SPI surface remains entirely framework-neutral. */
public final class SpringAiAlibabaAdapter implements AgentFrameworkAdapter {
  private static final FrameworkCapabilities CAPABILITIES =
      new FrameworkCapabilities(true, true, true, true, true, true);

  private final RuntimeFactory runtimeFactory;

  public SpringAiAlibabaAdapter() {
    this(SpringAiAlibabaRuntimeBridge::new);
  }

  SpringAiAlibabaAdapter(RuntimeFactory runtimeFactory) {
    this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
  }

  @Override
  public String key() {
    return "spring-ai-alibaba";
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
          var bridge = new AtomicReference<SpringAiAlibabaRuntimeBridge>();
          return Flux.concat(
                  Flux.just(
                      event(sequence, RunEvent.Type.RUN_STARTED, Map.of("runId", request.runId()))),
                  Flux.defer(
                      () -> {
                        var runtime = runtimeFactory.create(request, sequence);
                        bridge.set(runtime);
                        return runtime.events();
                      }))
              .timeout(Duration.ofSeconds(request.resolvedConfig().runtimeLimits().maxRunSeconds()))
              .onErrorResume(
                  error ->
                      Flux.just(
                          event(
                              sequence,
                              RunEvent.Type.RUN_FAILED,
                              Map.of(
                                  "result",
                                  RunResult.failed(SpringAiAlibabaFailureMapper.map(error))))))
              .doFinally(
                  ignored -> {
                    var runtime = bridge.get();
                    if (runtime != null) {
                      runtime.cancel();
                    }
                  });
        });
  }

  static RunEvent event(AtomicLong sequence, RunEvent.Type type, Map<String, Object> data) {
    return new RunEvent(sequence.incrementAndGet(), type, Instant.now(), data);
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

  @FunctionalInterface
  interface RuntimeFactory {
    SpringAiAlibabaRuntimeBridge create(RunRequest request, AtomicLong sequence);
  }

  private static final class SpringAiAlibabaFailureMapper {
    private SpringAiAlibabaFailureMapper() {}

    static RunFailure map(Throwable error) {
      if (find(error, TimeoutException.class) != null) {
        return new RunFailure(RunFailureCode.TIMEOUT, "Agent run timed out", true);
      }
      if (find(error, ToolFailure.class) != null) {
        return new RunFailure(RunFailureCode.TOOL, message(error), false);
      }
      if (find(error, HookFailure.class) != null) {
        return new RunFailure(RunFailureCode.HOOK, message(error), false);
      }
      var webClientResponse =
          find(
              error,
              org.springframework.web.reactive.function.client.WebClientResponseException.class);
      if (webClientResponse != null) {
        return new RunFailure(
            RunFailureCode.MODEL,
            "Model provider HTTP " + webClientResponse.getStatusCode().value(),
            webClientResponse.getStatusCode().is5xxServerError()
                || webClientResponse.getStatusCode().value() == 429);
      }
      var restClientResponse =
          find(error, org.springframework.web.client.HttpStatusCodeException.class);
      if (restClientResponse != null) {
        return new RunFailure(
            RunFailureCode.MODEL,
            "Model provider HTTP " + restClientResponse.getStatusCode().value(),
            restClientResponse.getStatusCode().is5xxServerError()
                || restClientResponse.getStatusCode().value() == 429);
      }
      if (find(
                  error,
                  org.springframework.web.reactive.function.client.WebClientRequestException.class)
              != null
          || find(error, org.springframework.web.client.ResourceAccessException.class) != null) {
        return new RunFailure(RunFailureCode.MODEL, "Model provider network request failed", true);
      }
      var cause = unwrap(error);
      if (cause instanceof IllegalArgumentException) {
        return new RunFailure(RunFailureCode.VALIDATION, message(cause), false);
      }
      var name = cause.getClass().getName().toLowerCase(Locale.ROOT);
      if (name.contains("model") || name.contains("http") || name.contains("openai")) {
        return new RunFailure(RunFailureCode.MODEL, "Model execution failed", true);
      }
      return new RunFailure(RunFailureCode.INTERNAL, "Framework execution failed", false);
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
      for (var current = error; current != null; current = current.getCause()) {
        if (type.isInstance(current)) {
          return type.cast(current);
        }
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

    private static String message(Throwable error) {
      return error.getMessage() == null || error.getMessage().isBlank()
          ? "Framework execution failed"
          : error.getMessage();
    }
  }
}
