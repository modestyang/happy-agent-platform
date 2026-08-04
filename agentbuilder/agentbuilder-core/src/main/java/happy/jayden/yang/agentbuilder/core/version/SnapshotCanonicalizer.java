package happy.jayden.yang.agentbuilder.core.version;

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
import happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentDefinition;
import happy.jayden.yang.agentbuilder.core.defaults.ResolvedBindingSource;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SnapshotCanonicalizer {
  private SnapshotCanonicalizer() {}

  public static String canonicalize(ResolvedAgentDefinition definition) {
    var root = new LinkedHashMap<String, Object>();
    root.put("componentSources", componentSources(definition));
    root.put("components", components(definition));
    root.put("resolvedEffectiveConfig", resolvedConfig(definition));
    var json = new StringBuilder();
    write(root, json);
    return json.toString();
  }

  private static Map<String, Object> components(ResolvedAgentDefinition definition) {
    var value = definition.components();
    var result = new LinkedHashMap<String, Object>();
    result.put("defaultProfileVersion", component(value.defaultProfileVersion()));
    result.put("evaluationSuiteVersion", component(value.evaluationSuiteVersion()));
    result.put("frameworkVersion", component(value.frameworkVersion()));
    result.put(
        "hookBindings",
        value.hookBindings().stream()
            .sorted(
                Comparator.comparing((PublishedHookBinding item) -> item.hookKey().value())
                    .thenComparingInt(item -> item.version().value()))
            .map(SnapshotCanonicalizer::hook)
            .toList());
    result.put("memoryPolicyVersion", component(value.memoryPolicyVersion()));
    result.put("modelBinding", component(value.modelBinding()));
    result.put("outputSchemaVersion", component(value.outputSchemaVersion()));
    result.put("promptVersion", component(value.promptVersion()));
    result.put("providerVersion", component(value.providerVersion()));
    result.put(
        "skillBindings",
        value.skillBindings().stream()
            .sorted(
                Comparator.comparing((PublishedSkillBinding item) -> item.skillKey().value())
                    .thenComparingInt(item -> item.version().value()))
            .map(SnapshotCanonicalizer::skill)
            .toList());
    result.put(
        "toolBindings",
        value.toolBindings().stream()
            .sorted(
                Comparator.comparing((PublishedToolBinding item) -> item.toolKey().value())
                    .thenComparingInt(item -> item.contractVersion().value()))
            .map(SnapshotCanonicalizer::tool)
            .toList());
    return result;
  }

  private static Map<String, Object> componentSources(ResolvedAgentDefinition definition) {
    var value = definition.sources();
    var result = new LinkedHashMap<String, Object>();
    result.put("defaultProfileVersion", source(value.defaultProfileVersion()));
    result.put("evaluationSuiteVersion", source(value.evaluationSuiteVersion()));
    result.put("frameworkVersion", source(value.frameworkVersion()));
    result.put("hookBindings", bindingSources(value.hookBindings()));
    result.put("memoryPolicyVersion", source(value.memoryPolicyVersion()));
    result.put("modelBinding", source(value.modelBinding()));
    result.put("outputSchemaVersion", source(value.outputSchemaVersion()));
    result.put("promptVersion", source(value.promptVersion()));
    result.put("providerVersion", source(value.providerVersion()));
    result.put("skillBindings", bindingSources(value.skillBindings()));
    result.put("toolBindings", bindingSources(value.toolBindings()));
    return result;
  }

  private static List<Object> bindingSources(List<ResolvedBindingSource> values) {
    return values.stream()
        .sorted(
            Comparator.comparing((ResolvedBindingSource item) -> item.componentKey().value())
                .thenComparingInt(item -> item.version().value()))
        .map(
            item -> {
              var result = new LinkedHashMap<String, Object>();
              result.put("componentKey", item.componentKey().value());
              result.put("source", source(item.source()));
              result.put("version", item.version().value());
              return (Object) result;
            })
        .toList();
  }

  private static Map<String, Object> resolvedConfig(ResolvedAgentDefinition definition) {
    var value = definition.resolvedConfig();
    var model = new LinkedHashMap<String, Object>();
    model.put("maxOutputTokens", value.modelParameters().maxOutputTokens());
    model.put("temperature", value.modelParameters().temperature());
    model.put("topP", value.modelParameters().topP());
    var limits = new LinkedHashMap<String, Object>();
    limits.put("concurrentRuns", value.runtimeLimits().concurrentRuns());
    limits.put("maxCostUsd", value.runtimeLimits().maxCostUsd());
    limits.put("maxInputTokens", value.runtimeLimits().maxInputTokens());
    limits.put("maxOutputTokens", value.runtimeLimits().maxOutputTokens());
    limits.put("maxRunSeconds", value.runtimeLimits().maxRunSeconds());
    limits.put("maxToolCalls", value.runtimeLimits().maxToolCalls());
    var sources = new LinkedHashMap<String, Object>();
    sources.put("defaultProfile", source(value.sources().defaultProfile()));
    sources.put("memoryPolicy", source(value.sources().memoryPolicy()));
    sources.put("modelMaxOutputTokens", source(value.sources().modelMaxOutputTokens()));
    sources.put("modelTemperature", source(value.sources().modelTemperature()));
    sources.put("modelTopP", source(value.sources().modelTopP()));
    sources.put("outputSchema", source(value.sources().outputSchema()));
    sources.put("retryPolicy", source(value.sources().retryPolicy()));
    sources.put("runtimeConcurrentRuns", source(value.sources().runtimeConcurrentRuns()));
    sources.put("runtimeMaxCostUsd", source(value.sources().runtimeMaxCostUsd()));
    sources.put("runtimeMaxInputTokens", source(value.sources().runtimeMaxInputTokens()));
    sources.put("runtimeMaxOutputTokens", source(value.sources().runtimeMaxOutputTokens()));
    sources.put("runtimeMaxRunSeconds", source(value.sources().runtimeMaxRunSeconds()));
    sources.put("runtimeMaxToolCalls", source(value.sources().runtimeMaxToolCalls()));
    var result = new LinkedHashMap<String, Object>();
    result.put("applicationScope", value.applicationScope());
    result.put("modelParameters", model);
    result.put("retryPolicy", value.retryPolicy().name());
    result.put("runtimeLimits", limits);
    result.put("sources", sources);
    return result;
  }

  private static Map<String, Object> component(VersionedComponent value) {
    return publishedRef(value.publishedRef());
  }

  private static Map<String, Object> publishedRef(PublishedComponentRef value) {
    var result = new LinkedHashMap<String, Object>();
    result.put("componentChecksum", value.componentChecksum());
    result.put("componentKey", value.componentKey().value());
    result.put("version", value.version().value());
    return result;
  }

  private static Map<String, Object> source(EffectiveValueSource value) {
    var result = new LinkedHashMap<String, Object>();
    result.put("source", value.source().name());
    value.sourceVersion().ifPresent(version -> result.put("sourceVersion", publishedRef(version)));
    return result;
  }

  private static Map<String, Object> tool(PublishedToolBinding value) {
    var result = new LinkedHashMap<String, Object>();
    value.approvalPolicy().ifPresent(item -> result.put("approvalPolicy", item.name()));
    result.put("componentChecksum", value.componentChecksum());
    result.put("contractVersion", value.contractVersion().value());
    result.put("enabled", value.enabled());
    value.maxCallsPerRun().ifPresent(item -> result.put("maxCallsPerRun", item));
    value.resultMode().ifPresent(item -> result.put("resultMode", item.name()));
    value.retryPolicy().ifPresent(item -> result.put("retryPolicy", item.name()));
    value.timeoutMs().ifPresent(item -> result.put("timeoutMs", item));
    result.put("toolKey", value.toolKey().value());
    value.usageGuidance().ifPresent(item -> result.put("usageGuidance", item));
    return result;
  }

  private static Map<String, Object> skill(PublishedSkillBinding value) {
    var result = new LinkedHashMap<String, Object>();
    result.put("applicationConfig", configEntries(value.applicationConfig()));
    result.put("componentChecksum", value.componentChecksum());
    result.put("enabled", value.enabled());
    result.put("skillKey", value.skillKey().value());
    result.put("version", value.version().value());
    return result;
  }

  private static Map<String, Object> hook(PublishedHookBinding value) {
    var result = new LinkedHashMap<String, Object>();
    result.put("componentChecksum", value.componentChecksum());
    result.put("config", configEntries(value.config()));
    result.put("enabled", value.enabled());
    result.put("hookKey", value.hookKey().value());
    result.put("version", value.version().value());
    return result;
  }

  private static List<Object> configEntries(List<ConfigEntry> values) {
    return values.stream()
        .sorted()
        .map(
            item -> {
              var result = new LinkedHashMap<String, Object>();
              result.put("path", item.path());
              result.put("value", configValue(item.value()));
              return (Object) result;
            })
        .toList();
  }

  private static Object configValue(ConfigValue value) {
    if (value instanceof StringValue stringValue) {
      return stringValue.value();
    }
    if (value instanceof NumberValue numberValue) {
      return numberValue.value();
    }
    if (value instanceof BooleanValue booleanValue) {
      return booleanValue.value();
    }
    if (value instanceof StringListValue stringListValue) {
      return stringListValue.value();
    }
    throw new IllegalArgumentException("unsupported config value: " + value);
  }

  private static void write(Object value, StringBuilder json) {
    if (value instanceof Map<?, ?> map) {
      json.append('{');
      var sorted = new TreeMap<String, Object>();
      map.forEach((key, item) -> sorted.put((String) key, item));
      var first = true;
      for (var entry : sorted.entrySet()) {
        if (!first) {
          json.append(',');
        }
        first = false;
        writeString(entry.getKey(), json);
        json.append(':');
        write(entry.getValue(), json);
      }
      json.append('}');
      return;
    }
    if (value instanceof List<?> list) {
      json.append('[');
      for (int index = 0; index < list.size(); index++) {
        if (index > 0) {
          json.append(',');
        }
        write(list.get(index), json);
      }
      json.append(']');
      return;
    }
    if (value instanceof String string) {
      writeString(string, json);
      return;
    }
    if (value instanceof BigDecimal decimal) {
      var normalized = decimal.stripTrailingZeros();
      json.append(normalized.signum() == 0 ? "0" : normalized.toPlainString());
      return;
    }
    if (value instanceof Number || value instanceof Boolean) {
      json.append(value);
      return;
    }
    throw new IllegalArgumentException("unsupported canonical value: " + value);
  }

  private static void writeString(String value, StringBuilder json) {
    SnapshotChecksum.sha256(value);
    json.append('"');
    value
        .codePoints()
        .forEach(
            codePoint -> {
              switch (codePoint) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                  if (codePoint < 0x20) {
                    json.append(String.format("\\u%04x", codePoint));
                  } else {
                    json.appendCodePoint(codePoint);
                  }
                }
              }
            });
    json.append('"');
  }
}
