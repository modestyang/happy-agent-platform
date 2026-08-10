package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect;
import happy.jayden.yang.agentbuilder.infrastructure.tool.SpringToolCatalogScanner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingChatClientTest {

  @Test
  void visibleContentRemovesMiniMaxThinkingBlocksFromTextAndJson() {
    assertEquals(
        "今天先走十分钟。",
        StreamingChatClient.visibleContent("<think>internal\nreasoning</think>\n\n今天先走十分钟。"));
    assertEquals(
        "{\"recommendations\":[]}",
        StreamingChatClient.visibleContent(
            "<think>schema planning</think>{\"recommendations\":[]}"));
    assertEquals("普通回答", StreamingChatClient.visibleContent("普通回答"));
    assertEquals("", StreamingChatClient.visibleContent("<think>unfinished reasoning"));
  }

  @Test
  void visibleJsonContentUnwrapsAJsonMarkdownFenceAfterThinking() {
    assertEquals(
        "{\n  \"recommendations\": []\n}",
        StreamingChatClient.visibleJsonContent(
            "<think>schema planning</think>\n```json\n{\n  \"recommendations\": []\n}\n```"));
    assertEquals(
        "{\"recommendations\":[]}",
        StreamingChatClient.visibleJsonContent("{\"recommendations\":[]}"));
  }

  @Test
  void visibleDeltaFilterNeverLeaksThinkingAcrossChunkBoundaries() {
    var visible = new ArrayList<String>();
    var filter = new StreamingChatClient.VisibleDeltaFilter(visible::add);

    filter.accept("<thi");
    filter.accept("nk>internal");
    filter.accept(" reasoning</think>\n\n## 今");
    filter.accept("日计划");
    filter.finish();

    assertEquals("## 今日计划", String.join("", visible));
  }

  @Test
  void exposesBoundRuntimeToolSchemasUsingOpenAiFunctionCallingShape() {
    var descriptor =
        new SpringToolCatalogScanner("test", List.of())
            .scanRegistration(new PlanTools())
            .descriptor();

    var tools = StreamingChatClient.openAiTools(List.of(descriptor));

    assertEquals("function", tools.get(0).get("type"));
    var function = (java.util.Map<?, ?>) tools.get(0).get("function");
    assertEquals("save_fitness_plan", function.get("name"));
    assertTrue(((java.util.Map<?, ?>) function.get("parameters")).containsKey("properties"));
  }

  @Test
  void parsesStreamingFunctionCallArgumentFragments() {
    var chunk =
        StreamingChatClient.parseChunk(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"save_fitness_plan\",\"arguments\":\"{\\\"scope\\\":\"}}]}}]}");

    assertEquals("call-1", chunk.toolCallDeltas().get(0).id());
    assertEquals("save_fitness_plan", chunk.toolCallDeltas().get(0).name());
    assertEquals("{\"scope\":", chunk.toolCallDeltas().get(0).arguments());
  }

  static final class PlanTools {
    @AgentTool(
        key = "fitness.plan.save",
        version = 1,
        runtimeName = "save_fitness_plan",
        displayName = "保存训练计划",
        description = "保存用户确认的训练计划",
        whenToUse = "用户确认保存计划时",
        whenNotToUse = "用户尚未确认时",
        applicationKey = "fitness",
        group = "plan",
        sideEffect = ToolSideEffect.WRITE,
        outputDescription = "保存结果")
    String save(@AgentToolParam(name = "scope", description = "计划范围") String scope) {
      return scope;
    }
  }
}
