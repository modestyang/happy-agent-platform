package happy.jayden.yang.agentbuilder.core.version;

import static happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentDefinitionTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import happy.jayden.yang.agentbuilder.core.component.AgentComponents;
import happy.jayden.yang.agentbuilder.core.component.ConfigEntry;
import happy.jayden.yang.agentbuilder.core.component.DefaultProfileRef;
import happy.jayden.yang.agentbuilder.core.component.EvaluationSuiteRef;
import happy.jayden.yang.agentbuilder.core.component.FrameworkRef;
import happy.jayden.yang.agentbuilder.core.component.MemoryPolicyRef;
import happy.jayden.yang.agentbuilder.core.component.ModelBinding;
import happy.jayden.yang.agentbuilder.core.component.OutputSchemaRef;
import happy.jayden.yang.agentbuilder.core.component.PromptRef;
import happy.jayden.yang.agentbuilder.core.component.ProviderRef;
import happy.jayden.yang.agentbuilder.core.component.PublishedHookBinding;
import happy.jayden.yang.agentbuilder.core.component.PublishedSkillBinding;
import happy.jayden.yang.agentbuilder.core.component.PublishedToolBinding;
import happy.jayden.yang.agentbuilder.core.component.StringValue;
import happy.jayden.yang.agentbuilder.core.defaults.AgentOverrides;
import happy.jayden.yang.agentbuilder.core.defaults.ApplicationDefaults;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SnapshotChecksumCoverageTest {

  private static final String A = "a".repeat(64);
  private static final String B = "b".repeat(64);

  @ParameterizedTest
  @EnumSource(Mutation.class)
  void everyComponentAndPublishedBindingChecksumParticipatesInSnapshotChecksum(Mutation mutation) {
    var baseline = definition(components(null));
    var changed = definition(components(mutation));
    assertNotEquals(
        AgentVersionSnapshot.publish(baseline).checksum(),
        AgentVersionSnapshot.publish(changed).checksum());
  }

  @Test
  void bindingAndConfigInputOrderDoesNotChangeCanonicalJson() {
    var first = definition(ordered(false));
    var second = definition(ordered(true));
    assertEquals(
        AgentVersionSnapshot.publish(first).canonicalJson(),
        AgentVersionSnapshot.publish(second).canonicalJson());
  }

  private static happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentDefinition definition(
      AgentComponents value) {
    var application =
        new ApplicationDefaults(
            "fitness", value.defaultProfileVersion().publishedRef(), DefaultValues.empty());
    return resolver()
        .resolveDefinition(limits(), codeDefaults(), application, AgentOverrides.none(), value);
  }

  private static AgentComponents components(Mutation mutation) {
    return new AgentComponents(
        new FrameworkRef(
            key("framework.agentscope"), version(1), checksum(mutation, Mutation.FRAMEWORK)),
        new ProviderRef(key("provider.openai"), version(1), checksum(mutation, Mutation.PROVIDER)),
        new ModelBinding(key("model.gpt"), version(1), checksum(mutation, Mutation.MODEL)),
        new PromptRef(key("prompt.coach"), version(1), checksum(mutation, Mutation.PROMPT)),
        List.of(
            new PublishedToolBinding(
                key("tool.one"),
                version(1),
                true,
                checksum(mutation, Mutation.TOOL),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty())),
        List.of(
            new PublishedSkillBinding(
                key("skill.one"), version(1), true, checksum(mutation, Mutation.SKILL), List.of())),
        List.of(
            new PublishedHookBinding(
                key("hook.one"), version(1), true, checksum(mutation, Mutation.HOOK), List.of())),
        new MemoryPolicyRef(key("memory.one"), version(1), checksum(mutation, Mutation.MEMORY)),
        new OutputSchemaRef(key("schema.one"), version(1), checksum(mutation, Mutation.OUTPUT)),
        new EvaluationSuiteRef(
            key("evaluation.one"), version(1), checksum(mutation, Mutation.EVALUATION)),
        new DefaultProfileRef(
            key("defaults.one"), version(1), checksum(mutation, Mutation.DEFAULT_PROFILE)));
  }

  private static String checksum(Mutation actual, Mutation target) {
    return actual == target ? B : A;
  }

  private static AgentComponents ordered(boolean reverse) {
    var first = new ConfigEntry("a.path", new StringValue("a"));
    var second = new ConfigEntry("z.path", new StringValue("z"));
    var configs = reverse ? List.of(second, first) : List.of(first, second);
    var tools =
        List.of(
            happy.jayden.yang.agentbuilder.core.component.ToolBinding.published(
                "tool.a", 1, true, A),
            happy.jayden.yang.agentbuilder.core.component.ToolBinding.published(
                "tool.z", 1, true, A));
    var skills =
        List.of(
            new PublishedSkillBinding(key("skill.a"), version(1), true, A, configs),
            new PublishedSkillBinding(key("skill.z"), version(1), true, A, List.of()));
    var hooks =
        List.of(
            new PublishedHookBinding(key("hook.a"), version(1), true, A, configs),
            new PublishedHookBinding(key("hook.z"), version(1), true, A, List.of()));
    return new AgentComponents(
        new FrameworkRef(key("framework.agentscope"), version(1), A),
        new ProviderRef(key("provider.openai"), version(1), A),
        new ModelBinding(key("model.gpt"), version(1), A),
        new PromptRef(key("prompt.coach"), version(1), A),
        reverse ? List.of(tools.get(1), tools.get(0)) : tools,
        reverse ? List.of(skills.get(1), skills.get(0)) : skills,
        reverse ? List.of(hooks.get(1), hooks.get(0)) : hooks,
        new MemoryPolicyRef(key("memory.one"), version(1), A),
        new OutputSchemaRef(key("schema.one"), version(1), A),
        new EvaluationSuiteRef(key("evaluation.one"), version(1), A),
        new DefaultProfileRef(key("defaults.one"), version(1), A));
  }

  enum Mutation {
    FRAMEWORK,
    PROVIDER,
    MODEL,
    PROMPT,
    TOOL,
    SKILL,
    HOOK,
    MEMORY,
    OUTPUT,
    EVALUATION,
    DEFAULT_PROFILE
  }
}
