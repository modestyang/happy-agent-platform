package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;

import happy.jayden.yang.agentbuilder.core.runtime.RuntimeCapabilityRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AdminWorkbenchService {
  private static final String MANDATORY_SAFETY_HOOK = "fitness.safety";

  private final AdminWorkbenchPort port;
  private final RuntimeCapabilityRegistry runtimeCapabilities;

  public AdminWorkbenchService(
      AdminWorkbenchPort port, RuntimeCapabilityRegistry runtimeCapabilities) {
    this.port = Objects.requireNonNull(port, "port");
    this.runtimeCapabilities = Objects.requireNonNull(runtimeCapabilities, "runtimeCapabilities");
  }

  public WorkbenchSnapshot snapshot() {
    return port.snapshot();
  }

  public AgentDraftView updateDraft(String agentKey, DraftUpdate update, long expectedRevision) {
    if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision");
    return port.updateDraft(agentKey, Objects.requireNonNull(update, "update"), expectedRevision);
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
    if (!draft.hookKeys().contains(MANDATORY_SAFETY_HOOK)) {
      errors.add("必需安全 Hook fitness.safety 尚未绑定");
    }
    requireComponent(snapshot, errors, "HOOK", MANDATORY_SAFETY_HOOK);
    draft.hookKeys().stream()
        .filter(key -> !MANDATORY_SAFETY_HOOK.equals(key))
        .forEach(key -> requireComponent(snapshot, errors, "HOOK", key));
    if (draft.toolKeys().isEmpty()) warnings.add("当前 Agent 没有绑定 Tool");
    if (draft.skillKeys().isEmpty()) warnings.add("当前 Agent 没有绑定 Skill");
    return new ValidationView(errors.isEmpty(), errors, warnings);
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
    if (modelKey == null || modelKey.isBlank() || providerKey == null || providerKey.isBlank()) return;
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
