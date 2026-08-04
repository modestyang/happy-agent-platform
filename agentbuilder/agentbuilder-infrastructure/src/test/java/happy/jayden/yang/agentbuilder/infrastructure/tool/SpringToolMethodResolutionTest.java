package happy.jayden.yang.agentbuilder.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

class SpringToolMethodResolutionTest {

  @Test
  void resolvesOneMostSpecificGenericMethodThroughJdkProxy() throws Exception {
    var proxyFactory = new ProxyFactory(new StringGenericTools());
    proxyFactory.setInterfaces(StringTools.class);
    var proxy = proxyFactory.getProxy();

    var registration = scanner().scanRegistration(proxy);

    assertEquals(
        "string",
        property(registration.descriptor().inputSchema().document(), "value").get("type"));
    assertEquals(
        "legs:user-1",
        registration
            .handler()
            .invoke(
                Map.of("value", "legs"),
                new ToolExecutionContext("user-1", "run-1", Set.of(), "operation-1")));
  }

  @Test
  void mergesComposedImplementationAnnotationWithoutDuplicatingInterfaceContractOnCglibProxy()
      throws Exception {
    var proxyFactory = new ProxyFactory(new StringGenericTools());
    proxyFactory.setProxyTargetClass(true);
    var proxy = proxyFactory.getProxy();

    var registrations = scanner().scanRegistrations(List.of(proxy));

    assertEquals(1, registrations.size());
    assertEquals("generic_query", registrations.get(0).descriptor().runtimeName());
    assertEquals(
        "arms:user-2",
        registrations
            .get(0)
            .handler()
            .invoke(
                Map.of("value", "arms"),
                new ToolExecutionContext("user-2", "run-2", Set.of(), "operation-2")));
  }

  @Test
  void mergesInterfaceParameterMetadataIntoAnnotatedImplementation() throws Exception {
    var registration = scanner().scanRegistration(new ImplementationMetadataOnly());

    assertEquals(
        "string",
        property(registration.descriptor().inputSchema().document(), "query").get("type"));
    assertEquals(
        "legs",
        registration
            .handler()
            .invoke(
                Map.of("query", "legs"),
                new ToolExecutionContext("user-3", "run-3", Set.of(), "operation-3")));
  }

