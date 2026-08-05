package happy.jayden.yang.agentbuilder.core.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentDraftContractTest {

  @Test
  void agentDraftUsesFrozenFieldNamesAndDefensivelyCopiesBindingReplacements() {
    var tools = new ArrayList<ToolBinding>();
    var skills = new ArrayList<SkillBinding>();
    var hooks = new ArrayList<HookBinding>();
    var framework = reference("framework.agentscope", 1);
    var draft =
        new AgentDraft(
            "coach.agent",
            "fitness",
            3,
            framework,
            reference("provider.openai", 2),
            reference("model.gpt", 4),
            reference("prompt.coach", 5),
            tools,
            skills,
            hooks,
            Optional.of(reference("memory.window", 1)),
            Optional.of(reference("schema.answer", 1)),
            Optional.of(reference("evaluation.safety", 1)),
            Optional.of(reference("defaults.fitness", 7)),
            DefaultValues.empty(),
            Instant.parse("2026-08-05T00:00:00Z"));

    tools.add(new ToolBinding(new ComponentKey("tool.write"), new ComponentVersion(1), true));

    assertEquals(framework, draft.frameworkVersion());
    assertEquals(0, draft.toolBindings().size());
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            draft
                .skillBindings()
                .add(
                    new SkillBinding(
                        new ComponentKey("skill.plan"), new ComponentVersion(1), true)));
  }

  private static VersionReference reference(String key, int version) {
    return new VersionReference(new ComponentKey(key), new ComponentVersion(version));
  }
}
