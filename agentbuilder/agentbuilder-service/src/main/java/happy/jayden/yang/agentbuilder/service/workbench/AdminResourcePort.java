package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.*;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for independently managed admin resources. */
public interface AdminResourcePort {
  List<ProviderDefinition> listProviders();

  Optional<ProviderDefinition> findProvider(String providerKey);

  ProviderDefinition createProvider(ProviderCreate request);

  ProviderDefinition updateProvider(String providerKey, ProviderUpdate request, long revision);

  List<ModelDefinition> listModels(String providerKey);

  ModelDefinition createModel(ModelCreate request);

  ModelDefinition updateModel(String modelKey, ModelUpdate request, long revision);

  List<PromptDefinition> listPrompts();

  PromptDefinition createPrompt(PromptCreate request);

  PromptDefinition updatePrompt(String promptKey, PromptUpdate request, long revision);

  List<SkillDefinition> listSkills();

  SkillDefinition createSkill(SkillCreate request);

  SkillDefinition updateSkill(String skillKey, SkillUpdate request, long revision);

  List<HookDefinition> listHooks();

  HookDefinition updateHook(String hookKey, HookUpdate request, long revision);

  List<FrameworkDefinition> listFrameworks();

  List<MemoryDefinition> listMemories();
}
