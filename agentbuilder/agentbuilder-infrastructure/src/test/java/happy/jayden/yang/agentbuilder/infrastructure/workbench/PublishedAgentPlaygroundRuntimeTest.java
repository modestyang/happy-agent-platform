package happy.jayden.yang.agentbuilder.infrastructure.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import happy.jayden.yang.agentbuilder.core.runtime.AgentFrameworkAdapter;
import happy.jayden.yang.agentbuilder.core.runtime.AgentFrameworkRegistry;
import happy.jayden.yang.agentbuilder.core.runtime.AssistantReply;
import happy.jayden.yang.agentbuilder.core.runtime.FrameworkCapabilities;
import happy.jayden.yang.agentbuilder.core.runtime.ResponseBlock;
import happy.jayden.yang.agentbuilder.core.runtime.RunEvent;
import happy.jayden.yang.agentbuilder.core.runtime.RunRequest;
import happy.jayden.yang.agentbuilder.core.tool.AgentTool;
import happy.jayden.yang.agentbuilder.core.tool.AgentToolParam;
import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolRiskLevel;
import happy.jayden.yang.agentbuilder.core.tool.ToolSideEffect;
import happy.jayden.yang.agentbuilder.infrastructure.tool.DefaultToolRegistry;
import happy.jayden.yang.agentbuilder.infrastructure.tool.SpringToolCatalogScanner;
import happy.jayden.yang.agentbuilder.service.workbench.AdminResourceDtos.ProviderUpdate;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.CreateAgentRequest;
import happy.jayden.yang.agentbuilder.service.workbench.AdminWorkbenchDtos.DraftUpdate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

