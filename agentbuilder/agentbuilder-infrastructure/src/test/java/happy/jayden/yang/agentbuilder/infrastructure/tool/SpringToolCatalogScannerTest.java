package happy.jayden.yang.agentbuilder.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolLifecycleStatus;
import happy.jayden.yang.agentbuilder.core.tool.ToolRiskLevel;
import happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect;
import happy.jayden.yang.agentbuilder.core.tool.ToolSourceType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SpringToolCatalogScannerTest {

  @Test
  void discoversCompleteMetadataAndClosedSchemasWithoutTrustedContext() {
    var descriptor = scanner().scan(new HistoryTools());

    assertEquals("fitness.query_workout_history", descriptor.toolKey());
    assertEquals(1, descriptor.contractVersion());
    assertEquals("query_workout_history", descriptor.runtimeName());
    assertEquals("查询训练历史", descriptor.displayName());
    assertEquals("查询当前用户已完成的训练记录", descriptor.description());
    assertEquals("生成训练分析时", descriptor.whenToUse());
    assertEquals("修改训练计划时", descriptor.whenNotToUse());
    assertEquals("fitness", descriptor.applicationKey());
    assertEquals("workout", descriptor.group());
    assertEquals(List.of("fitness", "history"), descriptor.tags());
    assertTrue(descriptor.strictInput());
    assertEquals(ToolSideEffect.READ, descriptor.sideEffect());
    assertTrue(descriptor.idempotent());
    assertEquals(ToolRiskLevel.LOW, descriptor.riskLevel());
    assertEquals(List.of("workout:read"), descriptor.requiredScopes());
    assertEquals(2_500, descriptor.defaultTimeoutMs());
    assertEquals(5_000, descriptor.maxTimeoutMs());
    assertEquals(3, descriptor.defaultMaxCallsPerRun());
    assertFalse(descriptor.supportsStreaming());
    assertFalse(descriptor.returnDirect());
    assertEquals(ToolSourceType.LOCAL_BEAN, descriptor.sourceType());
    assertEquals(ToolLifecycleStatus.AVAILABLE, descriptor.status());
    assertTrue(descriptor.replacementTool().isEmpty());
    assertEquals("build-2026.08.05", descriptor.registeredBuild());
    assertTrue(descriptor.schemaChecksum().matches("[a-f0-9]{64}"));

    var input = descriptor.inputSchema().document();
    assertEquals("object", input.get("type"));
    assertEquals(false, input.get("additionalProperties"));
    assertEquals(List.of("request"), input.get("required"));
    var request = property(input, "request");
    assertEquals("查询范围", request.get("description"));
    assertEquals(List.of(Map.of("from_date", "2026-07-01")), request.get("examples"));
    assertEquals(false, request.get("additionalProperties"));
    assertEquals(List.of("from_date"), request.get("required"));
    var fromDate = property(request, "from_date");
    assertEquals("最早训练日期", fromDate.get("description"));
    assertEquals("^\\d{4}-\\d{2}-\\d{2}$", fromDate.get("pattern"));
    assertEquals(List.of("2026-07-01"), fromDate.get("examples"));
    var limit = property(request, "limit");
    assertEquals(1L, limit.get("minimum"));
    assertEquals(100L, limit.get("maximum"));

    var output = descriptor.outputSchema().document();
    assertEquals("object", output.get("type"));
    assertEquals(false, output.get("additionalProperties"));
    assertEquals(List.of("completed_count"), output.get("required"));
    assertEquals("完成训练数", property(output, "completed_count").get("description"));

    var modelVisibleSchema = input.toString();
    assertFalse(modelVisibleSchema.contains("userId"));
    assertFalse(modelVisibleSchema.contains("runId"));
    assertFalse(modelVisibleSchema.contains("grantedScopes"));
    assertFalse(modelVisibleSchema.contains("operationId"));
  }

  @Test
  void rejectsDuplicateRuntimeNamesAcrossDiscoveredBeans() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> scanner().scanAll(List.of(new DuplicateRuntimeOne(), new DuplicateRuntimeTwo())));

    assertTrue(exception.getMessage().contains("duplicate runtimeName"));
  }

  @Test
  void rejectsBlankModelVisibleDescriptions() {
    var exception =
        assertThrows(
            IllegalArgumentException.class, () -> scanner().scan(new BlankDescriptionTool()));

    assertTrue(exception.getMessage().contains("displayName"));
  }

  @Test
  void rejectsModelParametersWithoutValidDescriptions() {
    var exception =
        assertThrows(
            IllegalArgumentException.class, () -> scanner().scan(new UndescribedParameterTool()));

    assertTrue(exception.getMessage().contains("@AgentToolParam"));
  }

  @Test
  void rejectsChecksumDriftAtAnAlreadyRegisteredContractVersion() {
    var original = scanner().scan(new DriftVersionOne());
    var scannerWithHistory = new SpringToolCatalogScanner("build-2026.08.05", List.of(original));

    var exception =
        assertThrows(
            IllegalStateException.class,
            () -> scannerWithHistory.scan(new ChangedDriftVersionOne()));

    assertTrue(exception.getMessage().contains("increment contractVersion"));
  }

  @Test
  void acceptsChangedContractMetadataAfterVersionIncrement() {
    var original = scanner().scan(new DriftVersionOne());
    var scannerWithHistory = new SpringToolCatalogScanner("build-2026.08.05", List.of(original));

    var incremented = scannerWithHistory.scan(new DriftVersionTwo());

    assertEquals(2, incremented.contractVersion());
    assertFalse(original.schemaChecksum().equals(incremented.schemaChecksum()));
  }

  @Test
  void rejectsContractVersionThatSkipsPastHistoricalMaximumPlusOne() {
    var versionOne = scanner().scan(new DriftVersionOne());
    var versionTwo =
        new SpringToolCatalogScanner("build-2026.08.05", List.of(versionOne))
            .scan(new DriftVersionTwo());
    var scannerWithHistory =
        new SpringToolCatalogScanner("build-2026.08.05", List.of(versionOne, versionTwo));

    var exception =
        assertThrows(
            IllegalStateException.class, () -> scannerWithHistory.scan(new DriftVersionFour()));

    assertTrue(exception.getMessage().contains("next version 3"));
  }

  @Test
  void excludesLifecycleReplacementAndBuildRegistrationFromContractChecksum() {
    var original = scanner().scan(new DriftVersionOne());
    var scannerWithHistory = new SpringToolCatalogScanner("build-2026.08.06", List.of(original));

    var operationallyChanged = scannerWithHistory.scan(new OperationalMetadataChangedVersionOne());

    assertEquals(original.schemaChecksum(), operationallyChanged.schemaChecksum());
    assertEquals(ToolLifecycleStatus.DEPRECATED, operationallyChanged.status());
    assertTrue(operationallyChanged.replacementTool().isPresent());
    assertEquals("build-2026.08.06", operationallyChanged.registeredBuild());
  }

  @Test
  void includesExecutionSafetyLimitsInContractChecksum() {
    var original = scanner().scan(new DriftVersionOne());
    var scannerWithHistory = new SpringToolCatalogScanner("build-2026.08.05", List.of(original));

    var exception =
        assertThrows(
            IllegalStateException.class,
            () -> scannerWithHistory.scan(new SafetyChangedDriftVersionOne()));

    assertTrue(exception.getMessage().contains("increment contractVersion"));
  }

  @Test
  void discoversToolBeansFromSpringAndBuildsMetadataOnlyManifest() throws Exception {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(HistoryTools.class, HistoryTools::new);
      context.refresh();

      var scanner = scanner();
      var registrations = scanner.scanRegistrations(context);
      var manifest = scanner.buildManifest();

      assertEquals(1, registrations.size());
      assertEquals("build-2026.08.05", manifest.registeredBuild());
      assertEquals(1, manifest.availableTools().size());
      assertEquals(
          registrations.get(0).descriptor().toolKey(), manifest.availableTools().get(0).toolKey());
      assertEquals(
          registrations.get(0).descriptor().schemaChecksum(),
          manifest.availableTools().get(0).schemaChecksum());
      assertEquals(
          new HistoryResult(0),
          registrations
              .get(0)
              .handler()
              .invoke(
                  Map.of("request", Map.of("from_date", "2026-07-01")),
                  new ToolExecutionContext(
                      "user-1", "run-1", Set.of("workout:read"), "operation-1")));
    }
  }

  @Test
  void manifestRequiresCurrentAvailableExecutableRegistrations() {
    var scanner = scanner();
    assertThrows(IllegalStateException.class, scanner::buildManifest);

    scanner.scanRegistration(new OperationalMetadataChangedVersionOne());
    var exception = assertThrows(IllegalStateException.class, scanner::buildManifest);

    assertTrue(exception.getMessage().contains("AVAILABLE"));
    var cleared = assertThrows(IllegalStateException.class, scanner::buildManifest);
    assertTrue(cleared.getMessage().contains("scan current executable"));
  }

  @Test
  void exactOneScanFailureDoesNotCommitManifestState() {
    var scanner = scanner();
    scanner.scanRegistration(new HistoryTools());
    assertEquals(1, scanner.buildManifest().availableTools().size());

    assertThrows(
        IllegalArgumentException.class, () -> scanner.scanRegistration(new MultipleToolMethods()));

    assertThrows(IllegalStateException.class, scanner::buildManifest);
  }

  @Test
  void requiresExplicitNamesForMethodParameters() {
    var exception =
        assertThrows(IllegalArgumentException.class, () -> scanner().scan(new ImplicitNameTool()));

    assertTrue(exception.getMessage().contains("explicit"));
  }

  @Test
  void rejectsRollbackBelowCompleteHistoricalMaximumAndIncompleteHistory() {
    var versionOne = scanner().scan(new DriftVersionOne());
    var versionTwo =
        new SpringToolCatalogScanner("build-2026.08.05", List.of(versionOne))
            .scan(new DriftVersionTwo());

    assertThrows(IllegalStateException.class, () -> scanner().scan(new DriftVersionTwo()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpringToolCatalogScanner("build-2026.08.05", List.of(versionTwo)));
    var scannerWithCompleteHistory =
        new SpringToolCatalogScanner("build-2026.08.05", List.of(versionOne, versionTwo));
    var exception =
        assertThrows(
            IllegalStateException.class,
            () -> scannerWithCompleteHistory.scan(new DriftVersionOne()));

    assertTrue(exception.getMessage().contains("historical maximum"));
  }

  private static SpringToolCatalogScanner scanner() {
    return new SpringToolCatalogScanner("build-2026.08.05", List.of());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> property(Map<String, Object> schema, String name) {
    return ((Map<String, Map<String, Object>>) schema.get("properties")).get(name);
  }

  record HistoryRequest(
      @AgentToolParam(
              name = "from_date",
              description = "最早训练日期",
              example = "2026-07-01",
              pattern = "^\\d{4}-\\d{2}-\\d{2}$")
          String fromDate,
      @AgentToolParam(
              name = "limit",
              description = "最多返回条数",
              example = "10",
              required = false,
              minimum = 1,
              maximum = 100)
          Integer limit) {}

  record HistoryResult(
      @AgentToolParam(name = "completed_count", description = "完成训练数", example = "3")
          int completedCount) {}

  static final class HistoryTools {
    @AgentTool(
        key = "fitness.query_workout_history",
        version = 1,
        runtimeName = "query_workout_history",
        displayName = "查询训练历史",
        description = "查询当前用户已完成的训练记录",
        whenToUse = "生成训练分析时",
        whenNotToUse = "修改训练计划时",
        applicationKey = "fitness",
        group = "workout",
        tags = {"fitness", "history"},
        sideEffect = ToolSideEffect.READ,
        idempotent = true,
        risk = ToolRiskLevel.LOW,
        requiredScopes = "workout:read",
        defaultTimeoutMs = 2500,
        maxTimeoutMs = 5000,
        defaultMaxCallsPerRun = 3,
        supportsStreaming = false,
        returnDirect = false,
        status = ToolLifecycleStatus.AVAILABLE)
    HistoryResult history(
        @AgentToolParam(
                name = "request",
                description = "查询范围",
                example = "{\"from_date\":\"2026-07-01\"}")
            HistoryRequest request,
        ToolExecutionContext context) {
      return new HistoryResult(0);
    }
  }

  static final class DuplicateRuntimeOne {
    @AgentTool(
        key = "fitness.first",
        version = 1,
        runtimeName = "duplicate_runtime",
        displayName = "第一个工具",
        description = "读取第一组数据",
        whenToUse = "需要第一组数据时",
        whenNotToUse = "需要写入数据时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "第一组数据")
    String first() {
      return "first";
    }
  }

  static final class DuplicateRuntimeTwo {
    @AgentTool(
        key = "fitness.second",
        version = 1,
        runtimeName = "duplicate_runtime",
        displayName = "第二个工具",
        description = "读取第二组数据",
        whenToUse = "需要第二组数据时",
        whenNotToUse = "需要写入数据时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "第二组数据")
    String second() {
      return "second";
    }
  }

  static final class BlankDescriptionTool {
    @AgentTool(
        key = "fitness.blank",
        version = 1,
        runtimeName = "blank_description",
        displayName = " ",
        description = "有效工具说明",
        whenToUse = "需要读取时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果")
    String blank() {
      return "blank";
    }
  }

  static final class UndescribedParameterTool {
    @AgentTool(
        key = "fitness.undescribed",
        version = 1,
        runtimeName = "undescribed_parameter",
        displayName = "参数未说明工具",
        description = "验证参数必须有模型说明",
        whenToUse = "验证元数据时",
        whenNotToUse = "生产调用时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "验证结果")
    String undescribed(String query) {
      return query;
    }
  }

  static final class DriftVersionOne {
    @AgentTool(
        key = "fitness.drift",
        version = 1,
        runtimeName = "contract_drift",
        displayName = "稳定契约工具",
        description = "原始模型可见说明",
        whenToUse = "需要稳定数据时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "稳定结果")
    String stable() {
      return "stable";
    }
  }

  static final class ChangedDriftVersionOne {
    @AgentTool(
        key = "fitness.drift",
        version = 1,
        runtimeName = "contract_drift",
        displayName = "稳定契约工具",
        description = "版本未变但模型说明已变化",
        whenToUse = "需要稳定数据时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "稳定结果")
    String changed() {
      return "changed";
    }
  }

  static final class DriftVersionTwo {
    @AgentTool(
        key = "fitness.drift",
        version = 2,
        runtimeName = "contract_drift",
        displayName = "稳定契约工具",
        description = "版本已升级且模型说明已变化",
        whenToUse = "需要稳定数据时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "稳定结果")
    String changed() {
      return "changed";
    }
  }

  static final class DriftVersionFour {
    @AgentTool(
        key = "fitness.drift",
        version = 4,
        runtimeName = "contract_drift",
        displayName = "稳定契约工具",
        description = "版本跳跃且模型说明已变化",
        whenToUse = "需要稳定数据时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "稳定结果")
    String changed() {
      return "changed";
    }
  }

  static final class OperationalMetadataChangedVersionOne {
    @AgentTool(
        key = "fitness.drift",
        version = 1,
        runtimeName = "contract_drift",
        displayName = "稳定契约工具",
        description = "原始模型可见说明",
        whenToUse = "需要稳定数据时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "稳定结果",
        status = ToolLifecycleStatus.DEPRECATED,
        replacementKey = "fitness.replacement",
        replacementVersion = 2)
    String changed() {
      return "changed";
    }
  }

  static final class SafetyChangedDriftVersionOne {
    @AgentTool(
        key = "fitness.drift",
        version = 1,
        runtimeName = "contract_drift",
        displayName = "稳定契约工具",
        description = "原始模型可见说明",
        whenToUse = "需要稳定数据时",
        whenNotToUse = "需要写入时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "稳定结果",
        defaultTimeoutMs = 6000)
    String changed() {
      return "changed";
    }
  }

  static final class ImplicitNameTool {
    @AgentTool(
        key = "fitness.implicit",
        version = 1,
        runtimeName = "implicit_name",
        displayName = "隐式参数工具",
        description = "验证模型参数必须显式命名",
        whenToUse = "验证契约时",
        whenNotToUse = "实际运行时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果")
    String implicit(@AgentToolParam(description = "查询词", example = "legs") String query) {
      return query;
    }
  }

  static final class MultipleToolMethods {
    @AgentTool(
        key = "fitness.multi_one",
        version = 1,
        runtimeName = "multi_one",
        displayName = "多个工具一",
        description = "验证单工具扫描基数",
        whenToUse = "测试单工具扫描时",
        whenNotToUse = "生产调用时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果一")
    String one() {
      return "one";
    }

    @AgentTool(
        key = "fitness.multi_two",
        version = 1,
        runtimeName = "multi_two",
        displayName = "多个工具二",
        description = "验证单工具扫描基数",
        whenToUse = "测试单工具扫描时",
        whenNotToUse = "生产调用时",
        applicationKey = "fitness",
        group = "test",
        outputDescription = "结果二")
    String two() {
      return "two";
    }
  }
}
