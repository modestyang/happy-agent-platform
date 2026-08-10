package happy.jayden.yang.fitness;

import happy.jayden.yang.agentbuilder.core.tool.ToolExecutionContext;
import happy.jayden.yang.agentbuilder.core.tool.ToolRegistry;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository;
import happy.jayden.yang.agentbuilder.infrastructure.workbench.JdbcRunTraceRepository.ApprovalRecord;
import happy.jayden.yang.fitness.service.FitnessApplicationService;
import happy.jayden.yang.fitness.service.FitnessDtos.BootstrapData;
import happy.jayden.yang.fitness.service.FitnessDtos.ExerciseDto;
import happy.jayden.yang.fitness.service.FitnessExceptions.ConflictException;
import happy.jayden.yang.fitness.service.FitnessExceptions.DependencyUnavailableException;
import happy.jayden.yang.fitness.service.FitnessExceptions.InvalidRequestException;
import happy.jayden.yang.fitness.service.FitnessExceptions.NotFoundException;
import happy.jayden.yang.fitness.service.FitnessPorts.AiConversation;
import happy.jayden.yang.fitness.service.FitnessPorts.AiStreamListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Durable orchestration for app and developer-console AI runs. */
@Service
public class FitnessAgentRunService {

  private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");
  private static final String AGENT_KEY = "fitness.coach";
  private static final String SAVE_PLAN_TOOL = "fitness.plan.save";
  private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
  private static final Pattern CHINESE_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");

  private final FitnessApplicationService fitness;
  private final AiConversation conversation;
  private final JdbcRunTraceRepository traces;
  private final ToolRegistry tools;
  private final Executor executor;

  public FitnessAgentRunService(
      FitnessApplicationService fitness,
      AiConversation conversation,
      JdbcRunTraceRepository traces,
      ToolRegistry tools,
      @Qualifier("applicationTaskExecutor") Executor executor) {
    this.fitness = fitness;
    this.conversation = conversation;
    this.traces = traces;
    this.tools = tools;
    this.executor = executor;
  }

  public RunAccepted startUser(String sessionToken, String message) {
    return start(fitness.authenticateSession(sessionToken), message);
  }

  public RunAccepted startDeveloper(String message) {
    return start(fitness.developerUserId(), message);
  }

  public RunAccepted decideUser(
      String sessionToken, UUID runId, UUID approvalId, String decision, String idempotencyKey) {
    UUID userId = fitness.authenticateSession(sessionToken);
    requireOwner(runId, userId);
    return decide(runId, approvalId, userId, decision, idempotencyKey);
  }

  public RunAccepted decideDeveloper(
      UUID runId, UUID approvalId, String decision, String idempotencyKey) {
    UUID userId = traces.findRunOwner(runId).orElseThrow(() -> new NotFoundException("运行记录不存在"));
    return decide(runId, approvalId, userId, decision, idempotencyKey);
  }

  public SseEmitter streamUser(String sessionToken, UUID runId, String lastEventId) {
    requireOwner(runId, fitness.authenticateSession(sessionToken));
    return stream(runId, lastEventId);
  }

  public SseEmitter streamDeveloper(UUID runId, String lastEventId) {
    if (traces.findRunOwner(runId).isEmpty()) throw new NotFoundException("运行记录不存在");
    return stream(runId, lastEventId);
  }

