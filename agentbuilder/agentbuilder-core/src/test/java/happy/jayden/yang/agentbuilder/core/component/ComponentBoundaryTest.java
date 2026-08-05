package happy.jayden.yang.agentbuilder.core.component;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComponentBoundaryTest {

  private static final String SHA = "a".repeat(64);

  @Test
  void agentDraftSynchronizesAgentKeyAndBindingBoundsWithAdminContract() {
    assertDoesNotThrow(() -> draft("Coach Agent", List.of()));
    var tools = new ArrayList<ToolBinding>();
    for (int index = 0; index < 101; index++) {
      tools.add(new ToolBinding(key("tool.t" + index), version(1), true));
    }
    assertThrows(IllegalArgumentException.class, () -> draft("coach.agent", tools));
  }

  @Test
  void publishedBindingsRejectDuplicateIdentityAndConfigPathsBeforeCanonicalSorting() {
    var duplicate = ToolBinding.published("tool.same", 1, true, SHA);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentComponents(
                new FrameworkRef(key("framework.one"), version(1), SHA),
                new ProviderRef(key("provider.one"), version(1), SHA),
                new ModelBinding(key("model.one"), version(1), SHA),
                new PromptRef(key("prompt.one"), version(1), SHA),
                List.of(duplicate, duplicate),
                List.of(),
                List.of(),
                new MemoryPolicyRef(key("memory.one"), version(1), SHA),
                new OutputSchemaRef(key("schema.one"), version(1), SHA),
                new EvaluationSuiteRef(key("evaluation.one"), version(1), SHA),
                new DefaultProfileRef(key("defaults.one"), version(1), SHA)));

    var entries =
        List.of(
            new ConfigEntry("same.path", new StringValue("first")),
            new ConfigEntry("same.path", new StringValue("second")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PublishedSkillBinding(key("skill.one"), version(1), true, SHA, entries));
  }

  @Test
  void arbitraryStringsRejectUnpairedUnicodeSurrogates() {
    assertThrows(IllegalArgumentException.class, () -> new StringValue("bad\ud800value"));
    assertThrows(
        IllegalArgumentException.class, () -> new StringListValue(List.of("bad\udc00value")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolBinding(
                key("tool.one"),
                version(1),
                true,
                Optional.of("bad\ud800value"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StringListValue(java.util.Collections.nCopies(257, "value")));
  }

  @Test
  void publishedBindingCollectionsRejectMoreThanOneHundredItems() {
    var tools = new ArrayList<PublishedToolBinding>();
    for (int index = 0; index < 101; index++) {
      tools.add(ToolBinding.published("tool.p" + index, 1, true, SHA));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentComponents(
                new FrameworkRef(key("framework.one"), version(1), SHA),
                new ProviderRef(key("provider.one"), version(1), SHA),
                new ModelBinding(key("model.one"), version(1), SHA),
                new PromptRef(key("prompt.one"), version(1), SHA),
                tools,
                List.of(),
                List.of(),
                new MemoryPolicyRef(key("memory.one"), version(1), SHA),
                new OutputSchemaRef(key("schema.one"), version(1), SHA),
                new EvaluationSuiteRef(key("evaluation.one"), version(1), SHA),
                new DefaultProfileRef(key("defaults.one"), version(1), SHA)));
  }

  private static AgentDraft draft(String agentKey, List<ToolBinding> tools) {
    var reference = new VersionReference(key("component.one"), version(1));
    return new AgentDraft(
        agentKey,
        "fitness",
        1,
        reference,
        reference,
        reference,
        reference,
        tools,
        List.of(),
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        DefaultValues.empty(),
        Instant.EPOCH);
  }

  private static ComponentKey key(String value) {
    return new ComponentKey(value);
  }

  private static ComponentVersion version(int value) {
    return new ComponentVersion(value);
  }
}
