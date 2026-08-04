package happy.jayden.yang.agentbuilder.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