@Testcontainers(disabledWithoutDocker = true)
class PublishedAgentPlaygroundRuntimeTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private HttpServer modelServer;
  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private JdbcAdminWorkbenchStore workbench;
  private JdbcAdminResourceStore resources;
  private JdbcRunTraceRepository traces;
  private Path masterKey;

  @BeforeEach
  void setUp() throws Exception {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA IF EXISTS public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    try (var connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/agent/V1__agent_baseline.sql"));
    }
    var mapper = new ObjectMapper().findAndRegisterModules();
    masterKey = Files.createTempFile("happy-agent-generic-runtime", ".key");
    Files.writeString(
        masterKey, Base64.getEncoder().encodeToString(new byte[32]), StandardCharsets.US_ASCII);
    workbench = new JdbcAdminWorkbenchStore(dataSource, mapper, masterKey);
    resources = new JdbcAdminResourceStore(dataSource, mapper);
    traces = new JdbcRunTraceRepository(dataSource);

    modelServer = HttpServer.create(new InetSocketAddress(0), 0);
    modelServer.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] response =
              ("data: {\"choices\":[{\"delta\":{\"content\":\"今晚吃\"}}]}\n\n"
                      + "data: {\"choices\":[{\"delta\":{\"content\":\"清淡一些。\"}}]}\n\n"
                      + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6}}\n\n"
                      + "data: [DONE]\n\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    modelServer.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (modelServer != null) modelServer.stop(0);
    Files.deleteIfExists(masterKey);
  }

  @Test
  void treatsConfirmationAsRuntimeInteractionRatherThanTextRoundTrip() throws Exception {
    var method =
        PublishedAgentPlaygroundRuntime.class.getDeclaredMethod(
            "runtimeSystemPrompt", String.class);
    method.setAccessible(true);

    String prompt = (String) method.invoke(null, "你是健身助手。");

    assertTrue(prompt.contains("不得要求用户通过文字再次确认"));
  }

  @Test
  void streamsAnyPublishedAgentAndPersistsItsRunConversationAndTrace() throws Exception {
    var provider =
        resources.listProviders().stream()
            .filter(item -> item.providerKey().equals("minimax"))
            .findFirst()
            .orElseThrow();
    resources.updateProvider(
        "minimax",
        new ProviderUpdate(
            provider.displayName(),
            "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1",
            "ACTIVE"),
        provider.revision());
    workbench.saveCredential("minimax", "sk-test-generic-agent".toCharArray());
    var draft = workbench.createDraft(new CreateAgentRequest("baby.food", "辅食助手", "为家庭提供辅食安排建议"));
    workbench.publish(draft);

    var adapter = new TestAdapter();
    var runtime =
        new PublishedAgentPlaygroundRuntime(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            masterKey,
            traces,
            new AgentFrameworkRegistry(java.util.List.of(adapter)),
            new DefaultToolRegistry(java.util.List.of()),
            (hookKey, context) -> {});

    var started = runtime.startStreaming("baby.food", "晚饭吃什么", Runnable::run);

    assertEquals("baby.food", started.agentKey());
    assertEquals(1, started.agentVersion());
    assertEquals("MiniMax-M3", adapter.modelName());
    var trace = traces.findTrace(started.runId()).orElseThrow();
    assertEquals("SUCCEEDED", trace.status());
    assertEquals("今晚吃清淡一些。", trace.outputSummary());
    assertTrue(
        traces.streamEventsAfter(started.runId(), 0).stream()
            .anyMatch(
                event ->
                    event.type().equals("RUN_EVENT")
                        && event.data().get("eventType").equals("BLOCK_DELTA")
                        && event.data().get("delta").equals("今晚吃清淡一些。")));
    assertEquals(
        "今晚吃清淡一些。",
        traces
            .findConversation(started.conversationId())
            .orElseThrow()
            .messages()
            .get(1)
            .content());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM agent_runs WHERE run_id=? AND agent_key='baby.food'",
            Integer.class,
            started.runId()));
  }

  @Test
  void usesTheBackendCreatedConversationWithTheLatestPublishedAgentVersion() throws Exception {
    configureMiniMaxCredential();
    var draft =
        workbench.createDraft(new CreateAgentRequest("fitness.session", "健身会话", "验证应用端会话归属后端"));
    workbench.publish(draft);
    var adapter = new TestAdapter();
    var runtime =
        new PublishedAgentPlaygroundRuntime(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            masterKey,
            traces,
            new AgentFrameworkRegistry(List.of(adapter)),
            new DefaultToolRegistry(List.of()),
            (hookKey, context) -> {});
    UUID userId = UUID.randomUUID();
    var session = runtime.createConversation("fitness.session", userId);

    var current = workbench.findDraft("fitness.session").orElseThrow();
    var updated =
        workbench.updateDraft(
            "fitness.session",
            new DraftUpdate(
                current.name(),
                "第二个已发布版本",
                current.frameworkKey(),
                current.providerKey(),
                current.modelKey(),
                current.promptKey(),
                current.toolKeys(),
                current.skillKeys(),
                current.hookKeys(),
                current.memoryKey(),
                current.temperature(),
                current.maxToolCalls()),
            current.revision());
    workbench.publish(updated);

    var started =
        runtime.startStreaming(
            "fitness.session", userId, session.conversationId(), "继续聊", Runnable::run);

    assertEquals(session.conversationId(), started.conversationId());
    assertEquals(2, started.agentVersion());
  }

  @Test
  void backgroundTaskLoadsOnlyTheRequiredPublishedSkillAndItsDeclaredTools() throws Exception {
    configureMiniMaxCredential();
    jdbc.update(
        "UPDATE agent_skills SET content='# 每日三餐', required_tool_keys='[\"test.read\"]'::jsonb"
            + " WHERE skill_key='fitness.meal.skill'");
    jdbc.update(
        "UPDATE agent_drafts SET tool_keys='[\"test.read\",\"test.write\"]'::jsonb,"
            + " skill_keys='[\"fitness.plan.skill\",\"fitness.meal.skill\"]'::jsonb"
            + " WHERE agent_key='fitness.coach'");
    workbench.publish(workbench.findDraft("fitness.coach").orElseThrow());
    var adapter = new TaskCapturingAdapter();
    var toolRegistry =
        new DefaultToolRegistry(
            new SpringToolCatalogScanner("test", List.of())
                .scanRegistrations(List.of(new BackgroundTaskTools())));
    var runtime =
        new PublishedAgentPlaygroundRuntime(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            masterKey,
            traces,
            new AgentFrameworkRegistry(List.of(adapter)),
            toolRegistry,
            (hookKey, context) -> {});
    UUID userId = UUID.randomUUID();

    var result =
        runtime.runTask("fitness.coach", userId, "fitness.meal.skill", "生成 2026-08-12 的三餐计划");

    assertEquals("{\"recommendations\":[]}", result.output());
    assertEquals("fitness.meal.skill", result.skillKey());
    assertEquals(List.of("fitness.meal.skill"), adapter.skillKeys());
    assertEquals(List.of("test.read"), adapter.toolKeys());
    assertTrue(adapter.memoryEntries().isEmpty());
    assertTrue(adapter.toolOperationId().startsWith("fitness.background-task:"));
    UUID taskConversation =
        jdbc.queryForObject(
            "SELECT conversation_id FROM agent_runs WHERE run_id=?", UUID.class, result.runId());
    String taskConversationAgent =
        jdbc.queryForObject(
            "SELECT agent_key FROM agent_conversations WHERE conversation_id=?",
            String.class,
            taskConversation);
    assertEquals("fitness.coach:background:fitness.meal.skill", taskConversationAgent);
    assertFalse(
        traces
            .resolveConversation(userId, "fitness.coach", Instant.now())
            .conversationId()
            .equals(taskConversation));
  }

  @Test
  void projectsTrustedExerciseNamesWithoutChangingFrozenPlanArguments() throws Exception {
    configureMiniMaxCredential();
    UUID knownId = UUID.fromString("60000000-0000-0000-0000-000000000001");
    UUID unknownId = UUID.fromString("60000000-0000-0000-0000-000000000002");
    var runtime =
        workoutPlanRuntime(
            "plan.display",
            List.of("fitness.exercise.candidates.query", "fitness.plan.save"),
            knownId,
            unknownId,
            List.of(new TrustedExerciseCandidateTool(), new ConfirmedPlanTool()));

    var started =
        runtime.startStreaming("plan.display", UUID.randomUUID(), "保存全身训练计划", Runnable::run);

    var approvalEvent =
        traces.streamEventsAfter(started.runId(), 0).stream()
            .filter(event -> event.type().equals("APPROVAL"))
            .findFirst()
            .orElseThrow();
    var proposal = assertInstanceOf(Map.class, approvalEvent.data().get("proposal"));
    var days = assertInstanceOf(List.class, proposal.get("days"));
    var day = assertInstanceOf(Map.class, days.get(0));
    var exercises = assertInstanceOf(List.class, day.get("exercises"));
    var knownExercise = assertInstanceOf(Map.class, exercises.get(0));
    var unknownExercise = assertInstanceOf(Map.class, exercises.get(1));
    assertEquals("深蹲", knownExercise.get("name"));
    assertEquals("动作 2", unknownExercise.get("name"));
    assertFalse(knownId.toString().equals(knownExercise.get("name")));
    assertFalse(unknownId.toString().equals(unknownExercise.get("name")));

    UUID approvalId =
        jdbc.queryForObject(
            "SELECT approval_id FROM agent_run_approvals WHERE run_id=?",
            UUID.class,
            started.runId());
    var stored = traces.findApproval(started.runId(), approvalId).orElseThrow();
    var frozenRequest = assertInstanceOf(Map.class, stored.arguments().get("request"));
    var frozenDays = assertInstanceOf(List.class, frozenRequest.get("days"));
    var frozenDay = assertInstanceOf(Map.class, frozenDays.get(0));
    assertFalse(frozenDay.containsKey("exercises"));
    assertEquals(List.of(knownId.toString(), unknownId.toString()), frozenDay.get("exerciseIds"));
  }

  @Test
  void ignoresExerciseNamesFromUnboundToolResults() {
    configureMiniMaxCredential();
    UUID knownId = UUID.fromString("60000000-0000-0000-0000-000000000001");
    UUID unknownId = UUID.fromString("60000000-0000-0000-0000-000000000002");
    var runtime =
        workoutPlanRuntime(
            "plan.unbound-display",
            List.of("fitness.plan.save"),
            knownId,
            unknownId,
            List.of(new ConfirmedPlanTool()));

    var started =
        runtime.startStreaming(
            "plan.unbound-display", UUID.randomUUID(), "保存全身训练计划", Runnable::run);

    var approvalEvent =
        traces.streamEventsAfter(started.runId(), 0).stream()
            .filter(event -> event.type().equals("APPROVAL"))
            .findFirst()
            .orElseThrow();
    var proposal = assertInstanceOf(Map.class, approvalEvent.data().get("proposal"));
    var days = assertInstanceOf(List.class, proposal.get("days"));
    var day = assertInstanceOf(Map.class, days.get(0));
    var exercises = assertInstanceOf(List.class, day.get("exercises"));
    assertEquals("动作 1", assertInstanceOf(Map.class, exercises.get(0)).get("name"));
  }

  private PublishedAgentPlaygroundRuntime workoutPlanRuntime(
      String agentKey,
      List<String> toolKeys,
      UUID knownId,
      UUID unknownId,
      List<Object> toolBeans) {
    var draft = workbench.createDraft(new CreateAgentRequest(agentKey, "计划确认展示", "使用可信动作名称展示保存确认"));
    workbench.publish(
        workbench.updateDraft(
            agentKey,
            new DraftUpdate(
                draft.name(),
                draft.description(),
                draft.frameworkKey(),
                draft.providerKey(),
                draft.modelKey(),
                draft.promptKey(),
                toolKeys,
                draft.skillKeys(),
                draft.hookKeys(),
                draft.memoryKey(),
                draft.temperature(),
                draft.maxToolCalls()),
            draft.revision()));
    var scanner = new SpringToolCatalogScanner("test", List.of());
    return new PublishedAgentPlaygroundRuntime(
        dataSource,
        new ObjectMapper().findAndRegisterModules(),
        masterKey,
        traces,
        new AgentFrameworkRegistry(List.of(new WorkoutPlanConfirmationAdapter(knownId, unknownId))),
        new DefaultToolRegistry(toolBeans.stream().map(scanner::scanRegistration).toList()),
        (hookKey, context) -> {});
  }

  @Test
  void freezesFrameworkToolArgumentsUntilTheRunOwnerApproves() throws Exception {
    configureMiniMaxCredential();
    var draft = workbench.createDraft(new CreateAgentRequest("plan.writer", "计划保存", "确认后写入计划"));
    workbench.publish(
        workbench.updateDraft(
            "plan.writer",
            new DraftUpdate(
                draft.name(),
                draft.description(),
                draft.frameworkKey(),
                draft.providerKey(),
                draft.modelKey(),
                draft.promptKey(),
                List.of("test.write"),
                draft.skillKeys(),
                draft.hookKeys(),
                draft.memoryKey(),
                draft.temperature(),
                draft.maxToolCalls()),
            draft.revision()));
    var savedNote = new AtomicReference<String>();
    var invocations = new AtomicInteger();
    var tools =
        new DefaultToolRegistry(
            List.of(
                new SpringToolCatalogScanner("test", List.of())
                    .scanRegistration(new ConfirmedWriteTool(savedNote, invocations))));
    var runtime =
        new PublishedAgentPlaygroundRuntime(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            masterKey,
            traces,
            new AgentFrameworkRegistry(List.of(new ConfirmationAdapter())),
            tools,
            (hookKey, context) -> {});

    UUID runOwner = UUID.randomUUID();
    var started = runtime.startStreaming("plan.writer", runOwner, "保存这份计划", Runnable::run);

    assertEquals("WAITING_APPROVAL", traces.findTrace(started.runId()).orElseThrow().status());
    assertEquals(runOwner, traces.findRunOwner(started.runId()).orElseThrow());
    UUID approvalId =
        jdbc.queryForObject(
            "SELECT approval_id FROM agent_run_approvals WHERE run_id=?",
            UUID.class,
            started.runId());
    var approval = traces.findApproval(started.runId(), approvalId).orElseThrow();
    assertEquals("test.write", approval.toolKey());
    assertEquals("模型生成的计划", approval.arguments().get("note"));
    assertEquals(null, savedNote.get());

    runtime.decide(started.runId(), runOwner, approvalId, "APPROVE", "approval-key-1");
    runtime.decide(started.runId(), runOwner, approvalId, "APPROVE", "approval-key-1");

    assertEquals("模型生成的计划", savedNote.get());
    assertEquals(1, invocations.get());
    assertEquals("SUCCEEDED", traces.findTrace(started.runId()).orElseThrow().status());
  }

  @Test
  void acceptsTheSingleToolConfirmationShapeEmittedBySpringAiAlibaba() throws Exception {
    configureMiniMaxCredential();
    var draft = workbench.createDraft(new CreateAgentRequest("plan.writer.saa", "计划保存", "确认后写入计划"));
    workbench.publish(
        workbench.updateDraft(
            "plan.writer.saa",
            new DraftUpdate(
                draft.name(),
                draft.description(),
                "spring-ai-alibaba",
                draft.providerKey(),
                draft.modelKey(),
                draft.promptKey(),
                List.of("test.write"),
                draft.skillKeys(),
                draft.hookKeys(),
                draft.memoryKey(),
                draft.temperature(),
                draft.maxToolCalls()),
            draft.revision()));
    var runtime =
        new PublishedAgentPlaygroundRuntime(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            masterKey,
            traces,
            new AgentFrameworkRegistry(List.of(new DirectConfirmationAdapter())),
            new DefaultToolRegistry(
                List.of(
                    new SpringToolCatalogScanner("test", List.of())
                        .scanRegistration(new ConfirmedWriteTool(new AtomicReference<>())))),
            (hookKey, context) -> {});

    var started =
        runtime.startStreaming("plan.writer.saa", UUID.randomUUID(), "保存这份计划", Runnable::run);

    UUID approvalId =
        jdbc.queryForObject(
            "SELECT approval_id FROM agent_run_approvals WHERE run_id=?",
            UUID.class,
            started.runId());
    var approval = traces.findApproval(started.runId(), approvalId).orElseThrow();
    assertEquals("test.write", approval.toolKey());
    assertEquals("SAA 生成的计划", approval.arguments().get("note"));
    assertEquals("WAITING_APPROVAL", traces.findTrace(started.runId()).orElseThrow().status());
  }

  private void configureMiniMaxCredential() {
    var provider =
        resources.listProviders().stream()
            .filter(item -> item.providerKey().equals("minimax"))
            .findFirst()
            .orElseThrow();
    resources.updateProvider(
        "minimax",
        new ProviderUpdate(
            provider.displayName(),
            "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1",
            "ACTIVE"),
        provider.revision());
    workbench.saveCredential("minimax", "sk-test-generic-agent".toCharArray());
  }

  private static final class TestAdapter implements AgentFrameworkAdapter {
    private RunRequest request;

    @Override
    public String key() {
      return "agentscope";
    }

    @Override
    public FrameworkCapabilities capabilities() {
      return new FrameworkCapabilities(true, true, true, true, true, true);
    }

    @Override
    public void validate(happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig config) {}

    @Override
    public Flux<RunEvent> run(RunRequest request) {
      this.request = request;
      Instant now = Instant.now();
      return Flux.just(
          RunEvent.replyStarted(1, now, "reply-1", request.runId()),
          RunEvent.blockStarted(
              2,
              now,
              "reply-1",
              new ResponseBlock.Text("text-1", "", ResponseBlock.Fidelity.NATIVE)),
          RunEvent.blockDelta(3, now, "reply-1", "text-1", "今晚吃清淡一些。"),
          RunEvent.blockCompleted(4, now, "reply-1", "text-1"),
          RunEvent.replyEnded(5, now, "reply-1", AssistantReply.FinishReason.COMPLETED, ""),
          new RunEvent(6, RunEvent.Type.RUN_COMPLETED, now, java.util.Map.of()));
    }

    String modelName() {
      return request.model().modelName();
    }
  }

  private static final class ConfirmationAdapter implements AgentFrameworkAdapter {
    @Override
    public String key() {
      return "agentscope";
    }

    @Override
    public FrameworkCapabilities capabilities() {
      return new FrameworkCapabilities(true, true, true, true, true, true);
    }

    @Override
    public void validate(happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig config) {}

    @Override
    public Flux<RunEvent> run(RunRequest request) {
      Instant now = Instant.now();
      return Flux.just(
          RunEvent.replyStarted(1, now, "reply-1", request.runId()),
          RunEvent.blockStarted(
              2,
              now,
              "reply-1",
              new ResponseBlock.Text("text-1", "", ResponseBlock.Fidelity.NATIVE)),
          RunEvent.blockDelta(3, now, "reply-1", "text-1", "已准备好训练计划，等待你的确认。"),
          RunEvent.blockCompleted(4, now, "reply-1", "text-1"),
          RunEvent.blockStarted(
              5,
              now,
              "reply-1",
              new ResponseBlock.ToolCall(
                  "tool-call-call-1",
                  "call-1",
                  "test_write",
                  "",
                  ResponseBlock.ToolCallState.PENDING,
                  ResponseBlock.Fidelity.NATIVE)),
          RunEvent.blockDelta(6, now, "reply-1", "tool-call-call-1", "{\"note\":\"模型生成的计划\""),
          RunEvent.blockCompleted(7, now, "reply-1", "tool-call-call-1"),
          new RunEvent(
              8,
              RunEvent.Type.CONFIRMATION_REQUIRED,
              now,
              java.util.Map.of(
                  "replyId",
                  "reply-1",
                  "toolCalls",
                  List.of(
                      java.util.Map.of(
                          "toolCallId",
                          "call-1",
                          "toolName",
                          "test_write",
                          "arguments",
                          java.util.Map.of())))),
          new RunEvent(
              9, RunEvent.Type.RUN_WAITING_APPROVAL, now, java.util.Map.of("replyId", "reply-1")),
          RunEvent.replySuspended(10, now, "reply-1", "USER_CONFIRMATION"));
    }
  }

  private static final class TaskCapturingAdapter implements AgentFrameworkAdapter {
    private RunRequest request;

    @Override
    public String key() {
      return "agentscope";
    }

    @Override
    public FrameworkCapabilities capabilities() {
      return new FrameworkCapabilities(true, true, true, true, true, true);
    }

    @Override
    public void validate(happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig config) {}

    @Override
    public Flux<RunEvent> run(RunRequest request) {
      this.request = request;
      Instant now = Instant.now();
      return Flux.just(
          RunEvent.replyStarted(1, now, "reply-task", request.runId()),
          RunEvent.blockStarted(
              2,
              now,
              "reply-task",
              new ResponseBlock.Text("text-task", "", ResponseBlock.Fidelity.NATIVE)),
          RunEvent.blockDelta(3, now, "reply-task", "text-task", "{\"recommendations\":[]}"),
          RunEvent.blockCompleted(4, now, "reply-task", "text-task"),
          RunEvent.replyEnded(5, now, "reply-task", AssistantReply.FinishReason.COMPLETED, ""),
          new RunEvent(6, RunEvent.Type.RUN_COMPLETED, now, Map.of()));
    }

    List<String> skillKeys() {
      return request.skills().stream().map(RunRequest.Skill::key).toList();
    }

    List<String> toolKeys() {
      return request.tools().stream().map(tool -> tool.descriptor().toolKey()).toList();
    }

    List<String> memoryEntries() {
      return request.memory().entries();
    }

    String toolOperationId() {
      return request.toolExecutionContext().operationId();
    }
  }

  private static final class WorkoutPlanConfirmationAdapter implements AgentFrameworkAdapter {
    private final UUID knownId;
    private final UUID unknownId;

    private WorkoutPlanConfirmationAdapter(UUID knownId, UUID unknownId) {
      this.knownId = knownId;
      this.unknownId = unknownId;
    }

    @Override
    public String key() {
      return "agentscope";
    }

    @Override
    public FrameworkCapabilities capabilities() {
      return new FrameworkCapabilities(true, true, true, true, true, true);
    }

    @Override
    public void validate(happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig config) {}

    @Override
    public Flux<RunEvent> run(RunRequest request) {
      Instant now = Instant.now();
      return Flux.just(
          RunEvent.replyStarted(1, now, "reply-plan", request.runId()),
          new RunEvent(
              2,
              RunEvent.Type.TOOL_RESULT,
              now,
              Map.of(
                  "toolName",
                  "fitness_exercise_candidates_query",
                  "result",
                  Map.of(
                      "candidates",
                      List.of(Map.of("exerciseId", knownId.toString(), "name", "深蹲"))))),
          new RunEvent(
              3,
              RunEvent.Type.CONFIRMATION_REQUIRED,
              now,
              Map.of(
                  "toolName",
                  "fitness_plan_save",
                  "arguments",
                  Map.of(
                      "request",
                      Map.of(
                          "scope",
                          "DAY",
                          "days",
                          List.of(
                              Map.of(
                                  "scheduledFor",
                                  "2026-08-13",
                                  "title",
                                  "全身循环训练",
                                  "estimatedMinutes",
                                  20,
                                  "exerciseIds",
                                  List.of(knownId.toString(), unknownId.toString()))))))),
          new RunEvent(
              4, RunEvent.Type.RUN_WAITING_APPROVAL, now, Map.of("toolName", "fitness_plan_save")),
          RunEvent.replySuspended(5, now, "reply-plan", "USER_CONFIRMATION"));
    }
  }

  private static final class DirectConfirmationAdapter implements AgentFrameworkAdapter {
    @Override
    public String key() {
      return "spring-ai-alibaba";
    }

    @Override
    public FrameworkCapabilities capabilities() {
      return new FrameworkCapabilities(true, true, true, true, true, true);
    }

    @Override
    public void validate(happy.jayden.yang.agentbuilder.core.defaults.ResolvedAgentConfig config) {}

    @Override
    public Flux<RunEvent> run(RunRequest request) {
      Instant now = Instant.now();
      return Flux.just(
          RunEvent.replyStarted(1, now, "reply-1", request.runId()),
          new RunEvent(
              2,
              RunEvent.Type.CONFIRMATION_REQUIRED,
              now,
              Map.of("toolName", "test_write", "arguments", Map.of("note", "SAA 生成的计划"))),
          new RunEvent(
              3, RunEvent.Type.RUN_WAITING_APPROVAL, now, Map.of("toolName", "test_write")),
          RunEvent.replySuspended(4, now, "reply-1", "USER_CONFIRMATION"));
    }
  }

  static final class ConfirmedWriteTool {
    private final AtomicReference<String> savedNote;
    private final AtomicInteger invocations;

    ConfirmedWriteTool(AtomicReference<String> savedNote) {
      this(savedNote, new AtomicInteger());
    }

    ConfirmedWriteTool(AtomicReference<String> savedNote, AtomicInteger invocations) {
      this.savedNote = savedNote;
      this.invocations = invocations;
    }

    @AgentTool(
        key = "test.write",
        version = 1,
        runtimeName = "test_write",
        displayName = "确认写入",
        description = "确认后写入测试计划",
        whenToUse = "用户确认保存时",
        whenNotToUse = "用户未确认时",
        applicationKey = "test",
        group = "plan",
        sideEffect = ToolSideEffect.WRITE,
        risk = ToolRiskLevel.MEDIUM,
        requiredScopes = {"test.write"},
        outputDescription = "保存结果")
    String write(
        @AgentToolParam(name = "note", description = "冻结的计划文本") String note,
        ToolExecutionContext context) {
      invocations.incrementAndGet();
      savedNote.set(note);
      return "saved";
    }
  }

  static final class ConfirmedPlanTool {
    @AgentTool(
        key = "fitness.plan.save",
        version = 1,
        runtimeName = "fitness_plan_save",
        displayName = "保存训练计划",
        description = "确认后保存测试训练计划",
        whenToUse = "用户确认保存时",
        whenNotToUse = "用户未确认时",
        applicationKey = "fitness",
        group = "plan",
        sideEffect = ToolSideEffect.WRITE,
        risk = ToolRiskLevel.MEDIUM,
        requiredScopes = {"fitness.write"},
        outputDescription = "保存结果")
    String save(
        @AgentToolParam(name = "request", description = "冻结的训练计划") ConfirmedPlanRequest request,
        ToolExecutionContext context) {
      return "saved";
    }
  }

  static final class TrustedExerciseCandidateTool {
    @AgentTool(
        key = "fitness.exercise.candidates.query",
        version = 1,
        runtimeName = "fitness_exercise_candidates_query",
        displayName = "筛选候选动作",
        description = "返回可信候选动作",
        whenToUse = "制定训练计划时",
        whenNotToUse = "无需制定训练计划时",
        applicationKey = "fitness",
        group = "exercise",
        requiredScopes = {"fitness.read"},
        outputDescription = "候选动作")
    String query(ToolExecutionContext context) {
      return context.userId();
    }
  }

  record ConfirmedPlanRequest(
      @AgentToolParam(description = "计划范围") String scope,
      @AgentToolParam(description = "逐日训练计划") List<ConfirmedPlanDay> days) {}

  record ConfirmedPlanDay(
      @AgentToolParam(description = "训练日期") String scheduledFor,
      @AgentToolParam(description = "训练标题") String title,
      @AgentToolParam(description = "预计分钟") int estimatedMinutes,
      @AgentToolParam(description = "动作 ID") List<UUID> exerciseIds) {}

  static final class BackgroundTaskTools {
    @AgentTool(
        key = "test.read",
        version = 1,
        runtimeName = "test_read",
        displayName = "读取后台任务数据",
        description = "读取后台任务数据",
        whenToUse = "后台任务需要数据时",
        whenNotToUse = "无需数据时",
        applicationKey = "test",
        group = "task",
        requiredScopes = {"test.read"},
        outputDescription = "后台任务数据")
    String read(ToolExecutionContext context) {
      return context.userId();
    }

    @AgentTool(
        key = "test.write",
        version = 1,
        runtimeName = "test_write_background",
        displayName = "写入后台任务数据",
        description = "写入后台任务数据",
        whenToUse = "不应在只读任务使用",
        whenNotToUse = "每日三餐后台任务",
        applicationKey = "test",
        group = "task",
        sideEffect = ToolSideEffect.WRITE,
        risk = ToolRiskLevel.MEDIUM,
        requiredScopes = {"test.write"},
        outputDescription = "写入结果")
    String write(ToolExecutionContext context) {
      return context.userId();
    }
  }
}