  @Test
  void rejectsConflictingParameterMetadataAcrossOverrideHierarchy() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> scanner().scanRegistration(new ConflictingParameterImplementation()));

    assertTrue(exception.getMessage().contains("conflicting @AgentToolParam"));
  }

  @Test
  void rejectsConflictingToolContractsFromUnrelatedInterfacesDeterministically() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> scanner().scanRegistration(new ConflictingInterfaceImplementation()));
    var reversed =
        assertThrows(
            IllegalArgumentException.class,
            () -> scanner().scanRegistration(new ReversedConflictingInterfaceImplementation()));

    assertTrue(exception.getMessage().contains("conflicting @AgentTool metadata"));
    assertTrue(exception.getMessage().contains(LeftContract.class.getName()));
    assertTrue(exception.getMessage().contains(RightContract.class.getName()));
    assertEquals(exception.getMessage(), reversed.getMessage());
  }

  @Test
  void mergesIdenticalToolContractsFromUnrelatedInterfaces() throws Exception {
    var registration = scanner().scanRegistration(new IdenticalInterfaceImplementation());

    assertEquals("shared_contract", registration.descriptor().runtimeName());
    assertEquals(
        "arms",
        registration
            .handler()
            .invoke(
                Map.of("value", "arms"),
                new ToolExecutionContext("user-4", "run-4", Set.of(), "operation-4")));
  }

  private static SpringToolCatalogScanner scanner() {
    return new SpringToolCatalogScanner("build-proxy", List.of());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> property(Map<String, Object> schema, String name) {
    return ((Map<String, Map<String, Object>>) schema.get("properties")).get(name);
  }

  interface GenericTools<T> {
    @AgentTool(
        key = "fitness.generic",
        version = 1,
        runtimeName = "generic_query",
        displayName = "泛型查询",
        description = "通过泛型接口查询训练",
        whenToUse = "需要查询训练时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "查询结果")
    T query(
        @AgentToolParam(name = "value", description = "查询值", example = "legs") T value,
        ToolExecutionContext context);
  }

  interface StringTools extends GenericTools<String> {}

  static class StringGenericTools implements StringTools {
    @Override
    @GenericQueryTool
    public String query(
        @AgentToolParam(name = "value", description = "查询值", example = "legs") String value,
        ToolExecutionContext context) {
      return value + ":" + context.userId();
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @AgentTool(
      key = "fitness.generic",
      version = 1,
      runtimeName = "generic_query",
      displayName = "泛型查询",
      description = "通过泛型接口查询训练",
      whenToUse = "需要查询训练时",
      whenNotToUse = "需要写入时",
      applicationKey = "fitness",
      group = "test",
      outputDescription = "查询结果")
  @interface GenericQueryTool {}

  interface InterfaceParameterContract {
    String search(
        @AgentToolParam(name = "query", description = "查询值", example = "legs") String query);
  }

  static final class ImplementationMetadataOnly implements InterfaceParameterContract {
    @Override
    @AgentTool(
        key = "fitness.implementation_metadata",
        version = 1,
        runtimeName = "implementation_metadata",
        displayName = "实现元数据工具",
        description = "从接口合并参数契约",
        whenToUse = "需要验证参数继承时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "查询结果")
    public String search(String query) {
      return query;
    }
  }

  interface ConflictingParameterContract {
    String conflict(
        @AgentToolParam(name = "value", description = "查询值", example = "legs", maxLength = 8)
            String value);
  }

  static final class ConflictingParameterImplementation implements ConflictingParameterContract {
    @Override
    @AgentTool(
        key = "fitness.conflicting_parameter",
        version = 1,
        runtimeName = "conflicting_parameter",
        displayName = "冲突参数工具",
        description = "拒绝冲突的参数契约",
        whenToUse = "需要验证参数冲突时",
        whenNotToUse = "生产调用时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "查询结果")
    public String conflict(
        @AgentToolParam(name = "value", description = "查询值", example = "legs", maxLength = 12)
            String value) {
      return value;
    }
  }

  interface LeftContract {
    @AgentTool(
        key = "fitness.left_contract",
        version = 1,
        runtimeName = "left_contract",
        displayName = "左侧契约",
        description = "左侧接口声明的契约",
        whenToUse = "需要左侧契约时",
        whenNotToUse = "需要右侧契约时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果")
    String shared(
        @AgentToolParam(name = "value", description = "共享值", example = "arms") String value);
  }

  interface RightContract {
    @AgentTool(
        key = "fitness.right_contract",
        version = 1,
        runtimeName = "right_contract",
        displayName = "右侧契约",
        description = "右侧接口声明的契约",
        whenToUse = "需要右侧契约时",
        whenNotToUse = "需要左侧契约时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果")
    String shared(
        @AgentToolParam(name = "value", description = "共享值", example = "arms") String value);
  }

  static final class ConflictingInterfaceImplementation implements LeftContract, RightContract {
    @Override
    public String shared(String value) {
      return value;
    }
  }

  static final class ReversedConflictingInterfaceImplementation
      implements RightContract, LeftContract {
    @Override
    public String shared(String value) {
      return value;
    }
  }

  interface FirstIdenticalContract {
    @SharedContractTool
    String identical(
        @AgentToolParam(name = "value", description = "共享值", example = "arms") String value);
  }

  interface SecondIdenticalContract {
    @SharedContractTool
    String identical(
        @AgentToolParam(name = "value", description = "共享值", example = "arms") String value);
  }

  static final class IdenticalInterfaceImplementation
      implements FirstIdenticalContract, SecondIdenticalContract {
    @Override
    public String identical(String value) {
      return value;
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @AgentTool(
      key = "fitness.shared_contract",
      version = 1,
      runtimeName = "shared_contract",
      displayName = "共享契约",
      description = "两个接口共享的相同契约",
      whenToUse = "需要共享契约时",
      whenNotToUse = "需要写入时",
      applicationKey = "fitness",
      group = "test",
      outputDescription = "结果")
  @interface SharedContractTool {}
}
