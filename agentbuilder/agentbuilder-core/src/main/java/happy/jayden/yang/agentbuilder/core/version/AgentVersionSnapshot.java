package happy.jayden.yang.agentbuilder.core.version;

import happy.jayden.yang.agentbuilder.core.component.AgentComponents;
import happy.jayden.yang.agentbuilder.core.component.BooleanValue;
import happy.jayden.yang.agentbuilder.core.component.ConfigEntry;
import happy.jayden.yang.agentbuilder.core.component.ConfigValue;
import happy.jayden.yang.agentbuilder.core.component.NumberValue;
import happy.jayden.yang.agentbuilder.core.component.PublishedComponentRef;
import happy.jayden.yang.agentbuilder.core.component.PublishedHookBinding;
import happy.jayden.yang.agentbuilder.core.component.PublishedSkillBinding;
import happy.jayden.yang.agentbuilder.core.component.PublishedToolBinding;
import happy.jayden.yang.agentbuilder.core.component.StringListValue;
import happy.jayden.yang.agentbuilder.core.component.StringValue;
import happy.jayden.yang.agentbuilder.core.component.VersionedComponent;
import happy.jayden.yang.agentbuilder.core.defaults.EffectiveValueSource;
import happy.jayden.yang.agentbuilder.core.defaults.PublishedResolvedConfig;
import happy.jayden.yang.agentbuilder.core.defaults.PublishedResolvedConfigSources;
import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public record AgentVersionSnapshot(
    PublishedResolvedConfig resolvedConfig,
    AgentComponents components,
    String canonicalJson,
    String checksum) {

  public AgentVersionSnapshot {
    Objects.requireNonNull(resolvedConfig, "resolvedConfig");
    components = immutableCopy(components);
    Objects.requireNonNull(canonicalJson, "canonicalJson");
    Objects.requireNonNull(checksum, "checksum");
  }

  public static AgentVersionSnapshot publish(
      ResolvedAgentConfig resolvedConfig, AgentComponents components) {
    Objects.requireNonNull(resolvedConfig, "resolvedConfig");
    var copiedComponents = immutableCopy(components);
    var publishedConfig = resolvedConfig.publishedConfig();
    var canonicalJson = CanonicalWriter.write(publishedConfig, copiedComponents);
    return new AgentVersionSnapshot(
        publishedConfig, copiedComponents, canonicalJson, sha256(canonicalJson));
  }

  private static AgentComponents immutableCopy(AgentComponents value) {
    Objects.requireNonNull(value, "components");
    return new AgentComponents(
        value.frameworkVersion(),
        value.providerVersion(),
        value.modelBinding(),
        value.promptVersion(),
        value.toolBindings(),
        value.skillBindings(),
        value.hookBindings(),
        value.memoryPolicyVersion(),
        value.outputSchemaVersion(),
        value.evaluationSuiteVersion(),
        value.defaultProfileVersion());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }

  private static final class CanonicalWriter {
    private final StringBuilder json = new StringBuilder();
    private boolean first;

    static String write(PublishedResolvedConfig config, AgentComponents components) {
      var writer = new CanonicalWriter();
      writer.object(
          object -> {
            object.property("components", nested -> nested.components(components));
            object.property("resolvedEffectiveConfig", nested -> nested.resolvedConfig(config));
          });
      return writer.json.toString();
    }

    private void components(AgentComponents value) {
      object(
          object -> {
            object.property(
                "defaultProfileVersion", nested -> nested.component(value.defaultProfileVersion()));
            object.property(
                "evaluationSuiteVersion",
                nested -> nested.component(value.evaluationSuiteVersion()));
            object.property(
                "frameworkVersion", nested -> nested.component(value.frameworkVersion()));
            object.property("hookBindings", nested -> nested.hooks(value.hookBindings()));
            object.property(
                "memoryPolicyVersion", nested -> nested.component(value.memoryPolicyVersion()));
            object.property("modelBinding", nested -> nested.component(value.modelBinding()));
            object.property(
                "outputSchemaVersion", nested -> nested.component(value.outputSchemaVersion()));
            object.property("promptVersion", nested -> nested.component(value.promptVersion()));
            object.property("providerVersion", nested -> nested.component(value.providerVersion()));
            object.property("skillBindings", nested -> nested.skills(value.skillBindings()));
            object.property("toolBindings", nested -> nested.tools(value.toolBindings()));
          });
    }

    private void resolvedConfig(PublishedResolvedConfig value) {
      object(
          object -> {
            object.property("applicationScope", value.applicationScope());
            object.property(
                "modelParameters",
                nested ->
                    nested.object(
                        parameters -> {
                          parameters.property(
                              "maxOutputTokens", value.modelParameters().maxOutputTokens());
                          parameters.property("temperature", value.modelParameters().temperature());
                          parameters.property("topP", value.modelParameters().topP());
                        }));
            object.property("retryPolicy", value.retryPolicy().name());
            object.property(
                "runtimeLimits",
                nested ->
                    nested.object(
                        limits -> {
                          limits.property("concurrentRuns", value.runtimeLimits().concurrentRuns());
                          limits.property("maxCostUsd", value.runtimeLimits().maxCostUsd());
                          limits.property("maxInputTokens", value.runtimeLimits().maxInputTokens());
                          limits.property(
                              "maxOutputTokens", value.runtimeLimits().maxOutputTokens());
                          limits.property("maxRunSeconds", value.runtimeLimits().maxRunSeconds());
                          limits.property("maxToolCalls", value.runtimeLimits().maxToolCalls());
                        }));
            object.property("sources", nested -> nested.sources(value.sources()));
          });
    }

    private void sources(PublishedResolvedConfigSources value) {
      object(
          object -> {
            object.property("defaultProfile", nested -> nested.source(value.defaultProfile()));
            object.property("memoryPolicy", nested -> nested.source(value.memoryPolicy()));
            object.property(
                "modelMaxOutputTokens", nested -> nested.source(value.modelMaxOutputTokens()));
            object.property("modelTemperature", nested -> nested.source(value.modelTemperature()));
            object.property("modelTopP", nested -> nested.source(value.modelTopP()));
            object.property("outputSchema", nested -> nested.source(value.outputSchema()));
            object.property("retryPolicy", nested -> nested.source(value.retryPolicy()));
            object.property(
                "runtimeConcurrentRuns", nested -> nested.source(value.runtimeConcurrentRuns()));
            object.property(
                "runtimeMaxCostUsd", nested -> nested.source(value.runtimeMaxCostUsd()));
            object.property(
                "runtimeMaxInputTokens", nested -> nested.source(value.runtimeMaxInputTokens()));
            object.property(
                "runtimeMaxOutputTokens", nested -> nested.source(value.runtimeMaxOutputTokens()));
            object.property(
                "runtimeMaxRunSeconds", nested -> nested.source(value.runtimeMaxRunSeconds()));
            object.property(
                "runtimeMaxToolCalls", nested -> nested.source(value.runtimeMaxToolCalls()));
          });
    }

    private void source(EffectiveValueSource value) {
      object(
          object -> {
            object.property("source", value.source().name());
            value
                .sourceVersion()
                .ifPresent(
                    version ->
                        object.property("sourceVersion", nested -> nested.publishedRef(version)));
          });
    }

    private void component(VersionedComponent value) {
      publishedRef(value.publishedRef());
    }

    private void publishedRef(PublishedComponentRef value) {
      object(
          object -> {
            object.property("componentChecksum", value.componentChecksum());
            object.property("componentKey", value.componentKey().value());
            object.property("version", value.version().value());
          });
    }

    private void tools(List<PublishedToolBinding> bindings) {
      var sorted = new ArrayList<>(bindings);
      sorted.sort(
          Comparator.comparing((PublishedToolBinding item) -> item.toolKey().value())
              .thenComparingInt(item -> item.contractVersion().value()));
      array(
          sorted,
          binding ->
              object(
                  object -> {
                    binding
                        .approvalPolicy()
                        .ifPresent(policy -> object.property("approvalPolicy", policy.name()));
                    object.property("componentChecksum", binding.componentChecksum());
                    object.property("contractVersion", binding.contractVersion().value());
                    object.property("enabled", binding.enabled());
                    binding
                        .maxCallsPerRun()
                        .ifPresent(maximum -> object.property("maxCallsPerRun", maximum));
                    binding
                        .resultMode()
                        .ifPresent(mode -> object.property("resultMode", mode.name()));
                    binding
                        .retryPolicy()
                        .ifPresent(policy -> object.property("retryPolicy", policy.name()));
                    binding.timeoutMs().ifPresent(timeout -> object.property("timeoutMs", timeout));
                    object.property("toolKey", binding.toolKey().value());
                    binding
                        .usageGuidance()
                        .ifPresent(guidance -> object.property("usageGuidance", guidance));
                  }));
    }

    private void skills(List<PublishedSkillBinding> bindings) {
      var sorted = new ArrayList<>(bindings);
      sorted.sort(
          Comparator.comparing((PublishedSkillBinding item) -> item.skillKey().value())
              .thenComparingInt(item -> item.version().value()));
      array(
          sorted,
          binding ->
              object(
                  object -> {
                    object.property(
                        "applicationConfig",
                        nested -> nested.configEntries(binding.applicationConfig()));
                    object.property("componentChecksum", binding.componentChecksum());
                    object.property("enabled", binding.enabled());
                    object.property("skillKey", binding.skillKey().value());
                    object.property("version", binding.version().value());
                  }));
    }

    private void hooks(List<PublishedHookBinding> bindings) {
      var sorted = new ArrayList<>(bindings);
      sorted.sort(
          Comparator.comparing((PublishedHookBinding item) -> item.hookKey().value())
              .thenComparingInt(item -> item.version().value()));
      array(
          sorted,
          binding ->
              object(
                  object -> {
                    object.property("componentChecksum", binding.componentChecksum());
                    object.property("config", nested -> nested.configEntries(binding.config()));
                    object.property("enabled", binding.enabled());
                    object.property("hookKey", binding.hookKey().value());
                    object.property("version", binding.version().value());
                  }));
    }

    private void configEntries(List<ConfigEntry> entries) {
      var sorted = new ArrayList<>(entries);
      sorted.sort(Comparator.naturalOrder());
      array(
          sorted,
          entry ->
              object(
                  object -> {
                    object.property("path", entry.path());
                    object.property("value", nested -> nested.configValue(entry.value()));
                  }));
    }

    private void configValue(ConfigValue value) {
      if (value instanceof StringValue stringValue) {
        string(stringValue.value());
      } else if (value instanceof NumberValue numberValue) {
        number(numberValue.value());
      } else if (value instanceof BooleanValue booleanValue) {
        json.append(booleanValue.value());
      } else if (value instanceof StringListValue stringListValue) {
        array(stringListValue.value(), this::string);
      } else {
        throw new IllegalArgumentException("unsupported config value: " + value);
      }
    }

    private void object(Consumer<CanonicalWriter> properties) {
      json.append('{');
      boolean outerFirst = first;
      first = true;
      properties.accept(this);
      first = outerFirst;
      json.append('}');
    }

    private <T> void array(List<T> values, Consumer<T> itemWriter) {
      json.append('[');
      for (int index = 0; index < values.size(); index++) {
        if (index > 0) {
          json.append(',');
        }
        itemWriter.accept(values.get(index));
      }
      json.append(']');
    }

    private void property(String name, Consumer<CanonicalWriter> valueWriter) {
      separator();
      string(name);
      json.append(':');
      valueWriter.accept(this);
    }

    private void property(String name, Object value) {
      property(name, writer -> writer.scalar(value));
    }

    private void scalar(Object value) {
      if (value == null) {
        json.append("null");
      } else if (value instanceof String string) {
        string(string);
      } else if (value instanceof BigDecimal decimal) {
        number(decimal);
      } else if (value instanceof Number || value instanceof Boolean) {
        json.append(value);
      } else {
        throw new IllegalArgumentException("unsupported scalar: " + value);
      }
    }

    private void number(BigDecimal value) {
      var normalized = value.stripTrailingZeros();
      json.append(normalized.signum() == 0 ? "0" : normalized.toPlainString());
    }

    private void separator() {
      if (first) {
        first = false;
      } else {
        json.append(',');
      }
    }

    private void string(String value) {
      json.append('"');
      for (int index = 0; index < value.length(); index++) {
        char character = value.charAt(index);
        switch (character) {
          case '"' -> json.append("\\\"");
          case '\\' -> json.append("\\\\");
          case '\b' -> json.append("\\b");
          case '\f' -> json.append("\\f");
          case '\n' -> json.append("\\n");
          case '\r' -> json.append("\\r");
          case '\t' -> json.append("\\t");
          default -> {
            if (character < 0x20) {
              json.append(String.format("\\u%04x", (int) character));
            } else {
              json.append(character);
            }
          }
        }
      }
      json.append('"');
    }
  }
}
