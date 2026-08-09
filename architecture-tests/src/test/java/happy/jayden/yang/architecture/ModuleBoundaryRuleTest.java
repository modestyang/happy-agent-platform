package happy.jayden.yang.architecture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import happy.jayden.yang.agentbuilder.core.FixtureCoreViolation;
import happy.jayden.yang.application.FixtureApplicationDependency;
import org.junit.jupiter.api.Test;

class ModuleBoundaryRuleTest {

  @Test
  void agentCoreRuleRejectsCoreDependingOnApplication() {
    var classes =
        new ClassFileImporter()
            .importClasses(FixtureCoreViolation.class, FixtureApplicationDependency.class);

    var violation =
        assertThrows(
            AssertionError.class,
            () -> ModuleBoundaryTest.agentCoreIsApplicationAgnostic.check(classes));

    assertTrue(violation.getMessage().contains("FixtureCoreViolation"));
  }
}
