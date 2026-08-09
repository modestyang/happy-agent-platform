package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.runtime.RuntimeCapabilityRegistry;
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
    assertTrue(validation.errors().contains("组件 fitness.plan.generate 当前不可用"));
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
            "更温柔的瘦瘦",
            "陪用户完成日常训练",
            "agentscope",
            "bailian",
            "qwen-plus",
            "fitness.coach.prompt",
            List.of("fitness.plan.generate"),
            List.of("fitness.plan.skill"),
            List.of("fitness.safety"),
            "fitness.daily-memory",
            0.4,
            6);

    var updated = service.updateDraft("fitness.coach", update, 1);

    assertEquals("更温柔的瘦瘦", updated.name());
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
                    "fitness.plan.generate",
                    new ComponentUpdate("x", "x", "AVAILABLE", List.of(), Map.of())));

    assertEquals("Tool 仅可由应用代码登记，工作台只读", failure.getMessage());
  }

  private static RuntimeCapabilityRegistry allRuntimeCapabilities() {
    return (type, key) -> true;
  }

  private static AgentDraftView draft() {
    return new AgentDraftView(
        "fitness.coach",
        "瘦瘦健身教练",
        "根据真实健身数据提供陪伴与建议",
        "DRAFT",
        "agentscope",
        "bailian",
        "qwen-plus",
        "fitness.coach.prompt",
        List.of("fitness.plan.generate"),
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
    private final List<AgentDraftView> publishedDrafts = new ArrayList<>();

    private MemoryPort(AgentDraftView draft, boolean configured, String componentStatus) {
      this.draft = draft;
      this.configured = configured;
      this.componentStatus = componentStatus;
    }

    @Override
    public WorkbenchSnapshot snapshot() {
      return new WorkbenchSnapshot(
          new OverviewView(1, "DEGRADED", 1, configured ? 1 : 0, 0),
          List.of(draft),
          List.of(
              component("FRAMEWORK", "agentscope", componentStatus),
              component("MODEL", "qwen-plus", componentStatus),
              component("PROMPT", "fitness.coach.prompt", componentStatus),
              component("MEMORY", "fitness.daily-memory", componentStatus),
              component("TOOL", "fitness.plan.generate", componentStatus),
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
      return new ComponentView(
          type, key, key, "测试组件 " + key, 1, status, List.of("fitness"), Map.of("source", "test"));
    }

    @Override
    public Optional<AgentDraftView> findDraft(String agentKey) {
      return draft.agentKey().equals(agentKey) ? Optional.of(draft) : Optional.empty();
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
