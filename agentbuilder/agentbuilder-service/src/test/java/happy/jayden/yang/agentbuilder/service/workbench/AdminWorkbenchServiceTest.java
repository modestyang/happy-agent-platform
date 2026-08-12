package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.*;
import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.runtime.RuntimeCapabilityRegistry;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminWorkbenchServiceTest {

  @Test
  void validationBlocksAnUnconfiguredProviderAndUnavailableBinding() {
    var port = new MemoryPort(draft(), false, "DRAFT");
    var service = new AdminWorkbenchService(port, allRuntimeCapabilities());

    var validation = service.validate("fitness.coach");

    assertFalse(validation.valid());
    assertTrue(validation.errors().contains("Provider 尚未配置 API Key"));
    assertTrue(validation.errors().contains("组件 fitness.exercise.search 当前不可用"));
  }

  @Test
  void publishDelegatesOnlyAfterValidationPasses() {
    var port = new MemoryPort(draft(), true, "AVAILABLE");
    var service = new AdminWorkbenchService(port, allRuntimeCapabilities());

    var publication = service.publish("fitness.coach");

    assertEquals(1, publication.publishedVersion());
    assertEquals(1, port.publishedDrafts.size());
  }

  @Test
  void publishNeverWritesWhenValidationFails() {
    var port = new MemoryPort(draft(), false, "AVAILABLE");
    var service = new AdminWorkbenchService(port, allRuntimeCapabilities());

    var failure =
        assertThrows(
            AdminWorkbenchPort.ValidationFailure.class, () -> service.publish("fitness.coach"));

    assertTrue(failure.validation().errors().contains("Provider 尚未配置 API Key"));
    assertTrue(port.publishedDrafts.isEmpty());
  }

  @Test
  void draftUpdatesPreserveOptimisticRevisionContract() {
    var port = new MemoryPort(draft(), true, "AVAILABLE");
    var service = new AdminWorkbenchService(port, allRuntimeCapabilities());
    var update =
        new DraftUpdate(
            "更温柔的花爷",
            "陪用户完成日常训练",
            "agentscope",
            "bailian",
            "qwen-plus",
            "fitness.coach.prompt",
            List.of("fitness.exercise.search"),
            List.of("fitness.plan.skill"),
            List.of("fitness.safety"),
            "fitness.daily-memory",
            0.4,
            6);

    var updated = service.updateDraft("fitness.coach", update, 1);

    assertEquals("更温柔的花爷", updated.name());
    assertEquals(2, updated.revision());
  }

  @Test
  void validationBlocksAvailableSkillsAndHooksWithoutMatchingRuntimeHandlers() {
    var port = new MemoryPort(draft(), true, "AVAILABLE");
    var service = new AdminWorkbenchService(port, (type, key) -> false);

    var validation = service.validate("fitness.coach");

    assertFalse(validation.valid());
    assertTrue(validation.errors().contains("组件 fitness.plan.skill 没有已注册的运行时 handler"));
    assertTrue(validation.errors().contains("组件 fitness.safety 没有已注册的运行时 handler"));
    assertThrows(
        AdminWorkbenchPort.ValidationFailure.class, () -> service.publish("fitness.coach"));
    assertTrue(port.publishedDrafts.isEmpty());
  }

  @Test
  void validationAllowsAGenericAgentToPublishWithoutAFitnessSpecificHook() {
    var withoutSafety =
        new AgentDraftView(
            "fitness.coach",
            "花爷健身教练",
            "根据真实健身数据提供陪伴与建议",
            "DRAFT",
            "agentscope",
            "bailian",
            "qwen-plus",
            "fitness.coach.prompt",
            List.of("fitness.exercise.search"),
            List.of("fitness.plan.skill"),
            List.of(),
            "fitness.daily-memory",
            0.5,
            8,
            0,
            1,
            Instant.parse("2026-08-06T00:00:00Z"));
    var port = new MemoryPort(withoutSafety, true, "AVAILABLE");
    var service = new AdminWorkbenchService(port, allRuntimeCapabilities());

    var validation = service.validate("fitness.coach");

    assertTrue(validation.valid());
    service.publish("fitness.coach");
    assertEquals(1, port.publishedDrafts.size());
  }

  @Test
  void validationRejectsModelWhoseConfiguredProviderDoesNotMatchTheDraft() {
    var port = new MemoryPort(draft(), true, "AVAILABLE", "other-provider");
    var service = new AdminWorkbenchService(port, allRuntimeCapabilities());

    var validation = service.validate("fitness.coach");

    assertFalse(validation.valid());
    assertTrue(validation.errors().contains("模型 qwen-plus 未绑定当前 Provider bailian"));
    assertThrows(
        AdminWorkbenchPort.ValidationFailure.class, () -> service.publish("fitness.coach"));
    assertTrue(port.publishedDrafts.isEmpty());
  }

  @Test
  void validationBlocksASkillWhoseRequiredToolIsNotBoundToTheAgent() {
    var draftWithoutTools =
        new AgentDraftView(
            "fitness.coach",
            "花爷健身教练",
            "根据真实健身数据提供陪伴与建议",
            "DRAFT",
            "agentscope",
            "bailian",
            "qwen-plus",
            "fitness.coach.prompt",
            List.of(),
            List.of("fitness.plan.skill"),
            List.of(),
            "fitness.daily-memory",
            0.5,
            8,
            0,
            1,
            Instant.parse("2026-08-06T00:00:00Z"));
    var planSkill =
        new SkillDefinition(
            "fitness.plan.skill",
            "训练计划编排",
            "使用用户资料编排训练计划",
            "用户要求训练计划时",
            "",
            "先读取动作库。",
            List.of("fitness.exercise.search"),
            true,
            "ACTIVE",
            1,
            Instant.parse("2026-08-06T00:00:00Z"));
    var service =
        new AdminWorkbenchService(
            new MemoryPort(draftWithoutTools, true, "AVAILABLE"),
            allRuntimeCapabilities(),
            activeResources(planSkill),
            bindings -> null);

    var validation = service.validate("fitness.coach");

    assertFalse(validation.valid());
    assertTrue(
        validation.errors().contains("Skill fitness.plan.skill 缺少所需 Tool fitness.exercise.search"));
  }

  @Test
  void toolsAreReadOnlyFromTheWorkbenchEvenWhenTheirCatalogRowExists() {
    var service =
        new AdminWorkbenchService(
            new MemoryPort(draft(), true, "AVAILABLE"), allRuntimeCapabilities());

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.updateComponent(
                    "TOOL",
                    "fitness.exercise.search",
                    new ComponentUpdate("x", "x", "AVAILABLE", List.of(), Map.of())));

    assertEquals("Tool 仅可由应用代码登记，工作台只读", failure.getMessage());
  }

  private static RuntimeCapabilityRegistry allRuntimeCapabilities() {
    return (type, key) -> true;
  }

  private static AdminResourcePort activeResources(SkillDefinition skill) {
    var at = Instant.parse("2026-08-06T00:00:00Z");
    var provider =
        new ProviderDefinition(
            "bailian",
            "阿里云百炼",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "OPENAI_COMPATIBLE",
            "ACTIVE",
            true,
            "••••",
            1,
            at);
    return (AdminResourcePort)
        Proxy.newProxyInstance(
            AdminWorkbenchServiceTest.class.getClassLoader(),
            new Class<?>[] {AdminResourcePort.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "findProvider" -> Optional.of(provider);
                  case "listModels" ->
                      List.of(
                          new ModelDefinition(
                              "qwen-plus",
                              "bailian",
                              "qwen-plus",
                              "通义千问 Plus",
                              "",
                              true,
                              true,
                              false,
                              "ACTIVE",
                              1,
                              at));
                  case "listFrameworks" ->
                      List.of(
                          new FrameworkDefinition(
                              "agentscope", "AgentScope", "", Map.of(), "ACTIVE", 1, at));
                  case "listPrompts" ->
                      List.of(
                          new PromptDefinition(
                              "fitness.coach.prompt", "花爷系统提示词", "", "", "ACTIVE", 1, at));
                  case "listMemories" ->
                      List.of(
                          new MemoryDefinition(
                              "fitness.daily-memory", "当日会话记忆", "", 24, 12000, "ACTIVE", 1, at));
                  case "listSkills" -> List.of(skill);
                  case "listHooks" -> List.of();
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static AgentDraftView draft() {
    return new AgentDraftView(
        "fitness.coach",
        "花爷健身教练",
        "根据真实健身数据提供陪伴与建议",
        "DRAFT",
        "agentscope",
        "bailian",
        "qwen-plus",
        "fitness.coach.prompt",
        List.of("fitness.exercise.search"),
        List.of("fitness.plan.skill"),
        List.of("fitness.safety"),
        "fitness.daily-memory",
        0.5,
        8,
        0,
        1,
        Instant.parse("2026-08-06T00:00:00Z"));
  }

  private static final class MemoryPort implements AdminWorkbenchPort {
    private AgentDraftView draft;
    private final boolean configured;
    private final String componentStatus;
    private final String modelProviderKey;
    private final List<AgentDraftView> publishedDrafts = new ArrayList<>();

    private MemoryPort(AgentDraftView draft, boolean configured, String componentStatus) {
      this(draft, configured, componentStatus, "bailian");
    }

    private MemoryPort(
        AgentDraftView draft, boolean configured, String componentStatus, String modelProviderKey) {
      this.draft = draft;
      this.configured = configured;
      this.componentStatus = componentStatus;
      this.modelProviderKey = modelProviderKey;
    }

    @Override
    public WorkbenchSnapshot snapshot() {
      return new WorkbenchSnapshot(
          new OverviewView(1, "DEGRADED", 1, configured ? 1 : 0, 0),
          List.of(draft),
          List.of(
              component("FRAMEWORK", "agentscope", componentStatus),
              component(
                  "MODEL", "qwen-plus", componentStatus, Map.of("providerKey", modelProviderKey)),
              component("PROMPT", "fitness.coach.prompt", componentStatus),
              component("MEMORY", "fitness.daily-memory", componentStatus),
              component("TOOL", "fitness.exercise.search", componentStatus),
              component("SKILL", "fitness.plan.skill", componentStatus),
              component("HOOK", "fitness.safety", componentStatus)),
          List.of(
              new ProviderView(
                  "bailian",
                  "阿里云百炼",
                  "https://dashscope.aliyuncs.com/compatible-mode/v1",
                  configured,
                  configured ? "••••••••" : "",
                  configured ? "READY" : "NOT_CONFIGURED")),
          List.of());
    }

    private static ComponentView component(String type, String key, String status) {
      return component(type, key, status, Map.of("source", "test"));
    }

    private static ComponentView component(
        String type, String key, String status, Map<String, Object> config) {
      return new ComponentView(
          type, key, key, "测试组件 " + key, 1, status, List.of("fitness"), config);
    }

    @Override
    public Optional<AgentDraftView> findDraft(String agentKey) {
      return draft.agentKey().equals(agentKey) ? Optional.of(draft) : Optional.empty();
    }

    @Override
    public AgentDraftView createDraft(CreateAgentRequest request) {
      if (findDraft(request.agentKey()).isPresent()) throw new Conflict("Agent Key 已存在");
      draft =
          new AgentDraftView(
              request.agentKey(),
              request.name(),
              request.description(),
              "DRAFT",
              draft.frameworkKey(),
              draft.providerKey(),
              draft.modelKey(),
              draft.promptKey(),
              draft.toolKeys(),
              draft.skillKeys(),
              draft.hookKeys(),
              draft.memoryKey(),
              draft.temperature(),
              draft.maxToolCalls(),
              0,
              1,
              Instant.now());
      return draft;
    }

    @Override
    public AgentDraftView updateDraft(String agentKey, DraftUpdate update, long expectedRevision) {
      if (expectedRevision != draft.revision()) throw new Conflict("revision conflict");
      draft =
          new AgentDraftView(
              agentKey,
              update.name(),
              update.description(),
              draft.status(),
              update.frameworkKey(),
              update.providerKey(),
              update.modelKey(),
              update.promptKey(),
              update.toolKeys(),
              update.skillKeys(),
              update.hookKeys(),
              update.memoryKey(),
              update.temperature(),
              update.maxToolCalls(),
              draft.publishedVersion(),
              draft.revision() + 1,
              Instant.now());
      return draft;
    }

    @Override
    public ProviderView saveCredential(String providerKey, char[] credential) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ComponentView updateComponent(String type, String componentKey, ComponentUpdate update) {
      return new ComponentView(
          type,
          componentKey,
          update.displayName(),
          update.description(),
          1,
          update.status(),
          update.tags(),
          update.config());
    }

    @Override
    public PublicationView publish(AgentDraftView draft) {
      publishedDrafts.add(draft);
      return new PublicationView(draft.agentKey(), 1, Instant.now());
    }

    @Override
    public Optional<RunView> run(UUID runId) {
      return Optional.empty();
    }
  }
}
