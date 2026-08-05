package happy.jayden.yang.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "happy.jayden.yang",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {
    @ArchTest
    static final ArchRule agentCoreIsApplicationAgnostic =
            noClasses().that().resideInAPackage("..agentbuilder.core..")
                    .should().dependOnClassesThat().resideInAPackage("..application..");

    @ArchTest
    static final ArchRule onlyStarterBoots =
            classes().that().areAnnotatedWith(SpringBootApplication.class)
                    .should().resideInAPackage("happy.jayden.yang");

    @ArchTest
    static final ArchRule agentScopeTypesStayInsideTheAdapter =
            noClasses().that().resideOutsideOfPackage("..framework.adapter.agentscope..")
                    .should().dependOnClassesThat().resideInAnyPackage("io.agentscope..");

    @ArchTest
    static final ArchRule agentScopePublicApiIsFrameworkNeutral =
            noClasses().that().arePublic()
                    .and().resideInAPackage("..framework.adapter.agentscope..")
                    .should().dependOnClassesThat().resideInAnyPackage("io.agentscope..");
}
