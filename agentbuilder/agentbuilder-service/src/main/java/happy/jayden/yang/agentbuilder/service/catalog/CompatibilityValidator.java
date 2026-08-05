package happy.jayden.yang.agentbuilder.service.catalog;

import happy.jayden.yang.agentbuilder.core.component.AgentDraft;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Enforces cross-catalog constraints before an agent draft is published. */
public final class CompatibilityValidator {
  private final CatalogDefinitions catalog;

  public CompatibilityValidator() {
    this(CatalogDefinitions.empty());
  }

  public CompatibilityValidator(CatalogDefinitions catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public ValidationReport validate(AgentDraft draft) {
    if (draft == null) return new ValidationReport(List.of("agent draft is required"));
    var errors = new ArrayList<String>();
    var framework = catalog.frameworks().get(ComponentRef.from(draft.frameworkVersion()));
    if (framework == null) errors.add("framework adapter is unavailable");
    var model = catalog.models().get(ComponentRef.from(draft.modelBinding()));
    if (model == null) errors.add("model definition is unavailable");
    if (model != null && !model.providerRef().equals(ComponentRef.from(draft.providerVersion()))) {
      errors.add("model is not compatible with provider version");
    }
    var provider = catalog.providers().get(ComponentRef.from(draft.providerVersion()));
    if (provider == null) errors.add("provider definition is unavailable");
    var frameworkRef = ComponentRef.from(draft.frameworkVersion());
    if (model != null && !model.catalogMetadata().supports(frameworkRef))
      errors.add("model is not compatible with framework version");
    if (provider != null && !provider.catalogMetadata().supports(frameworkRef))
      errors.add("provider is not compatible with framework version");
    if (model != null
        && draft.toolBindings().stream().anyMatch(binding -> binding.enabled())
        && !model.capabilities().toolCalling()) {
      errors.add("model does not support tools");
    }
    if (framework != null
        && draft.toolBindings().stream().anyMatch(binding -> binding.enabled())
        && !framework.supportsTools()) errors.add("framework adapter does not support tools");
    if (framework != null
        && draft.skillBindings().stream().anyMatch(binding -> binding.enabled())
        && !framework.supportsSkills()) errors.add("framework adapter does not support skills");
    draft.skillBindings().stream()
        .filter(binding -> binding.enabled())
        .forEach(
            binding -> {
              var skill =
                  catalog.skills().get(new ComponentRef(binding.skillKey(), binding.version()));
              if (skill == null) {
                errors.add("skill definition is unavailable: " + binding.skillKey().value());
                return;
              }
              if (!skill.catalogMetadata().supports(frameworkRef)) {
                errors.add("skill is not compatible with framework version");
              }
              skill
                  .requiredTools()
                  .forEach(
                      tool -> {
                        var present =
                            draft.toolBindings().stream()
                                .anyMatch(
                                    bindingTool ->
                                        bindingTool.enabled()
                                            && new ComponentRef(
                                                    bindingTool.toolKey(),
                                                    bindingTool.contractVersion())
                                                .equals(tool));
                        if (!present)
                          errors.add(
                              "skill requires enabled tool: "
                                  + tool.componentKey().value()
                                  + "@"
                                  + tool.version().value());
                      });
            });
    draft
        .hookBindings()
        .forEach(
            binding -> {
              var hook =
                  catalog.hooks().get(new ComponentRef(binding.hookKey(), binding.version()));
              if (hook == null) {
                errors.add("hook definition is unavailable: " + binding.hookKey().value());
                return;
              }
              var applicable = hook.appliesTo(frameworkRef, draft.applicationScope());
              if (binding.enabled() && !applicable)
                errors.add(
                    "enabled hook is incompatible with framework or application scope: "
                        + binding.hookKey().value());
              if (binding.enabled() && framework != null && !framework.supportsHooks())
                errors.add("framework adapter does not support hooks");
              if (hook.mandatory() && applicable && !binding.enabled())
                errors.add("mandatory hook cannot be disabled: " + binding.hookKey().value());
            });
    catalog
        .hooks()
        .forEach(
            (reference, hook) -> {
              if (hook.mandatory()
                  && hook.appliesTo(
                      ComponentRef.from(draft.frameworkVersion()), draft.applicationScope())
                  && draft.hookBindings().stream()
                      .noneMatch(
                          binding ->
                              new ComponentRef(binding.hookKey(), binding.version())
                                  .equals(reference))) {
                errors.add("mandatory hook is missing: " + reference.componentKey().value());
                if (framework != null && !framework.supportsHooks())
                  errors.add("framework adapter does not support hooks");
              }
            });
    return new ValidationReport(errors);
  }
}
