package happy.jayden.yang.agentbuilder.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolSchemaRuntimeAlignmentTest {

  @Test
  void invokesRecordContainingNestedOptionalThroughGeneratedSchema() throws Exception {
    var registration = scanner().scanRegistration(new OptionalRecordTools());
    var context = new ToolExecutionContext("user-1", "run-1", Set.of(), "operation-1");

    assertEquals(
        "legs",
        registration.handler().invoke(Map.of("request", Map.of("muscle", "legs")), context));
    assertEquals("none", registration.handler().invoke(Map.of("request", Map.of()), context));
  }

  @Test
  void rejectsAnnotatedPrivateFieldWithoutCreatorOrSetter() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> scanner().scanRegistration(new PrivateFieldOnlyTools()));

    assertTrue(exception.getMessage().contains("writable bean property"));
  }

  private static SpringToolCatalogScanner scanner() {
    return new SpringToolCatalogScanner("build-shape", List.of());
  }

  record OptionalRequest(
      @AgentToolParam(name = "muscle", description = "可选肌群", example = "legs", required = false)
          Optional<String> muscle) {}

  static final class OptionalRecordTools {
    @AgentTool(
        key = "fitness.optional_record",
        version = 1,
        runtimeName = "optional_record",
        displayName = "可选记录工具",
        description = "验证嵌套可选参数可执行",
        whenToUse = "需要可选筛选时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "可选肌群结果")
    String query(
        @AgentToolParam(name = "request", description = "可选查询参数", example = "{\"muscle\":\"legs\"}")
            OptionalRequest request) {
      return request.muscle().orElse("none");
    }
  }

  static final class PrivateFieldOnlyRequest {
    @AgentToolParam(name = "value", description = "私有值", example = "legs")
    private String value;
  }

  static final class PrivateFieldOnlyTools {
    @AgentTool(
        key = "fitness.private_field",
        version = 1,
        runtimeName = "private_field",
        displayName = "私有字段工具",
        description = "拒绝不可构造的字段契约",
        whenToUse = "验证对象形状时",
        whenNotToUse = "生产调用时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果")
    String query(
        @AgentToolParam(name = "request", description = "不可构造请求", example = "{\"value\":\"legs\"}")
            PrivateFieldOnlyRequest request) {
      return request.value;
    }
  }
}
