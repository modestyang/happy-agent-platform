package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.*;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Application service for Provider and Model lifecycle rules. */
public final class AdminResourceService {
  private static final Pattern RESOURCE_KEY = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

  private final AdminResourcePort port;
  private final Set<String> runtimeToolKeys;

  public AdminResourceService(AdminResourcePort port, Set<String> runtimeToolKeys) {
    this.port = Objects.requireNonNull(port, "port");
    this.runtimeToolKeys = Set.copyOf(Objects.requireNonNull(runtimeToolKeys, "runtimeToolKeys"));
  }

  public List<ProviderDefinition> listProviders() {
    return port.listProviders();
  }

  public ProviderDefinition createProvider(ProviderCreate request) {
    Objects.requireNonNull(request, "request");
    requireKey(request.providerKey(), "Provider Key");
    var normalized =
        new ProviderCreate(
            request.providerKey(), request.displayName(), normalizeEndpoint(request.endpoint()));
    if (port.findProvider(normalized.providerKey()).isPresent()) {
      throw new IllegalArgumentException("Provider Key 已存在");
    }
    return port.createProvider(normalized);
  }

  public ProviderDefinition updateProvider(
      String providerKey, ProviderUpdate request, long expectedRevision) {
    Objects.requireNonNull(request, "request");
    if (expectedRevision < 1) throw new IllegalArgumentException("If-Match 必须是有效 revision");
    return port.updateProvider(
        providerKey,
        new ProviderUpdate(
            request.displayName(), normalizeEndpoint(request.endpoint()), request.status()),
        expectedRevision);
  }

  public List<ModelDefinition> listModels(String providerKey) {
    return port.listModels(providerKey == null ? "" : providerKey.trim());
  }

  public ModelDefinition createModel(ModelCreate request) {
    Objects.requireNonNull(request, "request");
    requireKey(request.modelKey(), "Model Key");
    var provider =
        port.findProvider(request.providerKey())
            .orElseThrow(() -> new IllegalArgumentException("Provider 不存在"));
    if (!"ACTIVE".equals(provider.status())) {
      throw new IllegalArgumentException("只能为已启用的 Provider 新增模型");
    }
    return port.createModel(
        new ModelCreate(
            request.modelKey(),
            request.providerKey(),
            request.modelId(),
            request.displayName(),
            request.description(),
            true,
            request.supportsToolCalling(),
            request.supportsVision()));
  }

  public ModelDefinition updateModel(String modelKey, ModelUpdate request, long expectedRevision) {
    Objects.requireNonNull(request, "request");
    if (expectedRevision < 1) throw new IllegalArgumentException("If-Match 必须是有效 revision");
    return port.updateModel(
        modelKey,
        new ModelUpdate(
            request.modelId(),
            request.displayName(),
            request.description(),
            true,
            request.supportsToolCalling(),
            request.supportsVision(),
            request.status()),
        expectedRevision);
  }

  public List<PromptDefinition> listPrompts() {
    return port.listPrompts();
  }

  public PromptDefinition createPrompt(PromptCreate request) {
    Objects.requireNonNull(request, "request");
    requireKey(request.promptKey(), "Prompt Key");
    return port.createPrompt(request);
  }

  public PromptDefinition updatePrompt(String key, PromptUpdate request, long revision) {
    requireRevision(revision);
    return port.updatePrompt(key, Objects.requireNonNull(request, "request"), revision);
  }

  public List<SkillDefinition> listSkills() {
    return port.listSkills();
  }

  public SkillDefinition createSkill(SkillCreate request) {
    Objects.requireNonNull(request, "request");
    requireKey(request.skillKey(), "Skill Key");
    var missing =
        request.requiredToolKeys().stream()
            .filter(key -> !runtimeToolKeys.contains(key))
            .distinct()
            .toList();
    if (!missing.isEmpty())
      throw new IllegalArgumentException("依赖工具不存在: " + String.join(", ", missing));
    return port.createSkill(request);
  }

  public SkillDefinition updateSkill(String key, SkillUpdate request, long revision) {
    requireRevision(revision);
    return port.updateSkill(key, Objects.requireNonNull(request, "request"), revision);
  }

  public List<HookDefinition> listHooks() {
    return port.listHooks();
  }

  public HookDefinition updateHook(String key, HookUpdate request, long revision) {
    requireRevision(revision);
    return port.updateHook(key, Objects.requireNonNull(request, "request"), revision);
  }

  public List<FrameworkDefinition> listFrameworks() {
    return port.listFrameworks();
  }

  public List<MemoryDefinition> listMemories() {
    return port.listMemories();
  }

  private static String normalizeEndpoint(String endpoint) {
    var normalized = endpoint.trim().replaceFirst("/+$", "");
    var uri = URI.create(normalized);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null) {
      throw new IllegalArgumentException("Endpoint 必须是有效的 HTTP(S) 地址");
    }
    return normalized;
  }

  private static void requireKey(String value, String label) {
    if (value.length() > 160 || !RESOURCE_KEY.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " 仅支持小写字母、数字、点和连字符，并且必须以字母开头");
    }
  }

  private static void requireRevision(long revision) {
    if (revision < 1) throw new IllegalArgumentException("If-Match 必须是有效 revision");
  }
}
