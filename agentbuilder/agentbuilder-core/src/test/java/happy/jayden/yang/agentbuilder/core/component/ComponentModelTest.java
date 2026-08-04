package happy.jayden.yang.agentbuilder.core.component;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComponentModelTest {

  @Test
  void componentIdentityRejectsValuesThatCannotSatisfyTheFrozenContract() {
    assertThrows(IllegalArgumentException.class, () -> new ComponentKey("Bad Key"));
    assertThrows(IllegalArgumentException.class, () -> new ComponentVersion(0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PublishedComponentRef(
                new ComponentKey("model.main"), new ComponentVersion(1), "not-a-sha256"));
  }

  @Test
  void frameworkNeutralComponentsFormAClosedTypedHierarchy() {
    assertTrue(VersionedComponent.class.isSealed());
    assertTrue(
        java.util.Set.of(VersionedComponent.class.getPermittedSubclasses())
            .containsAll(
                java.util.Set.of(
                    FrameworkRef.class,
                    ProviderRef.class,
                    ModelBinding.class,
                    PromptRef.class,
                    MemoryPolicyRef.class,
                    OutputSchemaRef.class,
                    EvaluationSuiteRef.class)));
  }
}
