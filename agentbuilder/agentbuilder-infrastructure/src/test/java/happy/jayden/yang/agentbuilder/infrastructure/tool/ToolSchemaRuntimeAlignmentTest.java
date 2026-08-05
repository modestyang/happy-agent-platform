package happy.jayden.yang.agentbuilder.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

  @Test
  void invokesNestedRecordContainingEverySupportedJavaTimeType() throws Exception {
    var registration = scanner().scanRegistration(new JavaTimeTools());

    var result =
        registration
            .handler()
            .invoke(
                Map.of(
                    "request",
                    Map.of(
                        "window",
                        Map.of(
                            "instant",
                            "2026-08-05T01:02:03Z",
                            "date",
                            "2026-08-05",
                            "local_date_time",
                            "2026-08-05T09:10:11",
                            "offset_date_time",
                            "2026-08-05T09:10:11+08:00"))),
                new ToolExecutionContext("user-2", "run-2", Set.of(), "operation-2"));

    assertEquals(
        "2026-08-05T01:02:03Z|2026-08-05|2026-08-05T09:10:11|2026-08-05T09:10:11+08:00", result);
  }

  @Test
  void explicitlyRejectsInterfaceDto() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> scanner().scanRegistration(new InterfaceRequestTools()));

    assertTrue(exception.getMessage().contains("must be a concrete non-record class"));
  }

  @Test
  void explicitlyRejectsAbstractDto() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> scanner().scanRegistration(new AbstractRequestTools()));

    assertTrue(exception.getMessage().contains("must be a concrete non-record class"));
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

  record JavaTimeWindow(
      @AgentToolParam(name = "instant", description = "绝对时间", example = "2026-08-05T01:02:03Z")
          Instant instant,
      @AgentToolParam(name = "date", description = "本地日期", example = "2026-08-05") LocalDate date,
      @AgentToolParam(
              name = "local_date_time",
              description = "本地日期时间",
              example = "2026-08-05T09:10:11")
          LocalDateTime localDateTime,
      @AgentToolParam(
              name = "offset_date_time",
              description = "偏移日期时间",
              example = "2026-08-05T09:10:11+08:00")
          OffsetDateTime offsetDateTime) {}

  record JavaTimeRequest(
      @AgentToolParam(
              name = "window",
              description = "日期时间窗口",
              example =
                  "{\"instant\":\"2026-08-05T01:02:03Z\",\"date\":\"2026-08-05\",\"local_date_time\":\"2026-08-05T09:10:11\",\"offset_date_time\":\"2026-08-05T09:10:11+08:00\"}")
          JavaTimeWindow window) {}

  static final class JavaTimeTools {
    @AgentTool(
        key = "fitness.java_time",
        version = 1,
        runtimeName = "java_time",
        displayName = "日期时间工具",
        description = "验证日期时间契约真实可执行",
        whenToUse = "需要日期时间转换时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "日期时间结果")
    String query(
        @AgentToolParam(
                name = "request",
                description = "嵌套日期时间请求",
                example =
                    "{\"window\":{\"instant\":\"2026-08-05T01:02:03Z\",\"date\":\"2026-08-05\",\"local_date_time\":\"2026-08-05T09:10:11\",\"offset_date_time\":\"2026-08-05T09:10:11+08:00\"}}")
            JavaTimeRequest request) {
      var window = request.window();
      return window.instant()
          + "|"
          + window.date()
          + "|"
          + window.localDateTime()
          + "|"
          + window.offsetDateTime();
    }
  }

  interface InterfaceRequest {}

  static final class InterfaceRequestTools {
    @AgentTool(
        key = "fitness.interface_request",
        version = 1,
        runtimeName = "interface_request",
        displayName = "接口请求工具",
        description = "拒绝接口请求类型",
        whenToUse = "验证请求类型时",
        whenNotToUse = "生产调用时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果")
    String query(
        @AgentToolParam(name = "request", description = "接口请求", example = "{}")
            InterfaceRequest request) {
      return request.toString();
    }
  }

  abstract static class AbstractRequest {
    @AgentToolParam(name = "value", description = "抽象请求值", example = "legs")
    public String value;

    public AbstractRequest() {}
  }

  static final class AbstractRequestTools {
    @AgentTool(
        key = "fitness.abstract_request",
        version = 1,
        runtimeName = "abstract_request",
        displayName = "抽象请求工具",
        description = "拒绝抽象请求类型",
        whenToUse = "验证请求类型时",
        whenNotToUse = "生产调用时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果")
    String query(
        @AgentToolParam(name = "request", description = "抽象请求", example = "{\"value\":\"legs\"}")
            AbstractRequest request) {
      return request.value;
    }
  }
}
