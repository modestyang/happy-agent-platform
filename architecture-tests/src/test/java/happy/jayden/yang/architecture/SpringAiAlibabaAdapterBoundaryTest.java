package happy.jayden.yang.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "happy.jayden.yang",
    importOptions = ImportOption.DoNotIncludeTests.class)
class SpringAiAlibabaAdapterBoundaryTest {
  private static final String ADAPTER_PACKAGE = "..framework.adapter.springai..";
  private static final String[] FRAMEWORK_PACKAGES = {
    "org.springframework.ai..", "com.alibaba.cloud.ai.."
  };

  @ArchTest
  static final ArchRule frameworkTypesStayInsideTheAdapter =
      noClasses()
          .that()
          .resideOutsideOfPackage(ADAPTER_PACKAGE)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(FRAMEWORK_PACKAGES);

  @ArchTest
  static final ArchRule publicAdapterApiIsFrameworkNeutral =
      noClasses()
          .that()
          .arePublic()
          .and()
          .resideInAPackage(ADAPTER_PACKAGE)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(FRAMEWORK_PACKAGES);
}
