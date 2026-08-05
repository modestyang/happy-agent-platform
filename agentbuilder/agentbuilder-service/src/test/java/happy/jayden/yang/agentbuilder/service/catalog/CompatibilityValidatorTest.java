package happy.jayden.yang.agentbuilder.service.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.component.AgentDraft;
import happy.jayden.yang.agentbuilder.core.component.CatalogMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentKey;
import happy.jayden.yang.agentbuilder.core.component.ComponentMetadata;
import happy.jayden.yang.agentbuilder.core.component.ComponentRef;
import happy.jayden.yang.agentbuilder.core.component.ComponentVersion;
import happy.jayden.yang.agentbuilder.core.component.HookBinding;
import happy.jayden.yang.agentbuilder.core.component.SkillBinding;
import happy.jayden.yang.agentbuilder.core.component.ToolBinding;
import happy.jayden.yang.agentbuilder.core.component.VersionReference;
import happy.jayden.yang.agentbuilder.core.component.framework.FrameworkAdapterDefinition;
import happy.jayden.yang.agentbuilder.core.component.hook.HookDefinition;
import happy.jayden.yang.agentbuilder.core.component.model.ModelDefinition;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderPublicConfig;
import happy.jayden.yang.agentbuilder.core.component.provider.ProviderVersion;
import happy.jayden.yang.agentbuilder.core.component.skill.SkillDefinition;
import happy.jayden.yang.agentbuilder.core.defaults.DefaultValues;
import happy.jayden.yang.agentbuilder.core.defaults.SparseModelParameters;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompatibilityValidatorTest {
  private static final String CHECKSUM = "a".repeat(64);

  @Test
  void acceptsValidDraftWithExactCatalogReferences() {
    var fixture = Fixture.valid();
    assertTrue(new CompatibilityValidator(fixture.catalog()).validate(fixture.draft()).isValid());
  }

  @Test
  void rejectsProviderModelVersionMismatch() {
    var fixture = Fixture.valid();
    var draft = fixture.draftWithProvider(ref("provider.main", 2));
    assertErrorsContain(fixture, draft, "model is not compatible with provider version");
    assertErrorsContain(fixture, draft, "provider definition is unavailable");
  }

  @Test
  void rejectsModelFrameworkMismatch() {
    var fixture = Fixture.valid();
    var incompatible =
        fixture.model(catalog(meta("model.main", 1), List.of(ref("framework.other", 1))));
    assertErrorsContain(
        fixture.catalogWith(fixture.framework(), fixture.provider(), incompatible),
        fixture.draft(),
        "model is not compatible with framework version");
  }

  @Test
  void rejectsModelWithoutToolCallingWhenToolsAreEnabled() {
    var fixture = Fixture.valid();
    var model = fixture.model(new ModelDefinition.Capabilities(false, false, false, false, false));
    assertErrorsContain(
        fixture.catalogWith(fixture.framework(), fixture.provider(), model),
        fixture.draft(),
        "model does not support tools");
  }

  @Test
  void rejectsAdapterWithoutSkillCapability() {
    var fixture = Fixture.valid();
    var framework = fixture.framework(true, false, true);
    assertErrorsContain(
        fixture.catalogWith(framework, fixture.provider(), fixture.model()),
        fixture.draft(),
        "framework adapter does not support skills");
  }

  @Test
  void rejectsWrongVersionOfRequiredToolWithSameKey() {
    var fixture = Fixture.valid();
    var draft =
        fixture.draftWith(
            List.of(
                new ToolBinding(new ComponentKey("tool.search"), new ComponentVersion(2), true)),
            fixture.draft().hookBindings());
    assertErrorsContain(fixture, draft, "skill requires enabled tool: tool.search@1");
  }

  @Test
  void rejectsMissingMandatoryHook() {
    var fixture = Fixture.valid();
    assertErrorsContain(
        fixture,
        fixture.draftWith(fixture.draft().toolBindings(), List.of()),
        "mandatory hook is missing");
  }

  @Test
  void rejectsDisabledMandatoryHook() {
    var fixture = Fixture.valid();
    var hooks =
        List.of(
            new HookBinding(fixture.hookRef().componentKey(), fixture.hookRef().version(), false));
    assertErrorsContain(
        fixture,
        fixture.draftWith(fixture.draft().toolBindings(), hooks),
        "mandatory hook cannot be disabled");
  }

  @Test
  void rejectsUnresolvedEnabledHookBinding() {
    assertUnresolvedHook(true);
  }

  @Test
  void rejectsUnresolvedDisabledHookBinding() {
    assertUnresolvedHook(false);
  }

  @Test
  void rejectsEnabledHookWhenAdapterDoesNotSupportHooks() {
    var fixture = Fixture.valid();
    var framework = fixture.framework(true, true, false);
    assertErrorsContain(
        fixture.catalogWith(framework, fixture.provider(), fixture.model()),
        fixture.draft(),
        "framework adapter does not support hooks");
  }

  private static void assertUnresolvedHook(boolean enabled) {
    var fixture = Fixture.valid();
    var hooks =
        List.of(
            new HookBinding(new ComponentKey("hook.unknown"), new ComponentVersion(1), enabled));
    assertErrorsContain(
        fixture,
        fixture.draftWith(fixture.draft().toolBindings(), hooks),
        "hook definition is unavailable: hook.unknown");
  }

  private static void assertErrorsContain(Fixture fixture, AgentDraft draft, String expected) {
    assertErrorsContain(fixture.catalog(), draft, expected);
  }

  private static void assertErrorsContain(
      CatalogDefinitions catalog, AgentDraft draft, String expected) {
    var report = new CompatibilityValidator(catalog).validate(draft);
    assertFalse(report.isValid());
    assertTrue(
        report.errors().stream().anyMatch(error -> error.contains(expected)),
        report.errors().toString());
  }

  private record Fixture(
      ComponentRef frameworkRef,
      ComponentRef providerRef,
      ComponentRef modelRef,
      ComponentRef skillRef,
      ComponentRef hookRef,
      FrameworkAdapterDefinition framework,
      ProviderVersion provider,
      ModelDefinition model,
      SkillDefinition skill,
      HookDefinition hook,
      AgentDraft draft) {
    static Fixture valid() {
      var frameworkRef = ref("framework.main", 1);
      var providerRef = ref("provider.main", 1);
      var modelRef = ref("model.main", 1);
      var skillRef = ref("skill.main", 1);
      var hookRef = ref("hook.main", 1);
      var framework = CompatibilityValidatorTest.framework(frameworkRef, true, true, true);
      var provider = CompatibilityValidatorTest.provider(providerRef, frameworkRef);
      var model = CompatibilityValidatorTest.model(modelRef, providerRef, frameworkRef, true);
      var skill = CompatibilityValidatorTest.skill(skillRef, frameworkRef);
      var hook = CompatibilityValidatorTest.hook(hookRef, frameworkRef);
      var draft =
          CompatibilityValidatorTest.draft(frameworkRef, providerRef, modelRef, skillRef, hookRef);
      return new Fixture(
          frameworkRef,
          providerRef,
          modelRef,
          skillRef,
          hookRef,
          framework,
          provider,
          model,
          skill,
          hook,
          draft);
    }

    CatalogDefinitions catalog() {
      return catalogWith(framework, provider, model);
    }

    CatalogDefinitions catalogWith(
        FrameworkAdapterDefinition selectedFramework,
        ProviderVersion selectedProvider,
        ModelDefinition selectedModel) {
      return new CatalogDefinitions(
          Map.of(skillRef, skill),
          Map.of(hookRef, hook),
          Map.of(providerRef, selectedProvider),
          Map.of(modelRef, selectedModel),
          Map.of(),
          Map.of(),
          Map.of(),
          Map.of(),
          Map.of(frameworkRef, selectedFramework));
    }

    FrameworkAdapterDefinition framework(boolean tools, boolean skills, boolean hooks) {
      return CompatibilityValidatorTest.framework(frameworkRef, tools, skills, hooks);
    }

    ModelDefinition model(ModelDefinition.Capabilities capabilities) {
      return new ModelDefinition(
          model.metadata(),
          model.modelId(),
          model.providerRef(),
          model.modalities(),
          model.contextWindow(),
          model.maxOutputTokens(),
          capabilities,
          model.defaultParameters(),
          model.catalogMetadata());
    }

    ModelDefinition model(CatalogMetadata metadata) {
      return new ModelDefinition(
          model.metadata(),
          model.modelId(),
          model.providerRef(),
          model.modalities(),
          model.contextWindow(),
          model.maxOutputTokens(),
          model.capabilities(),
          model.defaultParameters(),
          metadata);
    }

    AgentDraft draftWithProvider(ComponentRef selectedProvider) {
      return copyDraft(
          draft, version(selectedProvider), draft.toolBindings(), draft.hookBindings());
    }

    AgentDraft draftWith(List<ToolBinding> tools, List<HookBinding> hooks) {
      return copyDraft(draft, draft.providerVersion(), tools, hooks);
    }
  }

  private static AgentDraft copyDraft(
      AgentDraft draft,
      VersionReference provider,
      List<ToolBinding> tools,
      List<HookBinding> hooks) {
    return new AgentDraft(
        draft.agentKey(),
        draft.applicationScope(),
        draft.revision(),
        draft.frameworkVersion(),
        provider,
        draft.modelBinding(),
        draft.promptVersion(),
        tools,
        draft.skillBindings(),
        hooks,
        draft.memoryPolicyVersion(),
        draft.outputSchemaVersion(),
        draft.evaluationSuiteVersion(),
        draft.defaultProfileVersion(),
        draft.runtimeOverrides(),
        draft.updatedAt());
  }

  private static FrameworkAdapterDefinition framework(
      ComponentRef reference, boolean tools, boolean skills, boolean hooks) {
    return new FrameworkAdapterDefinition(
        meta(reference),
        "Framework",
        "Framework adapter",
        List.of("runtime"),
        tools,
        skills,
        hooks,
        catalog(meta(reference), List.of(reference)));
  }

  private static ProviderVersion provider(ComponentRef reference, ComponentRef framework) {
    return new ProviderVersion(
        meta(reference),
        "Provider",
        "Provider definition",
        List.of("model"),
        new ProviderVersion.CredentialReference("secret.provider", 1),
        "https://example.test",
        ProviderPublicConfig.empty(),
        catalog(meta(reference), List.of(framework)));
  }

  private static ModelDefinition model(
      ComponentRef reference, ComponentRef provider, ComponentRef framework, boolean tools) {
    return new ModelDefinition(
        meta(reference),
        "model",
        provider,
        List.of(ModelDefinition.Modality.TEXT),
        100,
        10,
        new ModelDefinition.Capabilities(tools, true, true, false, false),
        SparseModelParameters.empty(),
        catalog(meta(reference), List.of(framework)));
  }

  private static SkillDefinition skill(ComponentRef reference, ComponentRef framework) {
    return new SkillDefinition(
        meta(reference),
        "# Skill\n\n```java\nrecord Example() {}\n```",
        List.of(),
        CHECKSUM,
        SkillDefinition.ProgressiveDisclosure.none(),
        List.of(ref("tool.search", 1)),
        catalog(meta(reference), List.of(framework)));
  }

  private static HookDefinition hook(ComponentRef reference, ComponentRef framework) {
    return new HookDefinition(
        meta(reference),
        "audit",
        Set.of(HookDefinition.Phase.PRE_TOOL),
        1,
        new HookDefinition.ConfigSchema("{\"type\":\"object\",\"additionalProperties\":false}"),
        100,
        HookDefinition.SideEffect.READ,
        HookDefinition.FailurePolicy.FAIL_CLOSED,
        true,
        Set.of("fitness"),
        Set.of(framework),
        catalog(meta(reference), List.of(framework)));
  }

  private static AgentDraft draft(
      ComponentRef framework,
      ComponentRef provider,
      ComponentRef model,
      ComponentRef skill,
      ComponentRef hook) {
    return new AgentDraft(
        "agent.main",
        "fitness",
        1,
        version(framework),
        version(provider),
        version(model),
        version(ref("prompt.main", 1)),
        List.of(new ToolBinding(new ComponentKey("tool.search"), new ComponentVersion(1), true)),
        List.of(new SkillBinding(skill.componentKey(), skill.version(), true)),
        List.of(new HookBinding(hook.componentKey(), hook.version(), true)),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        DefaultValues.empty(),
        Instant.EPOCH);
  }

  private static ComponentMetadata meta(String key, int version) {
    return meta(ref(key, version));
  }

  private static ComponentMetadata meta(ComponentRef reference) {
    return ComponentMetadata.available(reference.componentKey(), reference.version(), CHECKSUM);
  }

  private static CatalogMetadata catalog(
      ComponentMetadata metadata, List<ComponentRef> frameworks) {
    return new CatalogMetadata(
        metadata.componentKey().value(),
        "description",
        "catalog",
        List.of("test"),
        frameworks,
        new CatalogMetadata.Source(CatalogMetadata.SourceType.INTERNAL, "test"),
        new CatalogMetadata.Audit("tester", Instant.EPOCH),
        1);
  }

  private static ComponentRef ref(String key, int version) {
    return new ComponentRef(new ComponentKey(key), new ComponentVersion(version));
  }

  private static VersionReference version(ComponentRef reference) {
    return new VersionReference(reference.componentKey(), reference.version());
  }
}
