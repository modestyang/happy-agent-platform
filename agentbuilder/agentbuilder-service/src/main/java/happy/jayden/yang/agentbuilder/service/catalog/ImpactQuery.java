package happy.jayden.yang.agentbuilder.service.catalog;

import happy.jayden.yang.agentbuilder.core.component.AgentDraft;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.VersionReference;
import java.util.List;
import java.util.Objects;

/** Finds drafts whose bindings refer to one component version. */
public final class ImpactQuery {
  private final List<AgentDraft> drafts;

  public ImpactQuery(List<AgentDraft> drafts) {
    this.drafts = List.copyOf(Objects.requireNonNull(drafts, "drafts"));
  }

  public List<AgentDraft> affectedBy(ComponentRef component) {
    Objects.requireNonNull(component, "component");
    return drafts.stream()
        .filter(
            draft ->
                references(draft.frameworkVersion(), component)
                    || references(draft.providerVersion(), component)
                    || references(draft.modelBinding(), component)
                    || references(draft.promptVersion(), component)
                    || draft.memoryPolicyVersion().stream()
                        .anyMatch(reference -> references(reference, component))
                    || draft.outputSchemaVersion().stream()
                        .anyMatch(reference -> references(reference, component))
                    || draft.evaluationSuiteVersion().stream()
                        .anyMatch(reference -> references(reference, component))
                    || draft.defaultProfileVersion().stream()
                        .anyMatch(reference -> references(reference, component))
                    || draft.toolBindings().stream()
                        .anyMatch(
                            binding ->
                                references(
                                    new VersionReference(
                                        binding.toolKey(), binding.contractVersion()),
                                    component))
                    || draft.skillBindings().stream()
                        .anyMatch(
                            binding ->
                                references(
                                    new VersionReference(binding.skillKey(), binding.version()),
                                    component))
                    || draft.hookBindings().stream()
                        .anyMatch(
                            binding ->
                                references(
                                    new VersionReference(binding.hookKey(), binding.version()),
                                    component)))
        .toList();
  }

  private static boolean references(VersionReference reference, ComponentRef component) {
    return ComponentRef.from(reference).equals(component);
  }
}