  private RunAccepted start(UUID userId, String message) {
    String input = requireMessage(message);
    UUID runId = UUID.randomUUID();
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<UUID> conversationId = new AtomicReference<>();
    AtomicReference<RuntimeException> startupFailure = new AtomicReference<>();
    AtomicBoolean failed = new AtomicBoolean();
    executor.execute(
        () -> {
          try {
            conversation.sendStreaming(
                userId,
                runId,
                input,
                new AiStreamListener() {
                  @Override
                  public void onStarted(UUID value) {
                    conversationId.set(value);
                    traces.appendStreamEvent(
                        runId, "RUN_STATE", Map.of("status", "RUNNING", "summary", "已建立运行上下文"));
                    started.countDown();
                  }

                  @Override
                  public void onProgress(String summary) {
                    traces.appendStreamEvent(
                        runId, "RUN_STATE", Map.of("status", "RUNNING", "summary", summary));
                  }

                  @Override
                  public void onTextDelta(String delta) {
                    traces.appendStreamEvent(
                        runId, "TEXT_DELTA", Map.of("messageId", runId.toString(), "delta", delta));
                  }

                  @Override
                  public void onCompleted() {
                    finishModelRun(userId, runId, input);
                  }

                  @Override
                  public void onFailed(String error) {
                    failed.set(true);
                    traces.appendStreamEvent(runId, "ERROR", Map.of("message", safe(error)));
                    traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "FAILED"));
                  }
                });
          } catch (RuntimeException exception) {
            if (conversationId.get() == null) startupFailure.set(exception);
            else if (!failed.get()) {
              traces.appendStreamEvent(
                  runId, "ERROR", Map.of("message", safe(exception.getMessage())));
              traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "FAILED"));
            }
          } finally {
            started.countDown();
          }
        });
    try {
      if (!started.await(5, TimeUnit.SECONDS)) {
        throw new DependencyUnavailableException("启动 AI 运行超时");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new DependencyUnavailableException("启动 AI 运行被中断", exception);
    }
    if (startupFailure.get() != null) throw startupFailure.get();
    if (conversationId.get() == null) throw new DependencyUnavailableException("无法创建 AI 运行");
    return accepted(runId, conversationId.get(), "RUNNING");
  }

  private void finishModelRun(UUID userId, UUID runId, String input) {
    String status = traces.findRunStatus(runId).orElse("FAILED");
    if (!"SUCCEEDED".equals(status)) {
      traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", status));
      return;
    }
    if (!isPlanRequest(input)) {
      traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "SUCCEEDED"));
      return;
    }
    try {
      PlanProposal proposal = createProposal(userId, input);
      UUID approvalId = UUID.randomUUID();
      Map<String, Object> arguments = proposal.toolArguments(approvalId);
      ApprovalRecord approval =
          traces.requestApproval(
              approvalId,
              runId,
              userId,
              SAVE_PLAN_TOOL,
              proposal.scope().equals("WEEK") ? "保存未来 7 天训练计划" : "保存当天训练计划",
              arguments);
      traces.appendStreamEvent(
          runId, "RUN_STATE", Map.of("status", "WAITING_APPROVAL", "summary", "计划已准备好，等待你确认保存"));
      Map<String, Object> approvalData = new LinkedHashMap<>();
      approvalData.put("approvalId", approval.approvalId().toString());
      approvalData.put("toolCallId", approval.toolCallId().toString());
      approvalData.put("status", "REQUESTED");
      approvalData.put("title", approval.title());
      approvalData.put("proposal", proposal.display());
      traces.appendStreamEvent(runId, "APPROVAL", approvalData);
    } catch (RuntimeException exception) {
      traces.updateRunStatus(runId, "FAILED");
      traces.appendStreamEvent(runId, "ERROR", Map.of("message", safe(exception.getMessage())));
      traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "FAILED"));
    }
  }

  private RunAccepted decide(
      UUID runId, UUID approvalId, UUID userId, String decision, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new InvalidRequestException("Idempotency-Key 必填");
    }
    String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
    boolean alreadyDecided =
        traces
            .findApproval(runId, approvalId)
            .map(value -> !"REQUESTED".equals(value.status()))
            .orElse(false);
    ApprovalRecord approval;
    try {
      approval = traces.decideApproval(runId, approvalId, userId, normalized, idempotencyKey);
    } catch (IllegalStateException exception) {
      if (exception.getMessage().contains("not found")) throw new NotFoundException("确认请求不存在");
      throw new ConflictException("该确认请求已处理或不属于当前用户");
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException("decision 必须是 APPROVE 或 REJECT");
    }
    if (alreadyDecided) {
      String status = "APPROVE".equals(normalized) ? "SUCCEEDED" : "CANCELLED";
      return accepted(runId, traces.findRunConversation(runId).orElse(null), status);
    }
    if ("REJECT".equals(normalized)) {
      traces.appendStreamEvent(
          runId,
          "APPROVAL",
          Map.of(
              "approvalId", approvalId.toString(),
              "toolCallId", approval.toolCallId().toString(),
              "status", "REJECTED"));
      traces.appendStreamEvent(
          runId,
          "TEXT_DELTA",
          Map.of("messageId", runId.toString(), "delta", "\n\n好的，这份训练计划没有保存。"));
      traces.updateRunStatus(runId, "CANCELLED");
      traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "CANCELLED"));
      return accepted(runId, traces.findRunConversation(runId).orElse(null), "CANCELLED");
    }
    try {
      traces.appendStreamEvent(
          runId,
          "TOOL",
          Map.of(
              "toolCallId",
              approval.toolCallId().toString(),
              "toolKey",
              SAVE_PLAN_TOOL,
              "contractVersion",
              1,
              "phase",
              "RUNNING"));
      tools.invoke(
          SAVE_PLAN_TOOL,
          approval.arguments(),
          new ToolExecutionContext(
              userId.toString(), runId.toString(), Set.of("fitness.write"), "approval.execute"));
      traces.appendStreamEvent(
          runId,
          "TOOL",
          Map.of(
              "toolCallId",
              approval.toolCallId().toString(),
              "toolKey",
              SAVE_PLAN_TOOL,
              "contractVersion",
              1,
              "phase",
              "SUCCEEDED"));
      traces.appendStreamEvent(
          runId,
          "APPROVAL",
          Map.of(
              "approvalId", approvalId.toString(),
              "toolCallId", approval.toolCallId().toString(),
              "status", "APPROVED"));
      traces.appendStreamEvent(
          runId,
          "TEXT_DELTA",
          Map.of("messageId", runId.toString(), "delta", "\n\n已按你确认的内容保存到训练计划。"));
      traces.updateRunStatus(runId, "SUCCEEDED");
      traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "SUCCEEDED"));
      return accepted(runId, traces.findRunConversation(runId).orElse(null), "SUCCEEDED");
    } catch (Exception exception) {
      traces.updateRunStatus(runId, "FAILED");
      traces.appendStreamEvent(
          runId,
          "TOOL",
          Map.of(
              "toolCallId",
              approval.toolCallId().toString(),
              "toolKey",
              SAVE_PLAN_TOOL,
              "contractVersion",
              1,
              "phase",
              "FAILED"));
      traces.appendStreamEvent(runId, "ERROR", Map.of("message", safe(exception.getMessage())));
      traces.appendStreamEvent(runId, "COMPLETED", Map.of("status", "FAILED"));
      throw new DependencyUnavailableException("保存训练计划失败", exception);
    }
  }

  private SseEmitter stream(UUID runId, String lastEventId) {
    long initial = parseEventId(lastEventId);
    SseEmitter emitter = new SseEmitter(130_000L);
    executor.execute(
        () -> {
          long cursor = initial;
          try {
            boolean terminal = false;
            while (!terminal) {
              var events = traces.streamEventsAfter(runId, cursor);
              for (var event : events) {
                cursor = event.sequence();
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", Long.toString(event.sequence()));
                envelope.put("runId", runId.toString());
                envelope.put("type", event.type());
                envelope.put("occurredAt", event.occurredAt().toString());
                envelope.put("data", event.data());
                emitter.send(
                    SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.type())
                        .data(envelope));
                terminal = "COMPLETED".equals(event.type());
              }
              if (!terminal) Thread.sleep(150L);
            }
            emitter.complete();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(exception);
          } catch (Exception exception) {
            emitter.completeWithError(exception);
          }
        });
    return emitter;
  }

  private PlanProposal createProposal(UUID userId, String input) {
    BootstrapData data = fitness.loadForTool(userId);
    if (data.exercises().isEmpty()) throw new InvalidRequestException("动作库为空，暂时无法生成计划");
    String scope = weekly(input) ? "WEEK" : "DAY";
    LocalDate start = requestedDate(input);
    int count = "WEEK".equals(scope) ? 7 : 1;
    var days = new ArrayList<PlanDay>();
    for (int dayIndex = 0; dayIndex < count; dayIndex++) {
      LocalDate date = start.plusDays(dayIndex);
      var exercises = new ArrayList<ExerciseDto>();
      int perDay = Math.min(4, data.exercises().size());
      for (int exerciseIndex = 0; exerciseIndex < perDay; exerciseIndex++) {
        exercises.add(
            data.exercises().get((dayIndex * 2 + exerciseIndex) % data.exercises().size()));
      }
      days.add(new PlanDay(date, title(dayIndex), 30, List.copyOf(exercises)));
    }
    return new PlanProposal(scope, List.copyOf(days));
  }

  private static String title(int dayIndex) {
    String[] titles = {"全身基础训练", "低冲击有氧与核心", "下肢稳定训练", "上肢与体态训练", "恢复拉伸", "全身循环训练", "主动恢复"};
    return titles[dayIndex % titles.length];
  }

  private static LocalDate requestedDate(String input) {
    LocalDate today = LocalDate.now(USER_ZONE);
    if (input.contains("明天")) return today.plusDays(1);
    Matcher iso = ISO_DATE.matcher(input);
    if (iso.find()) {
      try {
        return LocalDate.parse(iso.group(1), DateTimeFormatter.ISO_LOCAL_DATE);
      } catch (DateTimeParseException ignored) {
        // Continue with the friendly Chinese format or today's date.
      }
    }
    Matcher chinese = CHINESE_DATE.matcher(input);
    if (chinese.find()) {
      try {
        return LocalDate.of(
            today.getYear(),
            Integer.parseInt(chinese.group(1)),
            Integer.parseInt(chinese.group(2)));
      } catch (RuntimeException ignored) {
        // Invalid dates are handled as an unspecified date.
      }
    }
    return today;
  }

  private static boolean isPlanRequest(String input) {
    return input.contains("训练计划")
        && (input.contains("生成") || input.contains("制定") || input.contains("安排"));
  }

  private static boolean weekly(String input) {
    return input.contains("一周")
        || input.contains("本周")
        || input.contains("7天")
        || input.contains("七天")
        || input.contains("未来7天");
  }

  private void requireOwner(UUID runId, UUID userId) {
    UUID owner = traces.findRunOwner(runId).orElseThrow(() -> new NotFoundException("运行记录不存在"));
    if (!owner.equals(userId)) throw new NotFoundException("运行记录不存在");
  }

  private static String requireMessage(String message) {
    if (message == null || message.isBlank()) throw new InvalidRequestException("message 不能为空");
    return message.trim();
  }

  private static long parseEventId(String value) {
    if (value == null || value.isBlank()) return 0;
    try {
      return Math.max(0L, Long.parseLong(value));
    } catch (NumberFormatException exception) {
      throw new InvalidRequestException("Last-Event-ID 不合法");
    }
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "AI 运行失败" : value;
  }

  private static RunAccepted accepted(UUID runId, UUID conversationId, String status) {
    Instant now = Instant.now();
    return new RunAccepted(
        runId,
        conversationId,
        status,
        "/api/v1/app/ai/runs/" + runId + "/events",
        List.of(),
        now,
        now);
  }

  public record RunAccepted(
      UUID runId,
      UUID sessionId,
      String status,
      String eventStreamUrl,
      List<Object> result,
      Instant createdAt,
      Instant updatedAt) {}

  private record PlanDay(
      LocalDate date, String title, int estimatedMinutes, List<ExerciseDto> exercises) {}

  private record PlanProposal(String scope, List<PlanDay> days) {
    Map<String, Object> toolArguments(UUID approvalId) {
      return Map.of(
          "request",
          Map.of(
              "approvalId",
              approvalId.toString(),
              "scope",
              scope,
              "days",
              days.stream()
                  .map(
                      day ->
                          Map.of(
                              "scheduledFor",
                              day.date().toString(),
                              "title",
                              day.title(),
                              "estimatedMinutes",
                              day.estimatedMinutes(),
                              "exerciseIds",
                              day.exercises().stream().map(item -> item.id().toString()).toList()))
                  .toList()));
    }

    Map<String, Object> display() {
      return Map.of(
          "scope",
          scope,
          "days",
          days.stream()
              .map(
                  day ->
                      Map.of(
                          "date",
                          day.date().toString(),
                          "title",
                          day.title(),
                          "estimatedMinutes",
                          day.estimatedMinutes(),
                          "exercises",
                          day.exercises().stream()
                              .map(
                                  item ->
                                      Map.of(
                                          "exerciseId", item.id().toString(), "name", item.name()))
                              .toList()))
              .toList());
    }
  }
}
