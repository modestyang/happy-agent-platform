package happy.jayden.yang.agentbuilder.service.workbench;

import static happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AdminWorkbenchService {
  private final AdminWorkbenchPort port;

  public AdminWorkbenchService(AdminWorkbenchPort port) {
    this.port = Objects.requireNonNull(port, "port");
  }

  public WorkbenchSnapshot snapshot() {
    return port.snapshot();
  }

  public AgentDraftView updateDraft(String agentKey, DraftUpdate update, long expectedRevision) {
    if (expectedRevision < 1) throw new IllegalArgumentException("expectedRevision");
    return port.updateDraft(agentKey, Objects.requireNonNull(update, "update"), expectedRevision);
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
    requireComponent(snapshot, errors, "PROMPT", draft.promptKey());
    requireComponent(snapshot, errors, "MEMORY", draft.memoryKey());
    draft.toolKeys().forEach(key -> requireComponent(snapshot, errors, "TOOL", key));
    draft.skillKeys().forEach(key -> requireComponent(snapshot, errors, "SKILL", key));
    draft.hookKeys().forEach(key -> requireComponent(snapshot, errors, "HOOK", key));
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

  private static void requireComponent(
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
  }
}
