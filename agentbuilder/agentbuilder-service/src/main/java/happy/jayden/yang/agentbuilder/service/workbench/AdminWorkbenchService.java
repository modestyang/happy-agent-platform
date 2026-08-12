package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;

import happy.jayden.yang.agentbuilder.core.runtime.RuntimeCapabilityRegistry;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AdminWorkbenchService {
  private static final java.util.regex.Pattern AGENT_KEY =
      java.util.regex.Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

  private final AdminWorkbenchPort port;
  private final RuntimeCapabilityRegistry runtimeCapabilities;
  private final AdminResourcePort resources;
  private final ToolRegistry tools;

  public AdminWorkbenchService(
      AdminWorkbenchPort port, RuntimeCapabilityRegistry runtimeCapabilities) {
    this(port, runtimeCapabilities, null, null);
  }

  public AdminWorkbenchService(
      AdminWorkbenchPort port,
      RuntimeCapabilityRegistry runtimeCapabilities,
      AdminResourcePort resources,
      ToolRegistry tools) {
    this.port = Objects.requireNonNull(port, "port");
    this.runtimeCapabilities = Objects.requireNonNull(runtimeCapabilities, "runtimeCapabilities");
    this.resources = resources;
    this.tools = tools;
  }

  public WorkbenchSnapshot snapshot() {
    return port.snapshot();
  }

  public List<AgentDraftView> agents() {
    return port.agents();
  }

  public AgentDraftView updateDraft(String agentKey, DraftUpdate update, long expectedRevision) {
    if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision");
    return port.updateDraft(agentKey, Objects.requireNonNull(update, "update"), expectedRevision);
  }

  /**
   * Starts a real persisted draft using the platform's current default runtime wiring. Domain
   * tools, skills and hooks remain editable on the following configuration page.
   */
  public AgentDraftView createDraft(CreateAgentRequest request) {
    var normalized = Objects.requireNonNull(request, "request");
    var agentKey = normalized.agentKey().trim();
    if (agentKey.length() > 160 || !AGENT_KEY.matcher(agentKey).matches()) {
      throw new IllegalArgumentException("Agent Key 仅支持小写字母、数字、点和连字符，并且必须以字母开头");
    }
    if (port.findDraft(agentKey).isPresent())
      throw new AdminWorkbenchPort.Conflict("Agent Key 已存在");
    return port.createDraft(
        new CreateAgentRequest(
            agentKey, normalized.name().trim(), normalized.description().trim()));
  }

  public ComponentView updateComponent(String type, String componentKey, ComponentUpdate update) {
    if (type == null || type.isBlank() || componentKey == null || componentKey.isBlank()) {
      throw new IllegalArgumentException("type 和 componentKey 必填");
    }
    if ("TOOL".equals(type.trim())) {
      throw new IllegalArgumentException("Tool 仅可由应用代码登记，工作台只读");
    }
    return port.updateComponent(
        type.trim(), componentKey.trim(), Objects.requireNonNull(update, "update"));
  }

  public ValidationView validate(String agentKey) {
    var draft =
        port.findDraft(agentKey).orElseThrow(() -> new AdminWorkbenchPort.NotFound("Agent 草稿不存在"));
    if (resources != null && tools != null) return validateIndependent(draft);
    var snapshot = port.snapshot();
    var errors = new ArrayList<String>();
    var warnings = new ArrayList<String>();

    var provider =
        snapshot.providers().stream()
            .filter(item -> item.providerKey().equals(draft.providerKey()))
            .findFirst();
    if (provider.isEmpty()) errors.add("Provider " + draft.providerKey() + " 不存在");
    else if (!provider.get().configured()) errors.add("Provider 尚未配置 API Key");

    requireComponent(snapshot, errors, "FRAMEWORK", draft.frameworkKey());
    requireComponent(snapshot, errors, "MODEL", draft.modelKey());
    requireModelProviderAlignment(snapshot, errors, draft.modelKey(), draft.providerKey());
    requireComponent(snapshot, errors, "PROMPT", draft.promptKey());
    requireComponent(snapshot, errors, "MEMORY", draft.memoryKey());
    draft.toolKeys().forEach(key -> requireComponent(snapshot, errors, "TOOL", key));
    draft.skillKeys().forEach(key -> requireComponent(snapshot, errors, "SKILL", key));
    draft.hookKeys().forEach(key -> requireComponent(snapshot, errors, "HOOK", key));
    if (draft.toolKeys().isEmpty()) warnings.add("当前 Agent 没有绑定 Tool");
    if (draft.skillKeys().isEmpty()) warnings.add("当前 Agent 没有绑定 Skill");
    return new ValidationView(errors.isEmpty(), errors, warnings);
  }

  private ValidationView validateIndependent(AgentDraftView draft) {
    var errors = new ArrayList<String>();
    var warnings = new ArrayList<String>();
    var provider = resources.findProvider(draft.providerKey());
    if (provider.isEmpty()) errors.add("Provider " + draft.providerKey() + " 不存在");
    else {
      if (!"ACTIVE".equals(provider.get().status())) errors.add("Provider 已停用");
      if (!provider.get().configured()) errors.add("Provider 尚未配置 API Key");
    }
    resources.listModels(draft.providerKey()).stream()
        .filter(item -> item.modelKey().equals(draft.modelKey()))
        .findFirst()
        .ifPresentOrElse(
            item -> {
              if (!"ACTIVE".equals(item.status())) errors.add("模型 " + draft.modelKey() + " 已停用");
            },
            () -> errors.add("模型 " + draft.modelKey() + " 不存在或不属于当前 Provider"));
    requireActive(
        errors,
        "Framework",
        draft.frameworkKey(),
        resources.listFrameworks().stream()
            .map(item -> Map.entry(item.frameworkKey(), item.status()))
            .toList());
    requireActive(
        errors,
        "Prompt",
        draft.promptKey(),
        resources.listPrompts().stream()
            .map(item -> Map.entry(item.promptKey(), item.status()))
            .toList());
    requireActive(
        errors,
        "Memory",
        draft.memoryKey(),
        resources.listMemories().stream()
            .map(item -> Map.entry(item.memoryKey(), item.status()))
            .toList());
    var toolKeys =
        tools.descriptors().stream()
            .map(item -> item.toolKey())
            .collect(java.util.stream.Collectors.toSet());
    draft
        .toolKeys()
        .forEach(
            key -> {
              if (!toolKeys.contains(key)) errors.add("Tool " + key + " 没有已注册的运行时实现");
            });
    var skills = resources.listSkills();
    draft
        .skillKeys()
        .forEach(
            key -> {
              var item = skills.stream().filter(value -> value.skillKey().equals(key)).findFirst();
              if (item.isEmpty()
                  || !"ACTIVE".equals(item.get().status())
                  || !item.get().runtimeReady()) errors.add("Skill " + key + " 当前不可用");
              else
                item.get()
                    .requiredToolKeys()
                    .forEach(
                        requiredTool -> {
                          if (!draft.toolKeys().contains(requiredTool)) {
                            errors.add("Skill " + key + " 缺少所需 Tool " + requiredTool);
                          }
                        });
            });
    var hooks = resources.listHooks();
    draft
        .hookKeys()
        .forEach(
            key -> {
              var item = hooks.stream().filter(value -> value.hookKey().equals(key)).findFirst();
              if (item.isEmpty()
                  || !"ACTIVE".equals(item.get().status())
                  || !item.get().runtimeReady()) errors.add("Hook " + key + " 当前不可用");
            });
    if (draft.toolKeys().isEmpty()) warnings.add("当前 Agent 没有绑定 Tool");
    if (draft.skillKeys().isEmpty()) warnings.add("当前 Agent 没有绑定 Skill");
    return new ValidationView(errors.isEmpty(), errors, warnings);
  }

  private static void requireActive(
      List<String> errors, String label, String key, List<Map.Entry<String, String>> values) {
    var item = values.stream().filter(value -> value.getKey().equals(key)).findFirst();
    if (item.isEmpty()) errors.add(label + " " + key + " 不存在");
    else if (!"ACTIVE".equals(item.get().getValue())) errors.add(label + " " + key + " 已停用");
  }

  public ProviderView saveCredential(String providerKey, char[] credential) {
    Objects.requireNonNull(credential, "credential");
    try {
      if (credential.length < 8) throw new IllegalArgumentException("API Key 长度至少为 8 个字符");
      return port.saveCredential(providerKey, credential);
    } finally {
      Arrays.fill(credential, '\0');
    }
  }

  public PublicationView publish(String agentKey) {
    var validation = validate(agentKey);
    if (!validation.valid()) throw new AdminWorkbenchPort.ValidationFailure(validation);
    var draft =
        port.findDraft(agentKey).orElseThrow(() -> new AdminWorkbenchPort.NotFound("Agent 草稿不存在"));
    return port.publish(draft);
  }

  public RunView run(UUID runId) {
    return port.run(runId).orElseThrow(() -> new AdminWorkbenchPort.NotFound("运行记录不存在"));
  }

  private void requireComponent(
      WorkbenchSnapshot snapshot, List<String> errors, String type, String key) {
    if (key == null || key.isBlank()) {
      errors.add(type + " 尚未选择");
      return;
    }
    var component =
        snapshot.components().stream()
            .filter(item -> item.type().equals(type) && item.componentKey().equals(key))
            .findFirst();
    if (component.isEmpty()) errors.add("组件 " + key + " 不存在");
    else if (!"AVAILABLE".equals(component.get().status())) errors.add("组件 " + key + " 当前不可用");
    else if (requiresRuntimeHandler(type) && !runtimeCapabilities.hasHandler(type, key)) {
      errors.add("组件 " + key + " 没有已注册的运行时 handler");
    }
  }

  private static void requireModelProviderAlignment(
      WorkbenchSnapshot snapshot, List<String> errors, String modelKey, String providerKey) {
    if (modelKey == null || modelKey.isBlank() || providerKey == null || providerKey.isBlank())
      return;
    snapshot.components().stream()
        .filter(item -> item.type().equals("MODEL") && item.componentKey().equals(modelKey))
        .findFirst()
        .ifPresent(
            model -> {
              Object configuredProvider = model.config().get("providerKey");
              if (!(configuredProvider instanceof String value) || value.isBlank()) {
                errors.add("模型 " + modelKey + " 未声明 providerKey");
              } else if (!providerKey.equals(value)) {
                errors.add("模型 " + modelKey + " 未绑定当前 Provider " + providerKey);
              }
            });
  }

  private static boolean requiresRuntimeHandler(String type) {
    return "SKILL".equals(type) || "HOOK".equals(type);
  }
}
