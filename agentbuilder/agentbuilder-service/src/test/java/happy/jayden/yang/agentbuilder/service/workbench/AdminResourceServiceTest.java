package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminResourceServiceTest {

  @Test
  void createsOpenAiCompatibleProviderWithNormalizedEndpoint() {
    var port = new MemoryPort();
    var service = service(port);

    var created =
        service.createProvider(
            new ProviderCreate("custom-openai", "Custom OpenAI", "https://llm.example.com/v1///"));

    assertEquals("https://llm.example.com/v1", created.endpoint());
    assertEquals("OPENAI_COMPATIBLE", created.protocol());
    assertEquals("ACTIVE", created.status());
  }

  @Test
  void rejectsModelWhoseProviderIsDisabled() {
    var port = new MemoryPort();
    port.providers.add(
        new ProviderDefinition(
            "disabled-provider",
            "Disabled",
            "https://llm.example.com/v1",
            "OPENAI_COMPATIBLE",
            "DISABLED",
            false,
            "",
            1,
            Instant.EPOCH));
    var service = service(port);

    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.createModel(
                    new ModelCreate(
                        "disabled-model",
                        "disabled-provider",
                        "model-id",
                        "Disabled Model",
                        "test",
                        true,
                        true,
                        false)));

    assertEquals("只能为已启用的 Provider 新增模型", error.getMessage());
  }

  @Test
  void createsPromptAndSkillAsIndependentResources() {
    var port = new MemoryPort();
    var service = service(port);

    var prompt =
        service.createPrompt(
            new PromptCreate("meal.prompt", "饮食提示词", "生成每日饮食建议", "请结合 {{profile}} 推荐三餐"));
    var skill =
        service.createSkill(
            new SkillCreate(
                "meal.skill",
                "饮食建议",
                "组合饮食上下文",
                "用户需要三餐建议时",
                "用户只记录饮食时",
                "先读取用户档案，再生成建议。",
                List.of("fitness.user.profile.query")));

    assertEquals(1, prompt.revision());
    assertEquals("meal.prompt", prompt.promptKey());
    assertEquals(1, skill.revision());
    assertEquals(List.of("fitness.user.profile.query"), skill.requiredToolKeys());
  }

  @Test
  void rejectsInvalidPromptAndSkillKeysBeforePersistence() {
    var port = new MemoryPort();
    var service = service(port);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.createPrompt(new PromptCreate("Bad Key", "提示词", "说明", "模板")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.createSkill(
                new SkillCreate("Bad Key", "技能", "说明", "时机", "禁用时机", "内容", List.of())));
    assertEquals(0, port.promptCreates);
    assertEquals(0, port.skillCreates);
  }

  private static final class MemoryPort implements AdminResourcePort {
    private final List<ProviderDefinition> providers = new ArrayList<>();
    private final List<ModelDefinition> models = new ArrayList<>();
    private int promptCreates;
    private int skillCreates;

    @Override
    public List<ProviderDefinition> listProviders() {
      return List.copyOf(providers);
    }

    @Override
    public Optional<ProviderDefinition> findProvider(String providerKey) {
      return providers.stream().filter(item -> item.providerKey().equals(providerKey)).findFirst();
    }

    @Override
    public ProviderDefinition createProvider(ProviderCreate request) {
      var value =
          new ProviderDefinition(
              request.providerKey(),
              request.displayName(),
              request.endpoint(),
              "OPENAI_COMPATIBLE",
              "ACTIVE",
              false,
              "",
              1,
              Instant.EPOCH);
      providers.add(value);
      return value;
    }

    @Override
    public ProviderDefinition updateProvider(
        String providerKey, ProviderUpdate request, long revision) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ModelDefinition> listModels(String providerKey) {
      return models.stream().filter(item -> item.providerKey().equals(providerKey)).toList();
    }

    @Override
    public ModelDefinition createModel(ModelCreate request) {
      var value =
          new ModelDefinition(
              request.modelKey(),
              request.providerKey(),
              request.modelId(),
              request.displayName(),
              request.description(),
              request.supportsStreaming(),
              request.supportsToolCalling(),
              request.supportsVision(),
              "ACTIVE",
              1,
              Instant.EPOCH);
      models.add(value);
      return value;
    }

    @Override
    public ModelDefinition updateModel(String modelKey, ModelUpdate request, long revision) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<PromptDefinition> listPrompts() {
      return List.of();
    }

    @Override
    public PromptDefinition createPrompt(PromptCreate request) {
      promptCreates++;
      return new PromptDefinition(
          request.promptKey(),
          request.displayName(),
          request.description(),
          request.template(),
          "ACTIVE",
          1,
          Instant.EPOCH);
    }

    @Override
    public PromptDefinition updatePrompt(String key, PromptUpdate request, long revision) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SkillDefinition> listSkills() {
      return List.of();
    }

    @Override
    public SkillDefinition createSkill(SkillCreate request) {
      skillCreates++;
      return new SkillDefinition(
          request.skillKey(),
          request.displayName(),
          request.description(),
          request.whenToUse(),
          request.whenNotToUse(),
          request.content(),
          request.requiredToolKeys(),
          true,
          "ACTIVE",
          1,
          Instant.EPOCH);
    }

    @Override
    public SkillDefinition updateSkill(String key, SkillUpdate request, long revision) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<HookDefinition> listHooks() {
      return List.of();
    }

    @Override
    public HookDefinition updateHook(String key, HookUpdate request, long revision) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<FrameworkDefinition> listFrameworks() {
      return List.of();
    }

    @Override
    public List<MemoryDefinition> listMemories() {
      return List.of();
    }
  }

  private static AdminResourceService service(MemoryPort port) {
    return new AdminResourceService(port, java.util.Set.of("fitness.user.profile.query"));
  }
}
